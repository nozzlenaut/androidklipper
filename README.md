# AndroidKlipper

**Run a real Klipper host on an Android phone, tablet, or handheld.**

AndroidKlipper packages **Klipper + Moonraker + Mainsail** directly on Android and connects to printer MCUs through Android USB host/OTG. The goal is simple: reuse cheap or old Android hardware in place of a Raspberry Pi-class host without turning Klipper into a proprietary appliance.

> **Current status:** working prototype / hardware-tested v1.  
> The best-tested host so far is a Retroid Pocket G2. A clean Moto G (2024) install has also reached Mainsail, loaded a normal Voron configuration, reached `READY`, and successfully homed the printer.

**Repository:** https://github.com/nozzlenaut/androidklipper  
**Video:** https://youtu.be/Nz-z8JivqjY  
**YouTube:** https://www.youtube.com/@UnqualifiedRepairs

---

## Why AndroidKlipper?

There are millions of Android devices with enough CPU, RAM, Wi-Fi, storage, display, and battery to run a printer host. Many of them are sitting in drawers.

AndroidKlipper is an attempt to make that hardware useful again.

It is designed to:

- run Klipper, Moonraker, and Mainsail locally on Android
- talk directly to one or more printer MCUs over USB
- keep the printer configuration user-owned and familiar
- preserve normal Klipper timing and safety behavior
- survive long prints without Android sleep/power-management surprises
- make failures visible and diagnosable
- avoid subscriptions, cloud dependencies, and proprietary lock-in

The app adapts the **host/transport layer**. It does not silently rewrite the printer.

---

## What works today

- generic USB discovery for supported Klipper/serial devices
- multiple printer MCUs behind a USB hub
- one Android PTY per MCU with stable USB identity mapping
- embedded Klipper
- embedded Moonraker
- bundled local Mainsail
- persistent `AndroidKlipperDrive` storage for config, G-code, database, and backups
- clean-device first-run onboarding
- first-run config upload through Mainsail
- waits for direct and nested config includes before starting Klippy
- automatic Klippy start once the config tree is complete
- USB auto-start and chained Android USB permission handling
- local kiosk Mainsail
- LAN-accessible Mainsail and Moonraker
- remote Mainsail address display/copy
- Android device name exposed as the host name
- foreground-service wake lock and keep-screen-awake handling
- Android battery telemetry
- persistent diagnostics / flight recorder

The G2 stability checkpoint completed **two sustained real prints plus long idle testing** without Timer-too-close, Android USB write-retry, invalid-byte, or disconnect failures.

---

## Tested hardware

USB host/OTG support is required.

For long or unattended prints, the Android device must also support **charging while remaining in USB host mode** through the selected hub/adapter. USB-C, OTG support, and a powered hub do **not** guarantee that behavior.

| Device | Printer USB | Charge while hosting printer USB | Result |
| --- | --- | --- | --- |
| Retroid Pocket G2 | Yes | **Yes** | Best-tested long-running host |
| Fire HD 8 (KFRAPWI) | Yes | **No with tested hardware** | Completed battery-powered prints |
| Moto G (2024), XT2413V | Yes | **No with tested hardware** | Clean install → Mainsail → READY → homing |

A device that cannot charge while hosting USB can still run AndroidKlipper for prints that comfortably fit inside its battery runtime.

See [`docs/COMPATIBILITY.md`](docs/COMPATIBILITY.md) for more detail.

---

## First run

The intended clean-device path is:

```text
install
  ↓
connect printer USB
  ↓
grant Android USB permissions
  ↓
identify MCU(s)
  ↓
start Moonraker
  ↓
open Mainsail
  ↓
upload normal Klipper config
  ↓
wait for includes
  ↓
start Klippy
  ↓
READY
  ↓
print
```

In practice:

1. Install and open AndroidKlipper.
2. Connect the printer through USB OTG.
3. Grant each Android USB permission prompt.
4. AndroidKlipper starts Moonraker and exposes Mainsail.
5. If `printer.cfg` is missing, upload your normal Klipper configuration through Mainsail.
6. AndroidKlipper waits for referenced includes and a stable config tree.
7. Klippy starts automatically.
8. Once Klipper reaches `READY`, upload G-code and print.

The app shows the remote Mainsail LAN address once Moonraker is ready.

---

## Architecture

