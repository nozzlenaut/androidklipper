# AndroidKlipper code tour

This is the "where the hell do I look?" guide.

You do **not** need to understand every file in this repo to follow the project. Most of the interesting AndroidKlipper-specific work lives in a pretty small set of places.

## Start here

### `MainActivity.kt`
The normal Android screen and controls. If a button, status display, startup option, or user-facing setting behaves strangely, start here.

### `KlipperHostService.kt`
The traffic cop.

This foreground service starts the host, finds printer MCUs, creates USB sessions and PTYs, starts Klipper, waits for Klipper's API socket, starts Moonraker, and publishes status back to the UI.

If the whole host sequence starts correctly and then falls apart somewhere in the middle, this is usually the first useful file to read.

## USB: where most of the Fire-tablet nonsense lives

### `usb/UsbDeviceScanner.kt`
Answers: **What USB devices can Android see, and which ones look usable?**

It knows the normal native-Klipper VID/PID and falls back to the general usb-serial-for-android device scanner for other serial hardware.

### `usb/UsbPermissionReceiver.kt`
Handles Android's USB permission dance.

If the tablet can physically see a board but the app is stuck asking for permission or never starts talking to it, look here and in `KlipperHostService.kt`.

### `usb/UsbSerialSession.kt`
This is one of the important ugly files.

Each instance owns one real USB serial connection and moves bytes between that MCU and its PTY. It also contains several Fire OS workarounds discovered during real-printer testing.

If you see errors such as:

- USB bulk write failed
- Queueing USB request failed
- a board disappears after reconnecting
- communication works until homing/load increases

start here.

## PTY bridge: the translator

### `pty/PtyBridge.kt`
Small Kotlin wrapper around the native PTY helper.

### `cpp/pty_bridge.cpp`
The actual native implementation.

A PTY is the adapter that lets Android's USB connection look like a Linux-style serial path to Klipper. Android owns one side; Klipper opens the other.

If Android can talk to the MCU but Klipper cannot open or use `/dev/pts/...`, this pair is where to look.

## Python / Klipper runtime

### `python/hostprobe.py`
Diagnostic toolbox.

It proves individual pieces work before committing to the full printer runtime: importing Klipper, loading the native helper, opening PTYs, identifying MCUs, and running smoke tests.

If we are trying to answer "which layer is broken?", this is useful.

### `python/persistent_host.py`
Runs the real persistent Klipper instance with the imported printer configuration.

It maps the known MCU IDs to their PTYs, imports/sanitizes the printer config, creates Klipper's API socket, handles Klipper restart requests, and keeps status available to Android.

If all MCUs identify successfully but the real printer config will not reach `ready`, look here, the generated config, and `klippy.log`.

### `python/moonraker_runner.py`
Starts and manages Moonraker after Klipper's API socket exists.

If Klipper is healthy but Mainsail says it cannot reach Moonraker, this is one of the first places to check.

## Config handling

### `config/ConfigRewriter.kt`
Handles the small config changes needed to make a normal Klipper config usable by the Android host without trashing the original file.

The Python runtime also sanitizes imported config during persistent-host startup.

Rule of thumb: **never "fix" AndroidKlipper by silently rewriting the user's source printer config.** Runtime copies exist for a reason.

## Mainsail

### `MainsailServer.kt`
Serves the bundled Mainsail files locally on the Android device.

### `MainsailActivity.kt`
Displays that local Mainsail UI in the app.

### `app/src/main/assets/mainsail/`
Vendored/generated Mainsail files.

Do not hand-edit the giant generated JavaScript files unless there is an extremely specific reason. Those are upstream build output, not where AndroidKlipper logic belongs.

## Build and vendor scripts

### `scripts/vendor-klipper.sh`
Pulls the pinned Klipper source used by the Android build.

### `scripts/patch-klipper.py`
Applies the small AndroidKlipper-specific changes needed by upstream Klipper.

### `scripts/vendor-moonraker.sh` / `scripts/patch-moonraker.py`
Same basic idea for Moonraker.

### `scripts/check-project.py`
Repository sanity checks.

If a future change requires a giant permanent fork of Klipper or Moonraker, stop and ask whether the Android-specific behavior can stay at the edge instead. That is one of the main design goals of this project.

## When something breaks

A rough debugging order:

1. **Android does not see a board** → cable/OTG/power, then `UsbDeviceScanner`.
2. **Android sees it but cannot open it** → USB permission + `UsbPermissionReceiver`.
3. **USB errors or disconnects** → `UsbSerialSession`.
4. **PTY cannot be opened / bytes do not cross** → `PtyBridge.kt` + `pty_bridge.cpp`.
5. **MCU will not identify** → `hostprobe.py`, USB session, and Klipper log output.
6. **MCUs identify but Klipper never becomes ready** → `persistent_host.py`, runtime config, `klippy.log`.
7. **Klipper is ready but web UI is broken** → Moonraker runner, Mainsail server/activity.
8. **Printer moves but homing fails** → first prove whether it is a normal printer/config problem or a USB timing/transport problem before changing anything.

That last one is more or less where the Fire HD 8 test machine is living right now. Fun!
