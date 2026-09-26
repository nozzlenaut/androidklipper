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

## Current hardware checkpoint - 2026-09-26

Primary test device: Retroid Pocket G2, Android 15.

Printer: Voron 2.4 350 with STM32F446 main MCU, RP2040 NHK/toolhead MCU, and RP2040 Eddy MCU.

Build v252 completed a full Benchy in **1:17:34 of actual print time** (1:22:15 total job time including home/QGL/scan) with zero print stalls, zero invalid MCU bytes, no `Timer too close`, no lost-MCU shutdown, and no AndroidKlipper service teardown during the print. This cleared the previous v249 Benchy failure window at about 61:55.

The successful run still had isolated USB retransmit bursts, including one larger main-MCU burst, so transport diagnostics stay enabled and repeated long runs still matter. The app now includes an Android-side flight recorder specifically so those events can be lined up with Klipper/Moonraker logs.

The next immediate test is an untouched post-print idle soak. Metadata extraction is also still broken on Android, which is why Mainsail currently shows 0/0 layers and lacks normal ETA/thumbnail data.

See `docs/CHECKPOINT.md` for the live checkpoint and `docs/TEST_RESULTS_2026-09-26_V252.md` for the full v252 run.

## Architecture in one paragraph

Android owns each physical USB device. AndroidKlipper forwards the raw Klipper byte stream through a native PTY. Embedded Klipper opens that PTY, Moonraker connects to Klipper's normal Unix socket, and Mainsail talks to Moonraker. The dynamic PTY number can change on every boot; the USB MCU's stable serial ID is what matters.

## Important current limitations

- The working printer profile still expects Dalton's three known MCU serial IDs. That is a safety rail, not the final generic design.
- Real-host startup still imports the source printer config from a configured Moonraker URL. A standalone cached-config boot path is still needed before this can honestly replace the old host with nothing else running.
- Screen-off can suspend USB host traffic on some Android devices. The G2 currently works around that by keeping Mainsail's display technically awake.
- USB detach/reconnect recovery is not automatic yet.
- OTG + charging behavior varies wildly by device and hub/cable.
- One long G2 print is proven; repeated long runs, post-print idle soak, and cross-device validation are still pending.

## Next milestones

1. Finish the untouched post-print idle soak and capture a runtime checkpoint.
2. Repair Moonraker G-code metadata extraction (layers, ETA, thumbnails/SVG).
3. Persist/export Moonraker database history with the device/printer profile.
4. Retest the same v252 transport on the Fire HD 8, then a cheap Moto G-class phone.
5. Cache the raw printer config locally and replace the hard-coded three-MCU fixture with a saved printer profile.
6. Add clean USB detach/reconnect recovery and better power diagnostics.
7. Add camera support: built-in Android camera, USB webcam, and Mainsail/Moonraker viewing from another device.

## Project rules

- Do not silently modify the user's source printer config.
- Prefer small Android compatibility patches over a permanent Klipper/Moonraker fork.
- Stable USB identity matters more than `/dev/pts/N` numbering.
- Make failures loud and diagnosable instead of hiding them.
- Keep the host useful without subscriptions or proprietary lock-in.

The code tour in `docs/CODE_TOUR.md` is the easiest place to start if you're trying to understand the repo.