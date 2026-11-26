# Building from source

This builds the USB relay app (`cli/` module) that bridges Termux `adb` to a
target phone over USB OTG.

## Requirements

- **JDK 17** (check with `java -version`)
- **Android SDK with platform 34** (Android 14) and a recent build-tools.
  Android Studio's SDK is fine; a command-line `cmdline-tools` install works too.
- A POSIX shell for the Gradle wrapper (Linux, macOS, or WSL/Git Bash on Windows).

## Steps

1. Clone the repository:

   ```
   git clone https://github.com/MostafaAshry513/adb-otg-termux.git
   cd adb-otg-termux
   ```

2. Point Gradle at your Android SDK (one of the two):

   ```
   echo "sdk.dir=/path/to/android/sdk" > local.properties
   # or: export ANDROID_HOME=/path/to/android/sdk
   ```

3. Make the wrapper executable. The executable bit can be lost when the
   repository is uploaded or unzipped:

   ```
   chmod +x gradlew
   ```

4. Run the unit tests and build the debug APK:

   ```
   ./gradlew :cli:testDebugUnitTest :cli:assembleDebug
   ```

   The APK lands in `cli/build/outputs/apk/debug/cli-debug.apk`.

## Tests

`./gradlew :cli:testDebugUnitTest` runs the unit tests on the JVM, no phone or
USB hardware needed. The tests stand up a fake adb target over a local socket
and push real ADB traffic through the relay, using the vendored AdbLib classes
in `adblib/` to build packets.

All 13 tests pass at the tagged release. One timing-sensitive test,
`garbageBytesCloseSessionWithHexEvidence`, failed once on a slow single-core
build machine and passed on re-run; if it fails for you, re-run before
suspecting your changes.

## Installing the APK

- Over adb: `adb install cli/build/outputs/apk/debug/cli-debug.apk`
- On-device: copy the APK to the host phone and open it (allow installs from
  your file manager when asked).

A prebuilt, hardware-tested APK is attached to the
[Releases](https://github.com/MostafaAshry513/adb-otg-termux/releases) page,
with its SHA-256 in the release notes.

## Project layout

- `cli/` - the Android app (foreground service, USB relay, in-app log).
- `adblib/` - two files from AdbLib (BSD 3-Clause), used by the unit tests to
  speak ADB. See `THIRD_PARTY_NOTICES.md`.
- `termux/` - the Termux client script, shipped inside the app as an asset.

## Honest notes

This project was developed with an AI assistant doing most of the code writing,
directed and hardware-tested by the author. Read the code before trusting it
with a device you care about, and expect untested behavior on device pairs
other than the author's.
