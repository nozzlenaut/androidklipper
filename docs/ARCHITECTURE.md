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

## Baud-rate split

For the first USB-only milestone, the Android USB side and PTY side are intentionally decoupled:

- physical Klipper USB serial: 250000
- PTY presented to pySerial/Klipper: 115200

The runtime config copy can inject `baud: 115200` for bridged serial MCU sections. Once Klipper identifies the MCU, its protocol dictionary supplies the wire frequency used for protocol timing. Before supporting USB-to-UART adapters generally, baud/control-line propagation needs a real implementation.

## Klipper native helper

Upstream Klipper normally builds `c_helper.so` with GCC at runtime. AndroidKlipper must not ship a compiler. CI builds the same C sources with the Android NDK and packages `libklipper_c_helper.so` in the APK. A tiny patch makes Klipper honor `ANDROID_KLIPPER_CHELPER` when set.

## No destructive config edits

Imported config files are immutable source material. AndroidKlipper creates a runtime copy which changes only transport paths/baud settings required by the Android host.

## Later layers

Moonraker and Mainsail are intentionally deferred until Klippy itself runs reliably for long prints. Their addition should not alter the USB/PTTY layer.
