# AndroidKlipper roadmap

This roadmap tracks real printer-host capability, not UI polish for its own sake.

## M0 — feasibility

- [x] prove Android can USB-host Klipper MCU hardware
- [x] prove Python/Klipper dependencies can live inside the APK
- [x] prove local Mainsail is practical

## M1 — native Android transport

- [x] enumerate supported USB devices
- [x] chained Android USB permission flow
- [x] native PTY bridge
- [x] stable USB identity -> dynamic PTY mapping
- [x] raw native-Klipper bulk read/write path
- [x] bounded write retry window with useful diagnostics
- [x] robust session teardown / USB reopen settle
- [x] no DTR/RTS reset surprises
- [x] project/static invariants in CI

Still needed:

- [ ] automatic USB detach/reconnect recovery
- [ ] repeated long-print + post-print idle soak testing
- [ ] decide whether per-packet allocation in the bulk writer is worth optimizing after a stable print baseline exists

## M2 — real Klipper host

- [x] embedded upstream-style Klipper runtime
- [x] packaged c_helper/native pieces
- [x] multi-MCU real config startup
- [x] runtime-only config sanitizing
- [x] persistent Klippy API socket
- [x] normal Klipper restart loop
- [x] successful real homing
- [x] successful Quad Gantry Level with Eddy

Current limitation:

- [x] complete a real long print on the G2 (v252 Benchy, 77:34 print time)
- [ ] repeat long prints and survive extended post-print idle sessions
- [ ] characterize intermittent retransmit bursts without weakening Klipper safety checks

## M3 — Moonraker + Mainsail appliance

- [x] embedded Moonraker
- [x] bundled local Mainsail
- [x] foreground connected-device service
- [x] auto-start real host from printer USB attach
- [x] optional auto-open Mainsail kiosk
- [x] removable-storage G-code directory
- [x] file-upload compatibility plumbing
- [x] LAN-accessible Moonraker and Mainsail
- [x] partial CPU wake lock
- [x] keep Mainsail display logically awake on devices that suspend USB with screen-off

Still needed:

- [ ] show/copy/share the current LAN Mainsail address in the app
- [ ] optional very-dim/black kiosk mode without allowing real display sleep
- [ ] graceful host recovery UI after USB loss
- [ ] reboot/autostart behavior suitable for a dedicated appliance

## M4 — make it standalone and portable

Today, the working development fixture still expects Dalton's exact three MCU IDs and imports the printer config from a configured Moonraker source.

- [ ] import and retain an untouched raw local config copy
- [ ] re-sanitize that raw copy against the PTYs created on each boot
- [ ] boot with no old Pi/NUC/Moonraker source present
- [ ] saved printer profile with expected MCU IDs/count
- [ ] one-time mapping flow for devices without unique USB serials
- [ ] remove magic "3 MCU" assumptions from generic startup UI
- [ ] separate generic project invariants from the current Voron development fixture

## M5 — device portability

- [x] Retroid Pocket G2: real host, homing, QGL
- [x] Retroid Pocket G2: successful 77+ minute real print on v252
- [ ] Retroid Pocket G2: extended post-print idle soak + repeated long print
- [ ] Fire HD 8 retest on current transport
- [ ] ROG Ally / alternate Android hardware test
- [ ] cheap mainstream phone test, preferably Moto G-class hardware
- [ ] document known-good hubs, OTG adapters, and powered cables
- [ ] charging/power-loss diagnostics
- [ ] low-battery warning before printing

## M6 - Moonraker state + metadata

- [ ] validate staged in-process Android G-code metadata extraction fix on-device
- [ ] verify layer count / ETA / thumbnails-SVG return with the metadata fix
- [ ] make Moonraker database/history portable with the device/printer profile
- [ ] add explicit database backup/export + restore path
- [x] add repeatable runtime checkpoint capture script

## M7 - cameras

Start this after the first convincing print/stability result.

- [ ] built-in Android camera live stream
- [ ] USB UVC webcam live stream
- [ ] register camera cleanly with Moonraker/Mainsail
- [ ] verify remote Mainsail + camera from another LAN device
- [ ] timelapse

Remote Mainsail itself already works; camera support is the missing piece.

## M8 - polish without turning it into nonsense

- [ ] simple first-run printer setup
- [ ] readable health page: host, MCUs, power, storage, network
- [ ] exportable diagnostics bundle
- [ ] safe update path
- [ ] optional tested hardware kit for power/OTG/cabling

Keep the local host free/open. Hardware earns its keep by being tested and convenient, not by locking the printer to proprietary electronics.
