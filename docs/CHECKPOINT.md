# AndroidKlipper checkpoint — historical 2026-09-26

This file preserves the v252 stability checkpoint as historical test evidence.

For the current generic first-run baseline, see `GENERIC_ONBOARDING_CHECKPOINT_2026-10-03.md` and the repository README. Do not use the "next gate" list below as the current project roadmap.

## Current working stack

Current test build: v252 (`host-persistence-252`).

Current primary device: Retroid Pocket G2, Android 15.

The app currently runs:

- Android USB host transport
- one PTY bridge per printer MCU
- embedded Klipper
- embedded Moonraker
- bundled Mainsail
- removable-storage G-code directory when available
- automatic real-host startup and kiosk flow
- foreground connected-device service + partial CPU wake lock
- sticky persistent-host intent for Android service recreation
- duplicate-start guard so USB callbacks cannot rebuild a live host
- persistent Android-side host flight recorder
- LAN-facing Mainsail and Moonraker

Remote Mainsail is verified. Camera streaming remains a separate future feature.

## Printer fixture

Voron 2.4 350 with three native Klipper USB MCUs:

- STM32F446 mainboard — `3F001E001450535556323420`
- RP2040 NHK/toolhead — `3033393834057C77`
- RP2040 Eddy/probe — `504450610844C31C`

These exact IDs are still bring-up safety rails, not the final generic-printer model.

## v252 long-print result — PASS

Stable host PID 13586 came up at 14:47:08 EDT. The Benchy print command was sent at 14:51:31 and Moonraker recorded completion at 16:13:30.

Result:

- `3DBenchy.gcode` completed
- print duration: 4653.686 s (1:17:33.7)
- total job duration: 4935.223 s (1:22:15.2)
- file progress reached 100%
- `print_stall=0`
- `bytes_invalid=0` on all three MCUs
- no `Timer too close`
- no lost-MCU shutdown
- no `GreenletExit`
- no USB bridge failure
- no host service teardown/rebuild during the print
- wake lock stayed held
- Moonraker/Mainsail remained available after completion

This clears the previous v249 Benchy failure window. That run died after about 61:55 of actual printing with a Klippy/Moonraker teardown; v252 completed 77:34.

Detailed timestamps/build/hash are in `TEST_RESULTS_2026-09-26_V252.md`.

## Retransmits are not solved yet

The successful run still had isolated retransmit bursts. Final counters at completion were:

- main MCU: 1408 retransmitted bytes, 0 invalid
- NHK: 153 retransmitted bytes, 0 invalid
- Eddy: 28 retransmitted bytes, 0 invalid

The largest burst was main 55 -> 1339 around 15:38:28. It did not produce a stall, invalid byte, rising steady-state latency, or lifecycle event. Keep measuring it; do not weaken Klipper safety thresholds to hide it.

## What v252 changed

Two source-level failure paths were addressed before this run:

1. v249 rebuilt Klippy/Moonraker/USB on every service start. A duplicate USB attach/permission callback could therefore tear down a live host. v252 ignores duplicate starts while the persistent host is active.
2. v249 ran six USB pump threads at `THREAD_PRIORITY_URGENT_AUDIO` while Klippy ran at normal priority. v252 gives Klippy higher scheduling priority and backs USB pumps down to foreground priority. This is especially relevant to the Fire HD 8 `Timer too close` failures.

Neither theory is considered universally proven yet, but the G2 result strongly supports the lifecycle fix.

## Flight recorder

`printer_data/logs/androidklipper-host.log` now records service lifecycle, wake-lock state, Android interactive/idle state, USB session counters, permission callbacks, and historical process exit reasons.

It caught several app crashes/restarts during install/startup before the stable PID 13586 session. Those startup crashes are still worth reproducing; they did not recur during the print.

Use `scripts/capture-runtime-checkpoint.py` to snapshot Moonraker state/history plus the host log and a bounded Moonraker log tail.

## Known feature gap: G-code metadata

Android Moonraker metadata extraction is still failing. Mainsail therefore shows 0/0 layers and misses normal ETA/thumbnail/SVG metadata.

Treat these as one metadata-parser problem:

- layer count
- estimated print time
- thumbnails/SVG preview
- richer file/filament metadata

## Current soak / next gate

The G2 is intentionally being left powered and idle after the successful Benchy. Do not change the runtime merely to create another test case.

After the idle soak:

1. capture a runtime checkpoint before touching the device
2. confirm PID/service continuity, wake lock, Moonraker/Klippy availability, and MCU counters
3. if clean, treat G2 long-print + post-print idle as the new stable baseline
4. repair Moonraker metadata extraction
5. make Moonraker database/history portable across upgrades/reinstalls or exportable with the device profile
6. retest the same v252 build on the Fire HD 8 with Desktop Commander/Termux out of the print path
7. only then add camera/appliance features

The G2 is now print-proven for one long real print. It is not yet declared universally stable across devices, power paths, or repeated long jobs.
