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
- [x] runtime-only config rewriter + unit tests
- [x] CI produces and inspects an installable debug APK
- [x] safe Klipper protocol-identify diagnostic path
- [x] device-side Klipper module import sweep
- [ ] Fire HD 8: 3 MCUs visible with stable serial IDs
- [ ] Fire HD 8: 3 PTYs created simultaneously
- [ ] Fire HD 8: pySerial opens all PTYs
- [ ] Fire HD 8: prebuilt c_helper loads through cffi
- [ ] Fire HD 8: Klipper identify succeeds on all 3 MCUs

## M2 - run real Klippy inside the app

- [ ] start/stop Klippy under service lifecycle
- [ ] connect a harmless `kinematics: none` config to one MCU
- [ ] connect all USB MCUs using a runtime config mapping
- [ ] clean recovery after USB detach/reconnect
- [ ] 8/24/48-hour idle soak tests

## M3 - useful printer host

- [ ] import a full printer config tree, including `[include ...]` files
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
