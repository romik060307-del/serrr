# RK Tunnel — one-tap public tunnel for old Android

**Target device:** Android 4.4.2 / API 19, 32-bit ARMv7 (best-effort; the exact Advan T2E hardware must be tested).

RK Tunnel v1.0.1 is an Android wrapper for Cloudflare Quick Tunnels. It embeds a Linux ARMv7 build of the open-source `cloudflared` client inside the APK and gives the user a simple UI to publish an already-running local HTTP service.

## What it does

- Enter a local origin URL (default `http://127.0.0.1:8080/`).
- Tap **AKTIFKAN SERVER PUBLIK**.
- The app launches `cloudflared tunnel --url ... --protocol http2 --no-autoupdate`.
- If the client starts successfully, the app reads the temporary `https://…trycloudflare.com` URL from its output and offers a copy button.
- Stop the tunnel from the app.

The APK does **not** create the website/server on port 8080. Start RK Server first, or enter the address of a web service already running on the tablet. This app only tunnels that local HTTP service to a public URL.

## Important compatibility caveat

The Android app UI targets API 19 and the workflow builds a 32-bit ARMv7 Linux `cloudflared` executable. The workflow also applies an Android DNS resolver path workaround and bundles a CA certificate bundle. However, Cloudflare does not officially publish an Android 4.4 build of `cloudflared`; running a Linux ARM executable from an unrooted Android 4.4 app is **not guaranteed**. The APK must be tested on the actual tablet. Version 1.0.1 avoids ProcessBuilder.redirectInput(File), which is API 26+ and crashes on Android 4.4/API 19; it connects stdin through /system/bin/sh instead. If Android rejects execution or cloudflared's Go runtime cannot resolve/connect, the app will show a failure in the log area. Do not claim a working tunnel until a test from another network succeeds.

## Quick Tunnel limits

A Quick Tunnel is temporary. It runs only while the `cloudflared` process and network connection remain active, and a new run normally gets a different random `trycloudflare.com` address. It is intended for testing/development, not permanent production hosting. Requests to that URL are relayed by Cloudflare; this avoids home-router port forwarding and normally works behind CGNAT, but it is not a fully self-contained server with zero outside infrastructure.

Official docs:
- https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/do-more-with-tunnels/trycloudflare/
- https://developers.cloudflare.com/tunnel/get-started/quick-tunnels/

## Build APK via GitHub Actions

1. Create a new repository named `rk-tunnel`.
2. Upload the **contents** of this project to the repository root. `.github/workflows/build-apk.yml` must be at that exact path.
3. The workflow will check out Cloudflare's `cloudflared` source at tag `2026.10.4`, compile it for Linux ARMv7, add it to APK assets, and build the Android APK.
4. The workflow pins Java 17 for Gradle 8.9. This avoids `Unsupported class file major version 69` caused by a Java 25 runner. Open **Actions**. On a `push` to `main`/`master` it runs automatically. Or choose **Build RK Tunnel APK** → **Run workflow**.
5. When the workflow finishes successfully, download artifact `RK-Tunnel-APK`, extract it, and install `app-debug.apk` on the tablet.

The APK binary is built by GitHub Actions, so the initial source ZIP intentionally does not contain the large `cloudflared` binary. No Android Studio is needed.

## Test procedure

1. Leave the existing RK Server active on the tablet, serving port 8080.
2. Install and open RK Tunnel.
3. Keep the default `http://127.0.0.1:8080/`, unless your server uses another local address/port.
4. Tap **AKTIFKAN SERVER PUBLIK** and wait for the `trycloudflare.com` link.
5. Copy the link and test from a different phone with Wi-Fi turned off and mobile data enabled.
6. If the app says binary execution failed, the old Android/firmware does not permit this run method; if tunnel connects but the origin fails, check that the local server is active at the specified address.

## Security note

This publishes the selected local service to anyone who obtains its public link. Do not expose a prototype that has no authentication, or any files/admin endpoints containing sensitive data. Stop the tunnel when the test is finished.

## License

RK Tunnel wrapper source is provided as a prototype. Cloudflare `cloudflared` is separately licensed by Cloudflare; the workflow includes the upstream license file in the APK assets.