```text
Printer MCU(s)
     │ USB
     ▼
Android USB Host API
     │
     ▼
AndroidKlipper USB ↔ PTY bridge
     │
     ▼
Klipper
     │ Unix socket
     ▼
Moonraker
     │
     ├────────► Local Mainsail / kiosk
     │
     └────────► LAN Mainsail / API
```

Android owns each physical USB device. AndroidKlipper bridges each MCU byte stream into an Android PTY. Klipper opens those PTYs through a stable USB-identity map, Moonraker connects to Klipper's normal Unix socket, and Mainsail talks to Moonraker.

The printer configuration remains normal Klipper configuration.

---

## Release baseline

The clean-device onboarding foundation is:

`generic-onboarding-stable-2026-10-03`

Current `main` builds on that checkpoint with:

- v1 UI cleanup
- remote Mainsail address display/copy
- charging compatibility guidance
- Android battery telemetry

These changes do not intentionally alter the proven USB/Klipper timing path.

### Hardening candidate — October 8, 2026

The `hardening-v1-2026-10-08` branch contains lifecycle and battery hardening plus metadata regression tests.

Its APK is a **prerelease candidate**, not a new hardware-proven baseline.

See [`docs/HARDENING_2026-10-08.md`](docs/HARDENING_2026-10-08.md) for the checkpoint and acceptance checklist.

The previous `main` (`15f180c`) and the generic onboarding tag remain rollback points.

---

## Building

Before project checks or an Android build, vendor the pinned upstream components:

```bash
scripts/vendor-klipper.sh
scripts/vendor-moonraker.sh
scripts/vendor-mainsail.sh
```

Mainsail is built from its pinned source with readable Android patches. Generated web assets are build output and are not maintained as a second source of truth.

CI covers:

- battery / metadata regression tests
- project checks
- Android unit tests
- APK build
- package inspection

Physical Android/printer testing remains a separate gate.

---

## Host names and temperature sensor labels

AndroidKlipper passes the Android device's user-visible name, falling back to manufacturer/model, to Klipper as the host name.

Klipper temperature-sensor section names still come from the user's config and are intentionally not silently rewritten.

For example, if a migrated config contains:

```ini
[temperature_sensor NUC Temp]
```

rename it to whatever you actually want Mainsail to display, such as:

```ini
[temperature_sensor Android Host Temp]
```

or the Android device's real name.

---

## Known limitations

- USB detach/reconnect recovery is not yet fully automatic.
- Some Android devices suspend USB when the display sleeps; kiosk mode keeps the display logically awake on affected hardware.
- CH34x, CP210x, FTDI, and Prolific driver detection exists, but native Klipper CDC USB remains the print-proven path.
- The Android Moonraker metadata patch is present, but layer/ETA/thumbnail behavior still needs an explicit on-device validation checkpoint before it should be called proven.
- Devices with duplicate/non-unique MCU USB serial identities still need a safe one-time mapping flow.
- Camera support is intentionally a separate future feature and is not part of the v1 core.
- **Charging while hosting USB is device-specific.** This is currently the biggest hardware compatibility caveat.

---

## Project rules

- Do not silently modify the user's source printer config.
- Prefer small Android compatibility patches over a permanent Klipper/Moonraker fork.
- Stable USB identity matters more than PTY numbering.
- Make failures loud and diagnosable instead of hiding them.
- Do not weaken Klipper timing/safety thresholds to mask transport failures.
- Keep the host useful without subscriptions or proprietary lock-in.

---

## More detail

- [`docs/COMPATIBILITY.md`](docs/COMPATIBILITY.md) — tested device and USB/power behavior
- [`docs/GENERIC_ONBOARDING_CHECKPOINT_2026-10-03.md`](docs/GENERIC_ONBOARDING_CHECKPOINT_2026-10-03.md) — frozen clean-device test
- [`docs/HARDENING_2026-10-08.md`](docs/HARDENING_2026-10-08.md) — current hardening candidate
- [`docs/CODE_TOUR.md`](docs/CODE_TOUR.md) — implementation tour

---

## Feedback and testing

Android hardware is messy, especially around USB host mode, sleep behavior, powered hubs, and simultaneous charging.

If you try AndroidKlipper on another device, the most useful feedback is:

- Android device/model
- Android version
- printer MCU(s)
- USB hub/OTG adapter
- whether printer USB remains stable
- whether the Android device can charge while hosting USB
- longest successful print / idle test
- logs from any failure

That compatibility data is more useful than a generic “works / doesn't work” report.
