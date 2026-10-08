# AndroidKlipper

AndroidKlipper turns an ordinary Android device into a real Klipper host. It runs Klipper, Moonraker, and Mainsail locally and talks to printer MCUs directly over Android USB host/OTG.

The core project is intentionally small: use cheap Android hardware in place of the Raspberry Pi-shaped part of a Klipper setup without forking Klipper into a proprietary appliance.

## Hardening candidate — October 8, 2026

The `hardening-v1-2026-10-08` branch contains lifecycle and battery hardening plus
metadata regression tests. Its APK is a **prerelease candidate**, not a new
hardware-proven baseline. See [the checkpoint and acceptance checklist](docs/HARDENING_2026-10-08.md).
The previous main (`15f180c`) and the generic onboarding tag remain rollback points.

## Building

Run `scripts/vendor-klipper.sh`, `scripts/vendor-moonraker.sh`, and
`scripts/vendor-mainsail.sh` before the project checks or Android build. Mainsail
is built from its pinned source with the readable Android patches; generated web
assets are build output and are no longer maintained as a second source of truth.
CI runs the battery/metadata regression tests, project checks, Android unit tests,
APK build, and package inspection. Android hardware testing is a separate gate.

## Release baseline

The clean-device onboarding foundation is `generic-onboarding-stable-2026-10-03`. Current `main` builds on that checkpoint with the v1 UI cleanup, remote Mainsail address display/copy, charging compatibility guidance, and Android battery telemetry without changing the proven USB/Klipper timing path.

The generic onboarding checkpoint was tested from a clean Android install through:

`install → connect printer USB → grant permissions → identify MCUs → start Moonraker → open Mainsail → upload config → wait for includes → start Klippy → READY → print`

A clean Moto G (2024) test reached Mainsail, accepted the normal Voron config tree, reached READY, and successfully homed the printer. The proven G2 transport/timing path was preserved.

## What works

- generic USB discovery for supported Klipper/serial devices
- multiple printer MCUs behind a USB hub
- one Android PTY per MCU with stable USB identity mapping
- embedded Klipper
- embedded Moonraker
- bundled local Mainsail
- persistent `AndroidKlipperDrive` storage for config, G-code, database, and backups
- first-run config upload through Mainsail
- waits for direct and nested config includes before starting Klippy
- automatic Klippy start when the uploaded config tree is complete
- USB auto-start and chained Android USB permission handling
- local kiosk Mainsail plus LAN-accessible Mainsail/Moonraker
- Android device name passed into Klipper as the host name
- foreground-service wake lock and keep-screen-awake handling
- battery telemetry
- persistent diagnostics / flight recorder

The G2 stability checkpoint completed two sustained real prints plus long idle testing without Timer-too-close, Android USB write-retry, invalid-byte, or disconnect failures.

## Device power requirement

USB host/OTG support is required to talk to the printer.

For long or unattended prints, the Android device must also support **charging while remaining in USB host mode** through the selected hub/adapter. USB-C, OTG support, and a powered hub do not guarantee this behavior.

| Tested device | Printer USB | Charge while hosting printer USB | Current use |
| --- | --- | --- | --- |
| Retroid Pocket G2 | Yes | **Yes** | Best-tested long-running host |
| Fire HD 8 (KFRAPWI) | Yes | **No with tested hardware** | Battery-powered prints |
| Moto G (2024), XT2413V | Yes | **No with tested hardware** | Battery-powered prints |

A device which cannot charge while hosting USB can still run AndroidKlipper for prints which fit comfortably inside its battery runtime.

See `docs/COMPATIBILITY.md` for details.

## First run

1. Install and open AndroidKlipper.
2. Connect the printer over USB OTG.
3. Grant each Android USB permission prompt.
4. AndroidKlipper starts Moonraker and makes Mainsail available.
5. If `printer.cfg` is missing, upload your normal Klipper config files in Mainsail.
6. AndroidKlipper waits for referenced includes and a stable config tree.
7. Klippy starts automatically and reaches READY.
8. Upload G-code and print.

The app shows the remote Mainsail LAN address once Moonraker is ready.

## Host names and temperature sensor labels

AndroidKlipper passes the Android device's user-visible name (falling back to manufacturer/model) to Klipper as the host name.

Klipper temperature-sensor section names still come from the user's config and are intentionally not silently rewritten. If a migrated config contains a legacy name such as `[temperature_sensor NUC Temp]`, rename that section in the config to the label you want Mainsail to display, for example `[temperature_sensor Android Host Temp]` or the actual device name.

## Known limitations

- USB detach/reconnect recovery is not yet fully automatic.
- Some Android devices suspend USB when the display sleeps; kiosk mode keeps the display logically awake on affected hardware.
- CH34x, CP210x, FTDI, and Prolific driver detection exists but native Klipper CDC USB remains the print-proven path.
- The Android Moonraker metadata patch is present, but layer/ETA/thumbnail behavior still needs an explicit on-device validation checkpoint before it should be called proven.
- Devices with duplicate/non-unique MCU USB serial identities still need a safe one-time mapping flow.
- Camera support is a separate future feature and is not part of the v1 core.

## Architecture

Android owns each physical USB device. AndroidKlipper bridges each MCU byte stream into an Android PTY. Klipper opens those PTYs through a stable USB-identity map, Moonraker connects to Klipper's normal Unix socket, and Mainsail talks to Moonraker.

The printer configuration stays user-owned. AndroidKlipper adapts the transport rather than silently rewriting printer behavior.

## Project rules

- Do not silently modify the user's source printer config.
- Prefer small Android compatibility patches over a permanent Klipper/Moonraker fork.
- Stable USB identity matters more than PTY numbering.
- Make failures loud and diagnosable instead of hiding them.
- Do not weaken Klipper timing/safety thresholds to mask transport failures.
- Keep the host useful without subscriptions or proprietary lock-in.

See `docs/GENERIC_ONBOARDING_CHECKPOINT_2026-10-03.md` for the frozen clean-device test and `docs/CODE_TOUR.md` for the implementation tour.
