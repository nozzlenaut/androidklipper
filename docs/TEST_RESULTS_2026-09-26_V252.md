# v252 G2 long-print checkpoint

Date: 2026-09-26

Build: `host-persistence-252`
APK: `AndroidKlipper-v252-host-persistence.apk`
SHA256: `43464ca94f29ded249db3dc7f3897a68e1350df701b4592914b2aae093d63c94`
Runtime commit: `0a2cf15 Harden persistent host lifecycle and diagnostics`
Repo head after workflow restore: `141d503 Restore normal Android build workflow [skip ci]`

## Test fixture

- Retroid Pocket G2, Android 15
- Voron 2.4 350
- STM32F446 main MCU
- RP2040 NHK/toolhead MCU
- RP2040 Eddy MCU
- bundled Klipper + Moonraker + Mainsail
- printer connected through the same USB/hub path used for prior v249 tests

## Timeline

- stable v252 host session began: 14:47:08 EDT
- print command sent: 14:51:31 EDT
- print completed: 16:13:30 EDT
- actual Klipper print duration: 4653.686 s (1:17:33.7)
- total job duration: 4935.223 s (1:22:15.2)
- final status: `completed`

## What passed

- full home / QGL / Eddy scan startup path
- 77+ minutes of real printing
- no `Timer too close`
- no lost MCU communication shutdown
- no `GreenletExit`
- no USB bridge error
- no service teardown/rebuild during the print
- no wake-lock release during the print
- no invalid MCU bytes
- `print_stall=0` through completion
- same AndroidKlipper PID (`13586`) remained active for the successful host session
- Moonraker and Mainsail remained available after completion

This is the first convincing long-print success on the G2 and clears the previous v249 failure window. The failed v249 Benchy stopped at about 61:55 of actual printing; v252 completed 77:34.

## MCU retransmit timeline

Retransmits were not zero, but they appeared as isolated bursts and never caused a Klipper stall or invalid byte:

- 15:05:29 — main 0 -> 55, NHK 21 -> 83, Eddy 0 -> 14
- 15:36:14 — Eddy 14 -> 21
- 15:38:28 — main 55 -> 1339
- 15:55:56 — main 1339 -> 1408, NHK 83 -> 153
- 16:07:06 — Eddy 21 -> 28

Final successful-print counters: main 1408, NHK 153, Eddy 28; all three had `bytes_invalid=0`.

First scripted post-print checkpoint at 16:23:08 EDT (about ten minutes idle): main 1408, NHK 153, Eddy 35; all three still had 0 invalid bytes. The extra Eddy +7 is another isolated event to correlate during the longer idle soak.

The large 15:38 main-MCU burst is worth keeping on the watch list, but it was not accompanied by rising latency, invalid bytes, a stalled print buffer, or a service lifecycle event. Do not hide or threshold-loosen this away; gather more runs first.

## Host flight recorder findings

The new `androidklipper-host.log` worked and materially improved diagnosis. During the successful session it continuously recorded:

- `persistent=true`
- wake lock held
- three USB sessions alive
- per-session RX/TX and read/write retry counters
- Android interactive/device-idle state

No duplicate-start teardown or service destruction occurred after the stable host came up.

The recorder also exposed several AndroidKlipper process crashes/restarts during installation/startup before PID 13586 became the stable session. Those are a separate startup issue to reproduce later; they did not recur during the print.

## Known functional gap

Moonraker G-code metadata extraction is still broken on Android. `current_layer` and `total_layer` remain null, so Mainsail shows 0/0 layers. The same failure prevents normal ETA/file metadata and thumbnail/SVG preview extraction.

Treat layer count, ETA, thumbnails, and richer file metadata as one metadata-parser workstream rather than separate UI bugs.

## Post-print plan

Leave the successful host powered and idle for an extended soak. After the soak, capture the same status/history/flight-recorder snapshot before changing anything. If idle remains clean, the next useful steps are metadata repair, Moonraker database persistence/backup, and a v252 Fire HD 8 retest before adding more host complexity.


## Metadata follow-up after the print

The v252 Moonraker log confirmed that metadata extraction was failing before the
parser ever ran. Moonraker tried to launch `metadata.py` with
`/system/bin/app_process64` because Chaquopy exposes that as `sys.executable`.
Each attempt exited with return code `-6`.

A repo-only fix is now staged for the next APK: call Moonraker's existing
metadata parser in-process on its worker thread instead of spawning a child
Python process. Pillow 11.0.0 is also added for thumbnail handling. This is not
part of the installed v252 build and still needs on-device validation.

The actual Benchy was parsed successfully with the pinned upstream parser on
the desktop: PrusaSlicer 2.9.3, estimated time 4559 s, object height 48.0 mm,
0.2 mm layer height, 0.2 mm first layer, and 64x64 + 400x300 thumbnails.
