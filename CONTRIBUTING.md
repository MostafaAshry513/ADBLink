# Contributing

Bug reports and pull requests are welcome.

## Reporting a bug

Open an issue and include:

- Host phone model and Android version; target phone model and Android version.
- The OTG adapter or cable used.
- What you ran in Termux and what happened, step by step.
- The relevant lines from the in-app log (USB detach, drop reasons, reconnects).

The app relays whatever Termux `adb` sends, so "command X fails" reports are
much more useful with the exact command and the log lines around the failure.

## Pull requests

- Keep changes small and explain the "why" in the PR description.
- Run `./gradlew :cli:testDebugUnitTest` before submitting (see BUILDING.md).
  One timing-sensitive test can flake on slow machines; re-run it once before
  digging.
- If you can, test on real hardware: this app exists to move bytes over a real
  USB cable, and an emulator cannot stand in for that.
- Do not change the files under `adblib/`; they are vendored from AdbLib under
  the BSD 3-Clause license (see THIRD_PARTY_NOTICES.md).

## A note on how this project is built

Most of the code was written by an AI assistant under the author's direction,
with the author installing and testing on real phones. Contributions from
humans and from AI-assisted workflows are both fine; what matters is that the
submitter understands the change and has tested it as far as their hardware
allows.

Everything you contribute is under the repository's MIT license.
