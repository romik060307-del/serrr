package com.rk.tunnel;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.net.Uri;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TunnelService extends Service {
    public static final String ACTION_START = "com.rk.tunnel.START";
    public static final String ACTION_STOP = "com.rk.tunnel.STOP";
    public static final String ACTION_QUERY = "com.rk.tunnel.QUERY";
    public static final String ACTION_STATUS = "com.rk.tunnel.STATUS";
    public static final String EXTRA_ORIGIN = "origin";
    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_LOG = "log";
    public static final String EXTRA_ACTIVE = "active";
    private static final int NOTIFICATION_ID = 4201;
    private static final String CHANNEL_ID = "rk_tunnel_status";
    private static final Pattern URL_PATTERN = Pattern.compile("https://[a-z0-9-]+\\.trycloudflare\\.com", Pattern.CASE_INSENSITIVE);

    private volatile Process tunnelProcess;
    private volatile Thread startThread;
    private volatile boolean stopping = false;
    private volatile boolean active = false;
    private volatile String publicUrl = "";
    private volatile String lastStatus = "Siap";
    private volatile String lastLog = "Jalankan RK Server lebih dahulu, lalu tekan AKTIFKAN SERVER PUBLIK.";
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_QUERY : intent.getAction();
        if (ACTION_QUERY.equals(action)) {
            broadcastStatus();
            if (!active && tunnelProcess == null) stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(action)) {
            stopTunnel(true);
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action)) {
            String origin = intent.getStringExtra(EXTRA_ORIGIN);
            if (origin == null || origin.trim().length() == 0) origin = "http://127.0.0.1:8080/";
            stopTunnel(false);
            startForeground(NOTIFICATION_ID, makeNotification("Menyiapkan tunnel…"));
            startTunnelAsync(origin.trim());
            return START_NOT_STICKY;
        }
        return START_NOT_STICKY;
    }

    private void startTunnelAsync(final String origin) {
        stopping = false;
        active = true;
        publicUrl = "";
        lastStatus = "Menyiapkan tunnel…";
        lastLog = "Alamat layanan lokal: " + origin;
        broadcastStatus();
        startThread = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    launchTunnel(origin);
                } catch (Throwable t) {
                    // Android 4.4 lacks some newer ProcessBuilder methods. Keep unexpected
                    // linkage/runtime errors visible in the app instead of crashing the process.
                    if (t instanceof ThreadDeath) throw (ThreadDeath) t;
                    reportUnexpectedFailure(t);
                }
            }
        }, "RKTunnelStarter");
        startThread.start();
    }

    private void launchTunnel(String origin) {
        try {
            if (stopping) return;
            if (!isOriginReachable(origin)) {
                throw new IllegalStateException("Server lokal tidak menjawab di " + origin + ". Jalankan RK Server dahulu dan pastikan port/alamatnya benar.");
            }
            acquireLocks();
            File dir = getFilesDir();
            File binary = new File(dir, "cloudflared");
            File resolver = new File(dir, "resolv.conf");
            File caBundle = new File(dir, "cacert.pem");
            copyPatchedAsset("cloudflared", binary, true);
            copyPatchedAsset("cacert.pem", caBundle, false);
            writeText(resolver, "nameserver 1.1.1.1\nnameserver 8.8.8.8\noptions timeout:2 attempts:2\n");
            if (!binary.exists() || binary.length() < 1024 * 1024) {
                throw new IllegalStateException("Binary cloudflared belum ada di APK. Buat APK melalui workflow GitHub Actions RK Tunnel.");
            }
            if (!binary.setExecutable(true, true) && !binary.canExecute()) {
                throw new IllegalStateException("Android menolak menjalankan binary tunnel dari folder aplikasi.");
            }
            File home = new File(dir, "tunnel-home");
            if (!home.exists()) home.mkdirs();
            // ProcessBuilder.redirectInput(File) was added to Android in API 26.
            // Calling it crashes Android 4.4 (API 19) with NoSuchMethodError.
            // Use Android's /system/bin/sh to connect the resolver file to stdin before exec.
            String shellScript = "exec 0< \"$1\"; shift; exec \"$@\"";
            ProcessBuilder pb = new ProcessBuilder(
                    "/system/bin/sh", "-c", shellScript,
                    "rk-tunnel-launch", resolver.getAbsolutePath(),
                    binary.getAbsolutePath(), "tunnel", "--no-autoupdate",
                    "--url", origin, "--protocol", "http2"
            );
            pb.redirectErrorStream(true);
            pb.environment().put("HOME", home.getAbsolutePath());
            pb.environment().put("TMPDIR", getCacheDir().getAbsolutePath());
            pb.environment().put("SSL_CERT_FILE", caBundle.getAbsolutePath());
            pb.environment().put("GODEBUG", "netdns=go");
            pb.environment().put("TUNNEL_ORIGIN_CERT", "");
            lastStatus = "Menghubungkan ke Cloudflare…";
            lastLog = "Menjalankan tunnel ke " + origin + ". Tunggu sampai link trycloudflare.com muncul.";
            broadcastStatus();
            if (stopping) return;
            Process proc = pb.start();
            tunnelProcess = proc;
            BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream(), Charset.forName("UTF-8")));
            String line;
            StringBuilder tail = new StringBuilder();
            while (!stopping && (line = reader.readLine()) != null) {
                if (tail.length() > 1600) tail.delete(0, tail.length() - 900);
                tail.append(line).append('\n');
                Matcher matcher = URL_PATTERN.matcher(line);
                if (matcher.find()) {
                    publicUrl = matcher.group();
                    lastStatus = "TUNNEL PUBLIK AKTIF";
                    lastLog = "Link publik dibuat. Siapa pun yang memiliki link dapat mencoba membukanya. Link berubah jika tunnel dibuat ulang.\n" + tail.toString();
                    broadcastStatus();
                    updateNotification("Tunnel aktif: " + publicUrl);
                } else if (line.toLowerCase(Locale.US).contains("error") || line.toLowerCase(Locale.US).contains("failed")) {
                    lastLog = line;
                    broadcastStatus();
                }
            }
            int exit = -1;
            try { exit = proc.waitFor(); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            if (!stopping) {
                active = false;
                publicUrl = "";
                lastStatus = "Tunnel berhenti (kode " + exit + ")";
                lastLog = tail.toString().trim();
                if (lastLog.length() == 0) lastLog = "cloudflared berhenti tanpa pesan. Coba cek koneksi internet dan apakah server lokal hidup.";
                broadcastStatus();
                stopForeground(true);
                releaseLocks();
                stopSelf();
            }
        } catch (Exception e) {
            if (stopping) return;
            active = false;
            publicUrl = "";
            lastStatus = "Gagal mengaktifkan tunnel";
            lastLog = e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "tidak ada keterangan" : e.getMessage()) + "\nPeriksa bahwa server lokal aktif dan build APK menyertakan binary cloudflared ARM.";
            broadcastStatus();
            stopForeground(true);
            releaseLocks();
            stopSelf();
        }
    }

    private void reportUnexpectedFailure(Throwable t) {
        if (stopping) return;
        active = false;
        publicUrl = "";
        lastStatus = "Gagal memulai tunnel (kompatibilitas Android)";
        String message = t.getMessage();
        if (message == null || message.trim().length() == 0) message = "tanpa keterangan";
        lastLog = t.getClass().getName() + ": " + message
                + "\nVersi ini dibuat untuk API 19. Jika masih gagal, kirim teks Keterangan ini.";
        try { broadcastStatus(); } catch (Throwable ignored) { }
        try { stopForeground(true); } catch (Throwable ignored) { }
        releaseLocks();
        try { stopSelf(); } catch (Throwable ignored) { }
    }

    private void copyPatchedAsset(String assetName, File destination, boolean patchDns) throws Exception {
        if (destination.exists() && destination.length() > 1024 * 1024 && (!patchDns || destination.canExecute())) return;
        InputStream in = getAssets().open(assetName);
        FileOutputStream out = new FileOutputStream(destination);
        byte[] bytes = new byte[32768];
        int n;
        while ((n = in.read(bytes)) != -1) out.write(bytes, 0, n);
        in.close(); out.close();
        if (patchDns) {
            byte[] contents = readAll(destination);
            byte[] from = "/etc/resolv.conf".getBytes("US-ASCII");
            byte[] to = "//proc/self/fd/0".getBytes("US-ASCII");
            if (from.length != to.length) throw new IllegalStateException("Panjang patch DNS tidak cocok.");
            int count = 0;
            for (int i = 0; i <= contents.length - from.length; i++) {
                boolean match = true;
                for (int j = 0; j < from.length; j++) if (contents[i+j] != from[j]) { match = false; break; }
                if (match) {
                    System.arraycopy(to, 0, contents, i, to.length);
                    count++; i += from.length - 1;
                }
            }
            if (count > 0) {
                FileOutputStream patched = new FileOutputStream(destination, false);
                patched.write(contents); patched.close();
            }
            destination.setReadable(true, true);
            destination.setExecutable(true, true);
        }
    }

    private byte[] readAll(File file) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        java.io.FileInputStream in = new java.io.FileInputStream(file);
        byte[] b = new byte[32768]; int n;
        while ((n = in.read(b)) != -1) out.write(b, 0, n);
        in.close(); return out.toByteArray();
    }

    private boolean isOriginReachable(String origin) {
        Socket socket = new Socket();
        try {
            Uri uri = Uri.parse(origin);
            String host = uri.getHost();
            int port = uri.getPort();
            if (port < 0) port = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
            if (host == null) return false;
            socket.connect(new InetSocketAddress(host, port), 2500);
            return true;
        } catch (Exception ignored) {
            return false;
        } finally {
            try { socket.close(); } catch (Exception ignored) { }
        }
    }

    private void writeText(File file, String text) throws Exception {
        FileOutputStream out = new FileOutputStream(file, false);
        out.write(text.getBytes("UTF-8")); out.close();
        file.setReadable(true, true);
    }

    private void acquireLocks() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && (wakeLock == null || !wakeLock.isHeld())) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RK_Tunnel:Cpu");
                wakeLock.acquire();
            }
        } catch (Exception ignored) { }
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm != null && (wifiLock == null || !wifiLock.isHeld())) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL, "RK_Tunnel:Wifi");
                wifiLock.acquire();
            }
        } catch (Exception ignored) { }
    }

    private void releaseLocks() {
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) { }
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Exception ignored) { }
    }

    private void stopTunnel(boolean stopSelfToo) {
        stopping = true;
        active = false;
        publicUrl = "";
        lastStatus = "Tunnel dimatikan";
        Process p = tunnelProcess;
        tunnelProcess = null;
        if (p != null) {
            try { p.destroy(); } catch (Exception ignored) { }
        }
        Thread t = startThread;
        if (t != null) t.interrupt();
        broadcastStatus();
        stopForeground(true);
        releaseLocks();
        if (stopSelfToo) stopSelf();
    }

    private void broadcastStatus() {
        Intent i = new Intent(ACTION_STATUS);
        i.setPackage(getPackageName());
        i.putExtra(EXTRA_STATUS, lastStatus == null ? "" : lastStatus);
        i.putExtra(EXTRA_URL, publicUrl == null ? "" : publicUrl);
        i.putExtra(EXTRA_LOG, lastLog == null ? "" : lastLog);
        i.putExtra(EXTRA_ACTIVE, active);
        sendBroadcast(i);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "RK Tunnel", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Status tunnel publik RK");
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification makeNotification(String content) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, 0);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder.setSmallIcon(R.drawable.rk_icon)
                .setContentTitle("RK Tunnel")
                .setContentText(content)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String content) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, makeNotification(content));
    }

    @Override public void onDestroy() {
        stopping = true;
        Process p = tunnelProcess;
        tunnelProcess = null;
        if (p != null) {
            try { p.destroy(); } catch (Exception ignored) { }
        }
        releaseLocks();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
