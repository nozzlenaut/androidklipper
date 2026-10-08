# AndroidKlipper roadmap

This roadmap separates the finished v1 host core from optional follow-on work.

## V1 core — complete

- [x] Android USB host transport
- [x] chained USB permission flow
- [x] native PTY bridge
- [x] stable USB identity -> PTY mapping
- [x] multi-MCU printer startup
- [x] embedded Klipper
- [x] embedded Moonraker
- [x] bundled Mainsail
- [x] persistent config/G-code/database/backups
- [x] generic clean-device onboarding
- [x] upload normal Klipper config through Mainsail
- [x] wait for direct/nested includes before Klippy start
- [x] boot without the old Pi/NUC/Moonraker source
- [x] generic MCU count instead of a hard-coded three-MCU UI
- [x] LAN-accessible Mainsail/Moonraker
- [x] show/copy remote Mainsail address in the Android app
- [x] Android device name exposed as the Klipper host name
- [x] wake-lock / kiosk handling
- [x] battery telemetry
- [x] diagnostic flight recorder
- [x] two sustained G2 prints plus long idle stability testing
- [x] clean Moto onboarding through normal config, READY, and homing
- [x] document simultaneous charging + USB-host requirement

Frozen clean-device baseline: `generic-onboarding-stable-2026-10-03`.

## October 8 hardening candidate

- [x] bring lifecycle isolation and foreground-service fixes forward from PR #8
- [x] pass kiosk wake setting explicitly across the process boundary
- [x] non-blocking, bounded battery polling with stale-reading expiry
- [x] automated pinned-parser layer/time/thumbnail and recovery tests
- [ ] device acceptance on the candidate APK (see HARDENING_2026-10-08.md)
- [ ] promote candidate to stable only after device evidence is recorded

## Core reliability follow-ups

These are worthwhile hardening items, but they do not block the v1 host concept:

- [ ] fully automatic arbitrary physical USB detach/reconnect recovery
- [ ] one-time safe mapping flow for duplicate/non-unique USB serial identities
- [ ] additional long prints on more Android hardware
- [ ] characterize residual MCU retransmit bursts without weakening Klipper safety checks

## Metadata

- [x] in-process Android Moonraker metadata extraction patch
- [ ] explicit on-device validation of layer count
- [ ] explicit on-device validation of ETA
- [ ] explicit on-device validation of thumbnails/SVG
- [x] persistent Moonraker database directory
- [ ] explicit database backup/export + restore workflow

## Power/device compatibility

- [x] Retroid Pocket G2: printer USB + simultaneous charging
- [x] Fire HD 8: printer USB validated; simultaneous charging not proven with tested hardware
- [x] Moto G (2024), XT2413V: printer USB/onboarding validated; simultaneous charging not supported with tested hardware
- [x] document that powered OTG behavior is device/hub-specific
- [ ] optional low-battery warning before starting a print
- [ ] expand the tested compatibility matrix as devices are actually tried

## UI / diagnostics

- [x] simple first-run setup walkthrough
- [x] separate normal controls from advanced diagnostics
- [x] show Android host device name
- [x] show/copy remote Mainsail LAN address
- [x] explicit long-print power requirement in the app
- [x] copyable diagnostic report
- [ ] richer health page only if real users need it
- [ ] safe end-user update UX beyond CI/debug APK delivery

## Cameras — future, separate branch

Camera support is not part of the v1 core and should not modify the proven USB/timing path.

- [ ] built-in Android camera
- [ ] USB UVC camera
- [ ] Mainsail/Moonraker registration
- [ ] remote live view
- [ ] timelapse

## Other future work

- [ ] native USB-to-CAN support, if practical
- [ ] more usb-serial driver validation beyond native Klipper CDC
- [ ] optional hardware recommendations based only on tested combinations

Keep the core local, open, and boring. New features should earn their complexity.
