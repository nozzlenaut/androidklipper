# Architecture

This is the less hand-wavy version of how AndroidKlipper works.

## The basic idea

The main design is **native Android + embedded Python**. There is no user-visible Linux/proot install hiding underneath it.

```text
Android UI / foreground service
        |
        +-- UsbManager + usb-serial-for-android
        |       +-- physical MCU #1
        |       +-- physical MCU #2
        |       +-- physical MCU #3 ...
        |
        +-- one native PTY per MCU
        |       +-- /dev/pts/N exposed to embedded Python
        |
        +-- embedded Python
                +-- Klipper (Klippy)
                +-- Moonraker
                +-- pySerial / greenlet / cffi / Jinja2
        |
        +-- local Mainsail web server
```

In normal-person terms: Android owns the real USB connection. Klipper gets a fake Linux-style serial path. The bridge in the middle copies bytes in both directions so neither side needs to pretend it is something it is not.

## Why PTYs exist

Klipper expects normal POSIX serial paths and eventually hands that connection to its native serial queue. Android's Java/Kotlin USB APIs do not look like that at all.

We could rewrite a large chunk of Klipper around Android USB, but then AndroidKlipper would become a permanent Klipper fork that is miserable to keep updated.

Instead, every USB MCU gets its own PTY. Klipper opens the PTY slave path while AndroidKlipper owns the master side and forwards bytes to and from the physical USB device.

This also removes the single-device limitation of the Octo4a bridge used during the original feasibility test.

## Device identity

VID/PID is not enough to identify a board. A printer can have several Klipper MCUs advertising the exact same VID/PID.

AndroidKlipper therefore uses the USB serial descriptor as the primary stable ID after permission is granted. On Fire OS, that serial can occasionally disappear after reopening a CDC device, so the app caches successful IDs for the current USB device path.

If a device genuinely has no usable serial descriptor, the software should require an explicit mapping instead of quietly guessing which board is which. Quietly guessing is how printers become unexpectedly exciting.

## USB / PTY transport

For native Klipper USB devices:

- Android opens and owns the physical USB device.
- The PTY is put in raw mode: no echo, line editing, or CR/LF translation.
- A small AndroidKlipper patch redirects `/dev/pts/*` from Klipper's normal UART connection path to its pipe connection path.
- AndroidKlipper forwards raw bytes between the PTY and USB bulk endpoints.

The PTY is not another hardware UART, so Klipper must not try to apply baud changes, serial exclusive locks, RTS/DTR toggles, or programmer-reset behavior to it.

Fire OS has also proven that perfectly normal CDC behavior is apparently optional. The native Klipper USB path therefore uses direct bulk endpoint reads instead of relying on some of the higher-level usb-serial-for-android read paths which were unstable in testing.

General USB-to-UART adapter support will need proper baud/control-line handling on the Android side. Native Klipper USB is the priority first.

## Klipper native helper

Upstream Klipper normally builds `c_helper.so` on the host with GCC. Shipping a compiler inside an Android app would be ridiculous.

The build instead compiles Klipper's helper with the Android NDK and packages it in the APK as `libklipper_c_helper.so`. A small patch lets Klipper use that packaged helper when `ANDROID_KLIPPER_CHELPER` is set.

## Config handling

The original printer config should stay original.

AndroidKlipper uses the persistent config uploaded through Mainsail. A connection-time serial map resolves normal USB identities to current PTYs; it does not rewrite the user config. Klipper SAVE_CONFIG writes to the persistent config.

## Klipper, Moonraker, and Mainsail

The project has moved past the original USB-only milestone. Current development builds can start persistent Klippy, expose its normal API socket, start Moonraker locally, and serve a bundled Mainsail interface from the Android device.

The recorded baseline includes sustained G2 printing and clean Moto onboarding through READY and homing. Coverage is device-specific; the October 8 hardening candidate still requires fresh device acceptance. The kiosk WebView runs in a separate process in that candidate so browser process failures have a smaller impact on the host.

The important design rule is that Moonraker and Mainsail sit above the USB/PTY transport. Fixing the UI or web API should not require redesigning the physical MCU bridge.

## What should stay boring

The weird Android-specific code should stay concentrated around USB, PTYs, startup, and config adaptation. Upstream Klipper, Moonraker, and Mainsail should remain as close to upstream as practical.

The less custom code we have to carry forever, the better.
