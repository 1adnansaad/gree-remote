# AGENTS.md

## This branch: `android-app`

This branch is for the **Android app version** of gree-remote. Android work happens here, not on `master`.

- The app is [GreeAndroidLite/](GreeAndroidLite/). [GreeRemoteAndroid/](GreeRemoteAndroid/) is the older upstream app; leave it alone.
- The other folders (PythonCLI, GreeLib, GreeRemoteGuiQt, GreeESP8266, GreeBlynkBridge) come from upstream. Read them for the protocol, but don't change them on this branch.

## GreeAndroidLite

- Plain Java, no Kotlin, no AndroidX and no other libraries. The UI is built in code in `MainActivity.java`, with no XML layouts.
- `Gree.java` speaks the LAN protocol: JSON over UDP port 7000, encrypted with AES ECB or GCM. See [README.md](README.md) and [PythonCLI/gree.py](PythonCLI/gree.py).
- What the user sets for each AC is stored in `SharedPreferences`, keyed by the unit's MAC: its name, whether it beeps, and whether it is pinned. So is each AC's IP and key, saved when the app pairs with it. At launch the app talks to the pinned AC straight away, with no scan and no pairing. It scans only if the AC doesn't answer, for example because it has a new IP.
- `Watch.java` is a foreground service. It runs while an AC is pinned and restarts after a reboot or an app update.
  - It listens for Wi-Fi joining and leaving.
  - When the pinned AC answers at its saved IP, the service's notification shows controls for it: power, −1 °C and +1 °C.
  - Otherwise the notification is a minimized "Looking for …" line.
  - It never scans in the background. Opening the app does the scan, which finds an AC that has moved to a new IP.
  - Android allows no lighter way to hear about leaving Wi-Fi: the `PendingIntent` network callback fires only when a network appears, and only once.
- Build: `cd GreeAndroidLite && ./gradlew assembleDebug`
- Test: `./gradlew testDebugUnitTest`. `GreeTest` runs a fake unit on loopback.
