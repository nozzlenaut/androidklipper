# Generic AndroidKlipper Onboarding Checkpoint — 2026-10-03

## Frozen tested code

- Tag: `generic-onboarding-stable-2026-10-03`
- Tested commit: `17007d60db6cd925696d2cffb57701fc55fd7f01`
- CI: Android build #280 / run `37150351469`
- CI result: PASS
  - static project checks
  - unit tests
  - debug APK build
  - packaged APK inspection
  - artifact upload

The annotated tag points directly at the tested code commit. Documentation after this point is intentionally not part of the tested tag.

## What this checkpoint proves

### Moto G 5G (2024) clean-device onboarding

A fresh Moto G 5G (2024) was used as a clean-room AndroidKlipper host.

Observed successful path:

1. Install AndroidKlipper APK on a device with no prior AndroidKlipper state.
2. Connect the Voron over USB OTG.
3. Grant Android USB access.
4. AndroidKlipper detects the printer USB devices and identifies Klipper MCUs.
5. Moonraker starts.
6. Mainsail opens and is usable.
7. Upload normal Klipper configuration files.
8. AndroidKlipper waits for referenced include files to exist and for the config tree to settle.
9. Klippy starts with the normal configuration.
10. Klipper reaches READY.
11. Home the Voron successfully from Mainsail.

This is the first checkpoint validated on a clean Android device other than the long-running G2 development host.

### G2 stability retained

The printer transport/timing hot path remains unchanged from the known-good G2 stability checkpoint.

Known-good timing values remain:

- native scheduled-send lead: 0.500 s
- background step generation low/high: 1.000 / 1.500 s
- motion buffer high: 2.0 s
- startup buffer: 0.750 s
- minimum scheduling time: 0.250 s
- multi-MCU trsync timeout: 0.050 s

The G2 checkpoint previously completed two sustained real prints plus long idle testing without Timer-too-close, Android USB write-retry, invalid-byte, or disconnect failures.

## Generic first-run behavior

Normal intended flow:

`install → open AndroidKlipper → connect printer USB → grant USB permissions → identify MCUs → start Moonraker → open Mainsail → upload config if needed → wait for includes → start Klippy → READY → print`

Important startup hardening in this checkpoint:

- USB auto-start defaults ON.
- Short USB enumeration settle delay for multi-MCU hubs.
- USB permissions are requested serially.
- Permission denial/disconnect reports a visible error instead of silently hanging.
- Duplicate-start guard remains active until Moonraker is actually ready.
- Stale startup locks are recovered after an interrupted setup.
- Moonraker/Klippy readiness is latched service state, not parsed from the latest status sentence.
- Mainsail stays available through Klippy config errors.
- Config upload preflight waits for direct and nested includes.
- Empty wildcard includes follow Klipper behavior and do not block startup.
- Config tree must remain stable briefly before Klippy starts.
- AndroidKlipper does not seed fake printer-side config files such as `variables.cfg`.
- `restart_method: command` is accepted for Android PTY-backed MCU configs.
- Mainsail embedded caching/service-worker behavior is hardened against stale frontend chunks after APK updates.
- Debug APK update no longer silently starts the real host and bypasses onboarding.
- Android notification permission is not stacked onto first-run USB prompts.
- Android backup restore is disabled so a new phone cannot unexpectedly inherit another host's private app state.

## Known limitations / compatibility notes

### Moto G 5G (2024) charging + OTG

The Moto G 5G (2024) successfully passes printer data over OTG.

With the powered hubs/cables tested so far, it would not simultaneously charge while remaining the USB host. The same accessories did allow simultaneous host/power behavior on the G2.

Treat simultaneous USB-host + charging support as device/hub specific. This is not an AndroidKlipper transport failure.

### USB attach auto-launch scope

The Android manifest currently targets native Klipper USB attach behavior for `1d50:614e`.

The runtime serial layer also supports common usb-serial-for-Android drivers, but CH340/CP210x/FTDI-style hardware may require opening AndroidKlipper manually before setup. Do not broaden the manifest to claim arbitrary USB serial devices without targeted testing.

### Cosmetic/UI

- AndroidKlipper passes the Android device's user-visible name to Klipper as the host name.
- Config-defined object labels remain user-owned. A legacy section such as `[temperature_sensor NUC Temp]` must be renamed in the Klipper config if a different Mainsail label is desired; AndroidKlipper intentionally does not silently rewrite it.
- The v1 polish branch adds a clearer setup screen, explicit power guidance, and a copyable remote Mainsail address without changing the proven USB/timing path.

### Camera

Native Android camera support is not part of this checkpoint.

The next camera work should branch from this checkpoint and use Android-native camera APIs (CameraX is the preferred direction), with the Moto G 5G (2024) as the first clean test device.

## Direction from this checkpoint

Do not fork the AndroidKlipper core per Android device.

Preferred structure:

1. Keep one generic AndroidKlipper host core.
2. Maintain a compatibility matrix for devices, hubs, OTG charging behavior, cameras, and Android-version quirks.
3. Add device-specific workarounds only when they are isolated and do not change the generic transport/timing path.
4. Develop larger features, especially camera support, on separate branches from this checkpoint.
5. Require a clean-device test before promoting major onboarding changes into the generic baseline.

## Next likely branch

Suggested next branch:

`android-camera-v1`

Base it on:

`generic-onboarding-stable-2026-10-03`

First target:

- Moto G 5G (2024) built-in camera
- local Mainsail-accessible stream or snapshot endpoint
- camera failures must not affect Klipper/Moonraker/USB host lifecycle
- no changes to the proven printer timing/USB hot path unless independently justified
