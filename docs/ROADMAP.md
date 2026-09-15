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
- [ ] Fire HD 8: stable USB serial IDs confirmed in final native-app report
- [x] Fire HD 8: 3 PTYs created simultaneously
- [ ] Fire HD 8: pySerial opens all PTYs
- [x] Fire HD 8: prebuilt c_helper loads through cffi

## M2 - run real Klippy inside the app

- [ ] start/stop Klippy under service lifecycle
- [ ] connect a harmless `kinematics: none` config to one MCU
- [ ] connect all USB MCUs using a runtime config mapping
- [ ] clean recovery after USB detach/reconnect
- [ ] 8/24/48-hour idle soak tests

## M3 - useful printer host

- [ ] import a full printer config tree
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
