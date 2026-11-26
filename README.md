# ADBLink

Use the real `adb` from Termux to control a second Android phone over a USB OTG cable, from a phone
that is not rooted and has no computer nearby.

The app in this repo is a small USB relay. It holds the USB connection to the target phone and
forwards raw ADB packets between the target's `adbd` and a local TCP port (`127.0.0.1:6000`). Termux's
own `adb` connects to that port, so every normal adb command works: `shell` (with proper terminal
sizing), `push`, `pull`, `install`, `logcat`, `reboot`, `forward` and so on. The app never holds
adb keys. RSA authentication happens between Termux's `adb` and the target phone, and the target
shows its usual "Allow USB debugging?" prompt.

```
Termux (adb client)  <-- TCP 127.0.0.1:6000 -->  this app  <-- USB OTG -->  target phone (adbd)
```

## Requirements

- Host phone: Android 5.0+ with USB host (OTG) support, Termux installed, no root needed.
- Target phone: USB debugging turned on. Tested by the author on an Android 11 target.
- An OTG adapter or cable between the two phones.

## Use

1. Build or install the APK (see below) on the host phone.
2. Turn on USB debugging on the target. Plug it into the host through the OTG cable.
3. Open the app and grant the USB permission when asked. The app keeps running as a foreground
   service and reconnects by itself if the cable drops.
4. In Termux:

   ```
   pkg install android-tools
   adb kill-server
   adb connect 127.0.0.1:6000
   adb devices
   ```

   The app's "Copy Termux commands" button copies these for you.
5. Accept the debugging prompt on the target (tick "Always allow" to skip it next time).

Notes:

- The port is 6000 on purpose. Stock `adb` probes `localhost` ports 5555-5585 for emulators and would
  register extra phantom devices there.
- One adb client at a time. A new connection replaces the old one.
- Any app on the host phone can reach `127.0.0.1`, so treat the relay like an open adb port while it
  runs. Stop the service when you are done.

## Build

The short version is below; [BUILDING.md](BUILDING.md) has the full
step-by-step guide, including troubleshooting.

Needs JDK 17 and the Android SDK (platform 34). Then:

```
echo "sdk.dir=/path/to/android/sdk" > local.properties
chmod +x gradlew   # the web upload did not keep the executable bit
./gradlew :cli:testDebugUnitTest :cli:assembleDebug
```

The APK ends up in `cli/build/outputs/apk/debug/`. The unit tests run a fake adb target over a local
socket (no hardware). At the time of publishing they pass; one timing-sensitive test
(`garbageBytesCloseSessionWithHexEvidence`) failed once on a slow single-core build machine and
passed on re-run.

## Honest notes

- **AI-assisted.** This project was developed with an AI assistant doing most of the code writing,
  with the author directing it, installing it on real phones and testing it. Read the code before you
  trust it with a device you care about.
- **Narrow testing.** Tested on one pair of phones. Other devices, Android versions, USB hubs and
  adapters may behave differently.
- **Not a full adb replacement.** It relays whatever the Termux `adb` sends. If something fails, the
  in-app log shows link events (USB detach, drop reasons, reconnects).
- **Security.** Debugging access is powerful. Only plug in phones you own, and review the prompt on
  the target before allowing a host.

## Credits and licensing

- This project grew out of [KhunHtetzNaing/ADB-OTG](https://github.com/KhunHtetzNaing/ADB-OTG)
  ("Android Debug Bridge On The Go"). That repo has no license file, so no code from it is included
  here. The package name `com.htetznaing.adbotg` is kept for continuity.
- `adblib/` has two files from [AdbLib](https://github.com/cgutman/AdbLib) by Cameron Gutman, under
  the BSD 3-Clause license (`adblib/LICENSE`). Details and the one change made are in
  [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
- Everything else is MIT licensed, see [LICENSE](LICENSE).
