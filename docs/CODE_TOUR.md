# AndroidKlipper code tour

This is the "where the hell do I look?" guide.

You do **not** need to understand every file in this repo to follow the project. Most AndroidKlipper-specific work lives in a small set of places.

## Start here

### `MainActivity.kt`
The normal Android screen and controls. If a button, status display, startup option, or user-facing setting behaves strangely, start here.

A few strings and startup checks still reflect the current three-MCU development printer. That is intentional bring-up scaffolding, not the final generic-printer UX.

### `KlipperHostService.kt`
The traffic cop.

This foreground service starts the host, finds printer MCUs, creates USB sessions and PTYs, starts Klipper, waits for Klipper's API socket, starts Moonraker/Mainsail, manages the wake lock, prepares G-code storage, and publishes status back to the UI.

If the whole host sequence starts correctly and then falls apart somewhere in the middle, this is usually the first useful file to read.

The current config-source URL is still a development bootstrap. A future standalone path should cache the untouched raw config locally and re-sanitize it against each boot's PTYs.

## USB — where most Android-specific pain lives

### `usb/UsbDeviceScanner.kt`
Answers: **What USB devices can Android see, and which ones look usable?**

It knows the native-Klipper VID/PID and falls back to usb-serial-for-android for other serial hardware.

### `usb/UsbPermissionReceiver.kt`
Handles Android's USB permission dance.

Fire OS can serialize permission dialogs, so requests are chained one device at a time before the host starts.

### `usb/UsbSerialSession.kt`
One of the intentionally ugly-but-contained files.

Each instance owns one real USB connection and moves bytes between that MCU and its PTY. Native Klipper USB uses raw Android bulk transfers because the generic serial writer hid too much failure detail on the test devices.

Important behavior is commented in the file: line-coding failures, no DTR/RTS, ambiguous Android read `-1`, the bounded write retry window, and teardown ordering.

If you see:

- USB bulk-write retries exhausted
- a board disappears after reconnecting
- all MCU links freeze together
- communication works under motion but dies later at idle

start here **and** consider device/hub/power policy before assuming Klipper itself is broken.

## PTY bridge — the translator

### `pty/PtyBridge.kt`
Small Kotlin wrapper around the native PTY helper.

### `cpp/pty_bridge.cpp`
The native implementation.

A PTY lets Android's USB connection look like a normal byte-oriented serial path to Klipper. The native side explicitly puts the PTY in raw mode and handles the no-slave-open `POLLHUP`/`EIO` cases so startup does not hot-loop.

If Android can talk to an MCU but Klipper cannot open or use `/dev/pts/...`, this pair is where to look.

## Python / Klipper runtime

### `python/hostprobe.py`
Diagnostic toolbox.

It proves individual pieces before committing to the full runtime: Klipper imports, c_helper loading, PTY access, MCU identify, no-pin multi-MCU smoke tests, and the older real-config smoke path.

Some exact development-printer IDs still live here as fixture/safety checks. Those should eventually move into saved printer profiles rather than becoming "generic" assumptions.

### `python/persistent_host.py`
Runs the real persistent Klipper instance.

It maps stable USB IDs to PTYs, imports/sanitizes the printer config, creates Klipper's API socket, handles normal restart requests, and keeps status available to Android.

The `_REQUIRED_IDS` set is currently a deliberate safety rail for the development Voron. Do not mistake it for the intended final architecture.

### `python/moonraker_runner.py`
Starts Moonraker after Klipper's API socket exists.

Moonraker currently listens on the LAN and trusts the local /24 selected from the active route. That is why remote Mainsail already works on a normal home network.

## Config handling

### `config/ConfigRewriter.kt`
Handles small config changes needed to make a normal Klipper config usable by the Android host without trashing the original file.

The Python persistent runtime also sanitizes imported config. Runtime copies exist for a reason: **do not "fix" AndroidKlipper by silently editing the user's source printer config.**

A standalone implementation should cache the **raw** source config, not a copy already rewritten to `/dev/pts/N`, because PTY numbers are allowed to change between boots.

## Mainsail

### `MainsailServer.kt`
Serves the bundled Mainsail files. Its generated config follows the hostname used to access the server, which allows the same bundle to work locally and over the LAN.

### `MainsailActivity.kt`
Displays local Mainsail and currently sets `FLAG_KEEP_SCREEN_ON` because the G2 power-gates USB when the display really sleeps.

Keeping the screen logically awake is intentional. A future dim/black kiosk mode should reduce panel brightness without dropping that flag.

### `app/src/main/assets/mainsail/`
Vendored/generated Mainsail files.

Do not hand-edit the giant generated JavaScript files unless there is an extremely specific reason. They are upstream build output, not where AndroidKlipper logic belongs.

## Build and vendor scripts

### `scripts/vendor-klipper.sh` + `scripts/patch-klipper.py`
Pin upstream Klipper and apply the small Android compatibility patches.

### `scripts/vendor-moonraker.sh` + `scripts/patch-moonraker.py`
Same idea for Moonraker.

### `scripts/check-project.py`
Repository sanity checks. It intentionally guards several transport invariants discovered through hardware testing.

Some checks also encode the current Voron fixture. Long-term, split generic architecture invariants from printer-specific fixture checks instead of letting those assumptions spread through the app.

## When something breaks

A useful debugging order:

1. **Android does not see a board** → cable/OTG/power, then `UsbDeviceScanner`.
2. **Android sees it but cannot open it** → USB permission + `UsbPermissionReceiver`.
3. **USB error / multiple boards freeze together** → `UsbSerialSession`, then Android USB-host state, hub, cable, and power.
4. **PTY cannot be opened / bytes do not cross** → `PtyBridge.kt` + `pty_bridge.cpp`.
5. **MCU will not identify** → `hostprobe.py`, USB session, Klipper log.
6. **MCUs identify but Klipper never becomes ready** → `persistent_host.py`, runtime config, `klippy.log`.
7. **Klipper is ready but web UI is broken** → Moonraker runner and Mainsail server/activity.
8. **Printer moves but homing/QGL fails** → separate normal printer/config failures from transport timing before changing either.
9. **Motion works, then everything dies later at idle** → compare all MCU sequence/retransmit stats; a simultaneous drop points toward the shared Android/hub/power path.

As of the 2026-09-25 checkpoint, homing and QGL are no longer the main blocker. Long-duration USB-host stability is.