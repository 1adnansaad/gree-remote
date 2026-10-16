# AGENTS.md

## This branch: `android-app`

This branch is for the **Android app version** of gree-remote. Android work happens here, not on `master`.

- The app is [GreeAndroidLite/](GreeAndroidLite/). [GreeRemoteAndroid/](GreeRemoteAndroid/) is the older upstream app; leave it alone.
- The other folders (PythonCLI, GreeLib, GreeRemoteGuiQt, GreeESP8266, GreeBlynkBridge) come from upstream. Read them for the protocol, but don't change them on this branch.

## GreeAndroidLite

- Plain Java, no Kotlin, no AndroidX and no other libraries. The UI is built in code in `MainActivity.java`, with no XML layouts.
- `Gree.java` speaks the LAN protocol: JSON over UDP port 7000, encrypted with AES ECB or GCM. See [README.md](README.md) and [PythonCLI/gree.py](PythonCLI/gree.py).
- What the user sets for each AC is stored in `SharedPreferences`, keyed by the unit's MAC: its name, whether it beeps, and whether it is pinned. So is each AC's IP and key, saved when the app pairs with it. At launch the app talks to the pinned AC straight away, with no scan and no pairing. It scans only if the AC doesn't answer, for example because it has a new IP.
- Build: `cd GreeAndroidLite && ./gradlew assembleDebug`
- Test: `./gradlew testDebugUnitTest`. `GreeTest` runs a fake unit on loopback.
