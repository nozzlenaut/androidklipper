# AndroidKlipper

AndroidKlipper is an experiment in turning an ordinary Android device into a real Klipper host.

The goal is not "Mainsail in a WebView." The goal is to replace the Raspberry Pi-shaped part of a printer setup with hardware people already own or can buy cheaply: an old tablet, handheld, prepaid phone, or similar Android device.

## What works now

The current development build runs the real stack on Android:

- direct USB communication with native Klipper MCUs
- one Android PTY per MCU so upstream Klipper can use normal serial-style paths
- embedded Klipper
- embedded Moonraker
- bundled Mainsail
- local G-code storage, including removable storage when available
- automatic USB permission/startup flow
- optional auto-start of the real host and Mainsail kiosk
- LAN access to Mainsail and Moonraker
- foreground-service wake lock
- Mainsail kiosk keeps the display awake on devices that suspend USB when the screen sleeps

The USB transport deliberately lives at the Android edge. Klipper itself should stay as close to upstream as possible.

## Current hardware checkpoint — 2026-09-25

Primary test device: Retroid Pocket G2, Android 15.

Printer: Voron 2.4 350 with three native Klipper USB MCUs:

- STM32F446 mainboard
- RP2040 NHK/toolhead board
- RP2040 Eddy/probe board

Build v249 successfully completed three full homes and a Quad Gantry Level with zero retransmitted or invalid bytes during the active test. Mainsail also stayed available past the device's normal one-minute screen timeout because the kiosk now keeps the display awake.

There is still an unresolved idle USB-host failure: roughly eleven minutes into that same session, all three MCU links fell behind and Klipper shut down. The active homing/QGL workload was clean; the later failure looked like a shared Android/USB-host or hub/power-path interruption rather than one MCU failing under motion load.

So: **real printer control works, but long-duration stability is not yet print-proven.**

See `docs/CHECKPOINT.md` for the detailed checkpoint and `docs/COMPATIBILITY.md` for device-portability notes.

## Architecture in one paragraph

Android owns each physical USB device. AndroidKlipper forwards the raw Klipper byte stream through a native PTY. Embedded Klipper opens that PTY, Moonraker connects to Klipper's normal Unix socket, and Mainsail talks to Moonraker. The dynamic PTY number can change on every boot; the USB MCU's stable serial ID is what matters.

## Important current limitations

- The working printer profile still expects Dalton's three known MCU serial IDs. That is a safety rail, not the final generic design.
- Real-host startup still imports the source printer config from a configured Moonraker URL. A standalone cached-config boot path is still needed before this can honestly replace the old host with nothing else running.
- Screen-off can suspend USB host traffic on some Android devices. The G2 currently works around that by keeping Mainsail's display technically awake.
- USB detach/reconnect recovery is not automatic yet.
- OTG + charging behavior varies wildly by device and hub/cable.
- A successful real print and longer soak test are still pending.

## Next milestones

1. Reproduce and isolate the remaining idle USB-host dropout.
2. Complete a real print without USB loss.
3. Test the same printer on the G2, Fire HD 8, ROG Ally/Android test environment, and a cheap mainstream phone such as a Moto G.
4. Cache the raw printer config locally so AndroidKlipper can boot without the old Moonraker source.
5. Replace the hard-coded three-MCU fixture with a saved printer profile.
6. Add clean USB detach/reconnect recovery and better power diagnostics.
7. Add camera support: built-in Android camera, USB webcam, and Mainsail/Moonraker viewing from another device.

## Project rules

- Do not silently modify the user's source printer config.
- Prefer small Android compatibility patches over a permanent Klipper/Moonraker fork.
- Stable USB identity matters more than `/dev/pts/N` numbering.
- Make failures loud and diagnosable instead of hiding them.
- Keep the host useful without subscriptions or proprietary lock-in.

The code tour in `docs/CODE_TOUR.md` is the easiest place to start if you're trying to understand the repo.