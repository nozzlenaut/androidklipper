# Roadmap

## M0 - feasibility (done outside this repo)

Fire HD 8 -> Android USB host -> serial bridge -> Klipper -> real MCU configured successfully.

## M1 - native host transport (current)

- [x] Android project scaffold
- [x] USB serial enumeration
- [x] explicit Klipper CDC VID/PID probe
- [x] multiple PTY bridge architecture
- [x] foreground connected-device service
- [x] embedded Python/pySerial probe
- [x] NDK build definition for Klipper c_helper
- [x] runtime-only config rewriter + unit test
- [x] CI produces installable APK
- [x] Fire HD 8: 3 USB MCUs visible simultaneously
- [x] Fire HD 8: stable USB serial IDs confirmed in native-app report
- [x] Fire HD 8: 3 PTYs created simultaneously
- [x] Fire HD 8: Klipper opens all PTYs as raw byte pipes (pySerial intentionally bypassed for Android PTYs)
- [x] Fire HD 8: prebuilt c_helper loads through cffi

## M2 - run real Klippy inside the app

- [x] start/stop diagnostic Klippy under service lifecycle
- [x] connect harmless `kinematics: none` config to physical MCUs
- [x] connect all three USB MCUs using runtime PTY mapping
- [x] full no-pin Klippy reaches Ready on all three MCUs (build 117)
- [x] import active Voron config and reach the real-config Ready callback path (build 136)
- [x] patch Android-missing `os.getloadavg()` crash in Klipper statistics (build 141; hardware re-test pending)
- [x] require successful MCU identify before a PTY can enter full/real Klippy
- [x] diagnostic service is non-sticky to prevent ambiguous Android restarts
- [ ] clean recovery after USB detach/reconnect
- [ ] persistent production Klippy lifecycle (do not exit at Ready)
- [ ] 8/24/48-hour idle soak tests

## M3 - useful printer host

- [x] import a full printer config tree from the existing Moonraker host (diagnostic migration path)
- [ ] persist/manage imported config locally without requiring the old host
- [ ] Moonraker
- [ ] bundled Mainsail
- [ ] local web UI
- [ ] printer files / G-code upload
- [ ] reboot autostart option
- [ ] safe power/battery warnings

## M4 - phone/tablet features

- [ ] built-in camera stream
- [ ] timelapse
- [ ] notifications
- [ ] local backups
- [ ] optional failure detection

## M5 - hardware / ecosystem

- [ ] tested powered OTG adapters
- [ ] USB power-isolation/backfeed testing
- [ ] universal phone/tablet mount
- [ ] optional hardware kit
- [ ] donation links / sponsors


## Verified hardware milestone
- [x] Fire HD 8: all three printer MCUs complete Klipper identify through Android USB -> native bridge -> raw PTY -> embedded Klipper
  - rp2040: `3033393834057C77` (139 commands)
  - stm32f446xx: `3F001E001450535556323420` (151 commands)
  - rp2040: `504450610844C31C` (139 commands)
  - verified on build 108 after printer power-cycle

- [x] Fire HD 8: full Klippy runtime reaches `Printer is ready` with all three physical printer MCUs through Android USB/PTYS
  - build 117
  - test config: `kinematics: none`, no configured pins
  - stm32f446xx: `3F001E001450535556323420`
  - rp2040: `3033393834057C77`
  - rp2040: `504450610844C31C`
  - dynamic Klipper packages `extras` and `kinematics` verified under Chaquopy


## Current Android compatibility notes

- Klipper statistics used `os.getloadavg()`, which Chaquopy Python on Android does not expose. AndroidKlipper patches this to read `/proc/loadavg` when available and otherwise report zero.
- The current real-config diagnostic exits at Ready; it is not yet the persistent production host.
- Resonance calibration is a later compatibility item: Klipper lazily requires NumPy for shaper analysis, uses multiprocessing for some calculations/writes, and uses Linux-style `/tmp` output paths. These do not block normal printer startup and are intentionally not bundled into the current startup-test build.
- USB detach/reconnect handling still needs a dedicated production lifecycle path before long-running printer use.


## Verified real-config milestone
- [x] Fire HD 8: full active Voron config reaches `REAL VORON CONFIG READY` against all three physical MCUs on Android
  - build 150
  - stm32f446xx: `3F001E001450535556323420`
  - Nitehawk rp2040: `3033393834057C77`
  - Eddy rp2040: `504450610844C31C`
  - active config imported from the old Moonraker host
  - Android runtime rewrites stable MCU IDs to dynamic PTYs
  - host-only K-ShakeTune include skipped
  - NUC temperature sensor stripped
  - `restart_method` stripped for PTY transports
  - Android `os.getloadavg()` incompatibility patched in the runtime Klipper statistics module
  - reached Ready with no unexpected motion or heating; LEDs configured normally; no shutdown/failsafe fan observed
