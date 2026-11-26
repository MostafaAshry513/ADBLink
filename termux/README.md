# Termux side

Nothing from this repo needs to be installed in Termux except the real `adb`:

    pkg install android-tools
    adb kill-server
    adb connect 127.0.0.1:6000
    adb devices

See the main README for the full setup.
