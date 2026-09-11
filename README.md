# AndroidKlipper

AndroidKlipper is an experimental open-source Klipper host for Android. The goal is simple: turn an old phone or tablet into the computer that runs a Klipper printer.

## Target experience

1. Install APK.
2. Plug the Android device into the printer over USB OTG.
3. Grant USB permission.
4. Import an existing Klipper config or choose a known printer profile.
5. Print.

No root, Termux, Linux Deploy, or Raspberry Pi required.

## Current milestone

The first milestone intentionally does **not** move motors or heat anything. It validates the host plumbing:

- enumerate multiple USB serial MCUs
- identify devices by USB serial number when available
- create one PTY per MCU
- bridge Android USB serial <-> PTY
- verify embedded Python/pySerial can exclusively open each PTY
- prepare a runtime-only Klipper config rewrite without changing the user's original config

A Fire HD 8 proof-of-concept has already demonstrated Android -> USB serial bridge -> Klipper -> real MCU communication using Octo4a. This repo replaces that scaffolding with a purpose-built app.

## Architecture

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

For the current native transport milestone, see [docs/FIRE_TEST.md](docs/FIRE_TEST.md) before installing the debug APK on a printer host.

## Build

The CI build vendors a pinned Klipper snapshot before compiling:

```bash
bash scripts/vendor-klipper.sh
gradle :app:assembleDebug
```

Android Studio can be used after running the vendor script once.

## Safety

Early builds are host-transport tests only. Do not use them to operate heaters or motion until the full multi-MCU runtime path and watchdog behavior have been validated.

## License

Project code is intended to be released under GPL-3.0-or-later. Bundled/upstream components retain their own licenses.
