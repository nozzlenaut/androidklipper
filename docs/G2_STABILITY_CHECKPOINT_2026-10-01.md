# G2 stability checkpoint — 2026-10-01

## Known-good code checkpoint

- Tested code commit: `a83f2455106ad0654454a15d56b46301b9e09398`
- Git tag: `g2-stable-2026-10-01`
- Branch at time of test: `g2-print-stability`
- CI: Android build #271 passed static checks, unit tests/debug APK build, APK inspection, and artifact upload.
- This tag intentionally points to the exact tested code before later onboarding/refinement work.

## Reference hardware

- Host: Retroid Pocket G2
- Printer: Voron 2.4 350
- Main MCU: Octopus STM32F446
- Toolhead MCU: NH36 RP2040 (`mcu nhk`)
- Probe MCU: BTT Eddy RP2040 (`mcu eddy`)
- AndroidKlipper host process during successful test window: PID 14660
- Three USB MCU sessions remained active throughout the successful print/idle windows.

## How we got here

The previous v255/ci257 hardening baseline survived roughly 21 hours idle but failed a real print after about 29 minutes with:
`MCU 'nhk' shutdown: Timer too close`

The important finding was that Klipper could report roughly two seconds of overall motion buffered while its native serial queue still held MCU-bound scheduled traffic until about 100 ms before the MCU clock deadline. AndroidKlipper adds PTY -> Kotlin/JVM -> Android UsbManager -> USB after that native queue, so the Linux-oriented 100 ms transmit margin was too tight for this transport path.

The stability branch then hardened the pipeline in three passes:
1. Native scheduled transmit margin: patched Klipper `MIN_REQTIME_DELTA` from 0.100 s to 0.500 s.
2. Active step-generation window: moved from about 0.45–0.70 s ahead to 1.0–1.5 s ahead.
3. Android bridge/lifecycle: removed per-packet `ByteArray.copyOf()` allocation, hardened PTY JNI writes, and kept the foreground host alive across Android task removal without rebuilding Klippy/USB/PTYS.

The existing roughly 2 second motion buffer remained in place. The new work made lower transport layers actually benefit from that headroom.

A fourth change added persistent G-code storage under `AndroidKlipperDrive/gcodes`, while keeping `printer_data/gcodes` as the Klipper/Moonraker-facing path via symlink.

## Validation performed

### Baseline / failure evidence
- ~21 hour idle soak on ci257: stable.
- Real print on ci257: failed about 29m13s in with `Timer too close` on NHK.
- Failure occurred while overall toolhead buffer still reported about 2 seconds.
- No >50 ms AndroidKlipper reactor-late warnings immediately preceding that failure.
- Android bridge write retries were 0 before the primary failure.
- A later all-three-MCU USB collapse after manual firmware restart/task removal was treated as a separate lifecycle issue.

### Successful print 1
- File: `photo_to_gridfinity_v5_2.gcode`
- Result: COMPLETE
- Print duration: 6699.495 s = about 1h 51m 39s
- Total job duration: 6980.042 s = about 1h 56m 20s
- Filament used: 16234.004 mm
- Post-print Klipper state: READY
- Android USB bridge writeRetries: 0 during the successful print window
- MCU bytes_invalid: 0 on main, NHK, and Eddy
- No `Timer too close`, lost-MCU, recovery, disconnect, or USB bridge errors during the successful print window
- Post-print retransmit counters were flat across an 8 second resample while traffic continued

### Idle after successful print 1
- Same AndroidKlipper PID 14660 remained alive overnight and into the next morning.
- All three MCU sessions remained connected.
- Host heartbeats continued with writeRetries=0.
- No overnight `Timer too close`, USB bridge, lost-communication, recovery, or disconnect events.
- At about 10:39 on 2026-10-01, retransmit counters were main 8385, NHK 5579, Eddy 750 and were flat across an 8 second resample.
- bytes_invalid remained 0 on all three MCUs.

### Successful print 2
- File: `joyconraildown.gcode`
- Result: COMPLETE
- Print duration: 5536.445 s = about 1h 32m 16s
- Total job duration: 5866.914 s = about 1h 37m 47s
- Filament used: 11427.320 mm
- G-code size: 5966500 bytes
- Post-print Klipper state: READY
- Same AndroidKlipper PID 14660
- Android USB bridge writeRetries: 0
- MCU bytes_invalid: 0 on all three MCUs
- Host error count for 2026-10-01 at the post-print check: 0
- MCU srtt about 1 ms and rto 25 ms
- Retransmit counters after print: main 17257, NHK 11751, Eddy 1452; all three were flat on an 8 second post-print resample while traffic continued

## Current interpretation

The G2 reference setup has now passed two sustained real prints totaling about 3h24m of actual print time, plus long idle periods before/between/after them, without a repeat of the original timer-too-close failure or an Android USB transport collapse.
This is strong evidence that the timing/USB changes fixed the reference G2 setup, but it is not proof across every Android device, hub, board, or printer. Cumulative retransmit counts should be treated as transport telemetry, not failures by themselves; the important observations were zero invalid bytes, zero app-side write failures, healthy latency, and flat counters after each print.

## Freeze boundary

Do not change the following without a new failure/log reason:
- Native Klipper send-ahead timing
- Step-generation lookahead values
- Android USB bridge hot path
- Foreground-service lifecycle behavior

Normal useful prints can continue accumulating validation data, but dedicated test prints are no longer required unless something regresses.

## Deferred / not part of this checkpoint

- Camera branch is still separate and should not be used to judge this stability checkpoint.
- Full persistent printer state is not implemented yet. Only G-code persistence is present.
- Current config is still the known G2/Voron setup; generic user onboarding has not yet been validated.
- Moonraker Tailscale authorization edits were staged later in the runtime config without restarting the host and are not part of the code checkpoint.

## Recommended next development sequence

1. Expand `AndroidKlipperDrive` into the durable printer state:
   - `config/`
   - `gcodes/`
   - Moonraker `database/`
   - `backups/`
   - other user-generated metadata/state worth preserving
   Keep normal Klipper/Moonraker paths by linking the runtime tree into this drive.

2. Make fresh-install behavior boring:
   - create the drive only when absent
   - seed a complete default config directory only on first run
   - never overwrite an existing user's config/state during APK updates

3. Make MCU discovery generic:
   - enumerate attached Klipper-capable USB devices
   - preserve stable device identities
   - show users what AndroidKlipper found and what PTY/mapping is active

4. Use Mainsail as the config-management UI where possible:
   - Moonraker should come up even if Klippy has no valid printer config
   - user uploads `printer.cfg` and included files through Mainsail
   - AndroidKlipper only needs to provide clear MCU/mapping information and useful config-error status

5. Add a minimal first-run/status screen:
   - storage status
   - detected MCUs
   - Moonraker state
   - Klipper READY / config error
   - camera state
   - Open Mainsail button

6. Add built-in Android camera support after the printer/config flow is proven, using the phone camera first so USB stability is not complicated unnecessarily.

7. Add one-tap diagnostic export before wider beta testing.

## Graduation test

Use the Moto G as a clean-room install:
fresh Android device -> install APK -> connect printer -> detect MCUs -> open Mainsail -> upload a normal existing Klipper config -> resolve mappings if needed -> Klipper READY -> upload G-code -> complete a real print -> enable built-in camera.

If that works without ADB, command-line work, or Dalton-specific baked-in data, move to a small outside beta with other Klipper boards/devices.
