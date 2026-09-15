# Architecture

## Decision

The mainline design is **native Android + embedded Python**, not a user-visible Linux/proot installation.

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
        +-- embedded Python 3.11
                +-- Klipper (Klippy)
                +-- pySerial / greenlet / cffi / Jinja2
                +-- prebuilt Klipper c_helper.so
```

## Why PTYs

Klipper expects normal POSIX serial paths and passes the file descriptor into its native serial queue. Rewriting Klipper around Android's Java USB APIs would create a large permanent fork. A PTY keeps Android-specific code at the edge and leaves almost all Klipper code untouched.

Each USB MCU gets its own PTY. This fixes the single-device limitation of the Octo4a bridge used for the feasibility test.

## Device identity

VID/PID is not sufficient. Multiple Klipper MCUs commonly advertise the same VID/PID. AndroidKlipper reads the USB serial descriptor after permission is granted and uses that as the primary stable identifier. If a device exposes no serial descriptor, the app must fall back to a user-confirmed mapping rather than silently guessing.

## USB / PTY transport

For the USB-only milestone, Android owns the physical serial device and Klipper sees a byte-transparent PTY:

- physical Klipper USB serial: opened/configured by usb-serial-for-android at 250000
- PTY: explicitly placed in raw mode; no terminal echo, canonical processing, or CR/LF translation
- Klipper: a tiny Android patch redirects `/dev/pts/*` from `connect_uart()` to `connect_pipe()`

This is deliberate. The PTY is not a second hardware UART, so Klipper must not apply pySerial exclusive locks, RTS/DTR changes, baud changes, or programmer-reset sequences to it. Runtime config copies only rewrite the serial path; they do not inject a synthetic PTY baud.

Before supporting USB-to-UART adapters generally, baud/control-line propagation needs a real implementation on the Android USB side.

## Klipper native helper

Upstream Klipper normally builds `c_helper.so` with GCC at runtime. AndroidKlipper must not ship a compiler. CI builds the same C sources with the Android NDK and packages `libklipper_c_helper.so` in the APK. A tiny patch makes Klipper honor `ANDROID_KLIPPER_CHELPER` when set.

## No destructive config edits

Imported config files are immutable source material. AndroidKlipper creates a runtime copy which changes only transport paths/baud settings required by the Android host.

## Later layers

Moonraker and Mainsail are intentionally deferred until Klippy itself runs reliably for long prints. Their addition should not alter the USB/PTTY layer.
