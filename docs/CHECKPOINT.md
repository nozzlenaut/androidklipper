# AndroidKlipper checkpoint

Last updated: 2026-09-25

This file is the current "where are we actually at?" checkpoint. It should describe observed hardware behavior, not the version we wish we had.

## Working stack

Current test build: v249.

Current primary device: Retroid Pocket G2, Android 15.

The app currently runs:

- Android USB host transport
- one PTY bridge per printer MCU
- embedded Klipper
- embedded Moonraker
- bundled Mainsail
- removable-storage G-code directory when available
- automatic real-host startup and kiosk flow
- partial CPU wake lock while the real host is active
- `FLAG_KEEP_SCREEN_ON` while the Mainsail kiosk is open
- LAN-facing Mainsail and Moonraker

Remote Mainsail was verified from another machine on the same LAN. The missing remote-printer feature is camera streaming, not basic web access.

## Printer fixture

Voron 2.4 350 with three native Klipper USB MCUs:

- STM32F446 mainboard — `3F001E001450535556323420`
- RP2040 NHK/toolhead — `3033393834057C77`
- RP2040 Eddy/probe — `504450610844C31C`

These IDs are currently hard-coded as a deliberate bring-up safety rail. They are not meant to be the final generic-printer model.

## Successful v249 test

Session started around 18:48:29 EDT and Klippy reached ready around 18:48:32.

The user then completed, manually from Mainsail:

- three full homes
- Quad Gantry Level
- additional X/Y homing/movement

During the active test:

- all three MCU send/receive sequence counters stayed aligned
- `bytes_retransmit=0` on all three
- `bytes_invalid=0` on all three
- no motion stalls were reported
- QGL completed normally
- heaters remained off after the test

This is strong evidence that the Android USB/PTTY/Klipper path can survive real multi-MCU motion and Eddy activity.

## Remaining failure

At approximately 18:59:43, while the printer was idle after the active test, Klipper shut down with a lost-MCU timeout.

Shutdown analysis showed all three MCU links had fallen behind:

- STM32: retransmit activity and receive sequence lag
- NHK RP2040: retransmit activity and receive sequence lag
- Eddy RP2040: retransmit activity and receive sequence lag

The Android bridge also reported a full 250 ms USB bulk-write blackout. This points more toward a shared USB-host/hub/power-state interruption than a single MCU or a QGL load problem.

The Mainsail WebSocket remained alive after the Klipper shutdown, so this was not simply "the web UI disappeared." Screen-off is still known to break USB on the G2, but v249's kiosk keeps the display awake while it remains foreground.

## Do not call this print-stable yet

The next meaningful gate is not another code-cleanup build. It is a longer stable host session followed by a small real print.

Before calling the G2 print-ready, we want:

- no shared USB dropout during an idle soak
- normal home/QGL
- a known-good 10–20 minute print
- no meaningful MCU retransmit growth
- clean cooldown and continued host availability afterward

## After the first good print

Add these to active development:

- built-in Android camera stream
- USB webcam stream
- Moonraker/Mainsail camera configuration
- remote Mainsail + live camera from another LAN device
- raw config cache for standalone boot
- saved printer profiles instead of exact IDs/count in code
- automatic USB detach/reconnect recovery
- charging/power-state diagnostics and low-battery warnings
- optional dim/black-screen kiosk mode that keeps the display logically awake

This checkpoint supersedes older Fire-HD-era notes that said full homing was still the main blocker.