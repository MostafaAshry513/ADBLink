# Third-party notices

## adblib (Cameron Gutman, BSD 3-Clause)

`adblib/` contains `AdbMessage.java` and `AdbProtocol.java`, taken from AdbLib by Cameron Gutman
(https://github.com/cgutman/AdbLib), via the ADB-OTG project below. The license text is in
`adblib/LICENSE`. Change from the copy this project started from: the
`parseAdbMessage(AdbChannel)` method was removed from `AdbMessage` because it depended on a channel
class from another fork whose license is unclear. Nothing in this repository uses that method.
The library is only used by the unit tests (to build ADB packets for the fake target).

## ADB-OTG by Khun Htetz Naing (origin and inspiration)

This project grew out of studying and testing against
[KhunHtetzNaing/ADB-OTG](https://github.com/KhunHtetzNaing/ADB-OTG) (Android app that runs adb
commands over USB OTG). That repository has no license file, so none of its code is copied into this
repository. The package name `com.htetznaing.adbotg` is kept for continuity. If the original author
prefers a different name, it is a simple rename.
