# Compatibility notes

## Baseline target

- Android API 24+
- ARMv7 or ARM64
- USB host/OTG capable device
- directly USB-connected Klipper MCU(s)
- native Klipper CDC USB is the best-tested path
- CH34x, CP210x, FTDI, and Prolific detection is inherited from usb-serial-for-android but is not yet considered print-ready

Python 3.11 is intentional because the current Chaquopy build supports both `armeabi-v7a` and `arm64-v8a`.

## How portable is it today?

### Same printer, different Android device

This is the most promising portability case.

The transport does not depend on fixed PTY numbers. Android discovers USB devices at runtime, AndroidKlipper creates new PTYs, and the printer config is rewritten from stable USB MCU IDs to the PTYs created for that boot.

That means moving the same printer from the G2 to a Fire tablet, another Android handheld, or a cheap Moto-class phone should not require changing Klipper's logical MCU identities.

The main device-to-device wildcards are outside Klipper:

- whether the Android device really supports USB host mode
- whether it keeps USB powered while idle
- whether it can charge while hosting USB
- hub/cable behavior
- vendor power-management rules
- USB permission behavior

So the software architecture is reasonably portable; Android hardware/power policy is the bigger risk.

### Different printer

Not generic yet.

The current real-host path deliberately expects the three known MCU IDs from the development Voron, and the auto-start UI still assumes three native Klipper USB devices. Those are bring-up safety rails.

The intended replacement is a saved printer profile containing the expected MCU identities/count and config source/cache. Until that exists, do not describe AndroidKlipper as plug-and-play for arbitrary Klipper printers.

## Known device behavior

### Retroid Pocket G2

- Android 15
- all three development-printer MCUs identify and run through the real Klipper config
- v252 completed a full 77:34 Benchy after home/QGL/Eddy scan
- successful run had zero print stalls and zero invalid MCU bytes
- isolated retransmit bursts still occur and remain under investigation
- persistent host lifecycle guard + wake lock stayed intact through the successful print
- physical screen-off/power-policy behavior still deserves dedicated soak testing; do not generalize one successful run into universal Android behavior

### Fire HD 8

Earlier builds proved local Mainsail, MCU communication, XY homing, fans/heaters/LED control, and periods of stable operation. It also showed bulk-write failures, charging/OTG headaches, and aggressive platform power behavior.

The current transport and wake fixes should be retested on the Fire rather than assuming old failures still apply.

### Cheap mainstream phones

A Moto G-class prepaid phone is a useful next test because it is closer to the project's actual value proposition than a specialty handheld: cheap, common Android hardware replacing a Pi-sized host.

USB host support and powered-hub behavior must still be verified per model.

## Charging while hosting USB

OTG + charging behavior is device and adapter dependent. This remains one of the strongest cases for an optional tested hardware kit.

Long-term software should expose:

- charging / discharging state
- battery percentage
- low-battery warning before a print
- whether external power disappeared during a print

Battery charge-limit controls should only be added when the device exposes a safe supported mechanism.

## Screen and idle behavior

A partial CPU wake lock is not enough on every Android device. Some devices independently suspend or power-gate the USB host controller when the display sleeps.

The current G2 workaround is to keep the Mainsail activity's display logically awake. A future kiosk option can dim the panel close to black while retaining the keep-awake flag.

Backgrounding the app and manually turning off the display still need dedicated testing.

## USB serial descriptors

If multiple identical MCUs do not expose unique USB serial numbers, automatic mapping is unsafe. The app should require a one-time user label/confirmation and remember the mapping.

## LAN access

Moonraker currently binds to the LAN and the bundled Mainsail server can be opened from another device on the same trusted subnet. This was verified during the G2 v249 test.

Future UX should show the current Mainsail LAN address with a copy/share button instead of making the user discover the IP manually.

## Cameras

Post-print TODO:

- Android built-in camera
- USB UVC webcam
- Moonraker/Mainsail camera registration
- remote Mainsail + live camera view
- timelapse after live streaming is reliable

## CAN

SocketCAN is not assumed to exist on Android. Native support for USB-to-CAN hardware is a separate workstream. Do not advertise CAN support until tested end-to-end.

## Android 17 local-network permission

Future builds targeting Android 17 will need to account for the platform's local-network permission before exposing Moonraker/Mainsail to other LAN devices.