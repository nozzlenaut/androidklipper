# AndroidKlipper

AndroidKlipper is an experiment to answer a pretty simple question:

**Can an old Android phone or tablet replace the Raspberry Pi that normally runs Klipper?**

The goal is not to turn Android into a weird desktop Linux install. The goal is to plug the Android device directly into a printer over USB, launch the app, and have it act like the Klipper host.

## What I want the setup to feel like

1. Install the APK.
2. Plug the Android device into the printer with USB OTG.
3. Grant USB permission.
4. Load a printer config.
5. Open Mainsail.
6. Print.

No root, Termux, Linux Deploy, or separate Raspberry Pi.

## Current state

This is still experimental. The Fire HD 8 test setup has already proven a surprising amount of the idea:

- Android can see multiple printer MCUs over USB.
- Each MCU gets its own PTY so Klipper can talk to it like a normal Linux serial device.
- Klipper can run inside the Android app.
- Moonraker and a local Mainsail instance can run on the tablet.
- Real printer controls such as fans, heaters, LEDs, and XY motion have worked in testing.
- Full reliable homing is **not solved yet**. Z homing and USB stability are the current trouble spots.

So, no, I would not trust this with a 30-hour print yet.

## Why this is harder than it sounds

Klipper expects Linux-style serial devices. Android does USB very differently, especially on Fire OS. AndroidKlipper works around that by putting a small bridge between Android's USB API and Klipper.

Very roughly:

```text
Printer MCU
    ↕ USB
Android USB code
    ↕
PTY bridge
    ↕
Klipper
    ↕
Moonraker
    ↕
Mainsail
```

That bridge is the interesting part. It lets most of Klipper stay normal instead of rewriting Klipper around Android.

For the less hand-wavy version, see [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Building it

The build vendors a pinned Klipper snapshot before compiling:

```bash
bash scripts/vendor-klipper.sh
gradle :app:assembleDebug
```

Android Studio works too after the vendor script has been run once.

## A note about the code

This repo intentionally tries to explain the weird parts in plain English. If a section exists only because Fire OS did something stupid, the comment should say that instead of hiding it behind a wall of jargon.

Vendored projects such as Mainsail and third-party libraries are left alone. Comments and documentation here are focused on the AndroidKlipper-specific code.

## Safety

This project can eventually control heaters and moving machinery. Early builds should be treated like development hardware, not a finished printer controller. Test with someone physically near the machine and be ready to kill power.

## License

Project code is intended to be released under GPL-3.0-or-later. Bundled and upstream components keep their own licenses.
