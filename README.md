# SyncLynk

Sync your Windows PC and Android phone over your local Wi-Fi — no
accounts, no database, no cloud. Pair by scanning a QR code, then get:

- **Universal clipboard** — copy on one device, paste on the other.
- **Notification mirroring** — Android notifications show up as
  Windows toasts.
- **Camera mirroring** — stream the phone's live camera to a window
  on the PC (use it as a webcam feed, document scanner, etc.).

Everything lives in memory for the life of the two apps. Close the
Windows app and the pairing code is gone — reopen it and you get a
fresh one.

## How pairing works (the "safety without login" part)

You asked for no login and no security friction — this is the
lightest version of that which still isn't wide open:

1. The Windows app generates a random 8-character code when it starts
   and shows it inside a QR code.
2. The phone scans the QR and sends that code as its first message.
3. If it matches, the two devices are linked for this session. If a
   random device on the same Wi-Fi tries to connect without ever
   seeing the QR code, it can't guess the token and gets disconnected.

No passwords to remember, nothing stored on disk, nothing sent
off your network — just "you had to be able to see my screen to
connect."

## Project layout

```
synclynk/
  windows-app/     Python desktop app (Tkinter GUI + asyncio WebSocket server)
  android-app/      Android Studio project (Kotlin)
  PROTOCOL.md        Wire format shared by both sides
```

## Running the Windows app

```bash
cd windows-app
pip install -r requirements.txt
python main.py
```

A window opens showing a QR code plus the IP/port/code in text form
(in case you'd rather type it in than scan). Leave it running.

## Running the Android app

1. Open `android-app/` in Android Studio (Giraffe or newer).
2. Let Gradle sync — it pulls OkHttp, ZXing, and CameraX from Maven,
   nothing custom to configure.
3. Run it on a phone that's on the **same Wi-Fi network** as the PC.
4. Tap **Scan QR to Connect** and point it at the PC's window.
5. Optional, one-time: tap **Enable Notification Mirroring** — Android
   requires you to flip this on manually in Settings; there's no way
   for any app to skip that screen, by OS design.
6. Tap **Start Camera Mirror** whenever you want to send the live
   camera feed to the PC.

Clipboard sync and notification mirroring start working as soon as
pairing succeeds — nothing extra to press.

## Notes / things to know before you extend this

- The Android **NotificationListenerService** permission is the one
  thing Android won't let an app grant itself. That's an OS-level
  privacy guardrail, not something this project adds on top.
- Camera streaming here is plain JPEG-over-WebSocket, not WebRTC — it's
  simple and dependency-light, good for LAN use, but higher-latency
  than a proper WebRTC pipeline. If you eventually want near-zero-lag
  video (e.g. for gaming or serious webcam use), swapping the camera
  transport for WebRTC is the natural next step.
- Because there's no database, reconnecting after a Wi-Fi drop just
  means: reopen/refocus the Windows app to see the (same) QR again, or
  rescan if the app was restarted and generated a new token.
- The Windows app's camera window uses OpenCV's `imshow`, the simplest
  way to get a "live video window" without pulling in a full media
  framework. Swap it for a Qt widget later if you want it embedded in
  the main GUI instead of a separate window.

## Extending it

Adding a new synced feature (file drag-and-drop, media/volume control,
remote input, "ring my phone", etc.) is just:
1. Add a new `type` to `PROTOCOL.md`.
2. Add a handler with `server.on_message("your_type", fn)` on Windows.
3. Add a matching branch in `SocketManager.Listener.onMessage` on
   Android.

No auth changes, no schema, no backend — the whole point of skipping
the database is that adding features stays this simple.

