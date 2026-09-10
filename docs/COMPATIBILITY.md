# Compatibility notes

## Initial target

- Android API 24+
- ARMv7 and ARM64
- USB host/OTG capable device
- directly USB-connected Klipper MCU(s)
- CDC ACM first; CH34x, CP210x, FTDI, and Prolific detection is inherited from usb-serial-for-android but is not yet considered print-ready

Python 3.11 is intentional: current Chaquopy supports both `armeabi-v7a` and `arm64-v8a` with Python 3.11, which gives older Fire tablets a chance to work.

## Known gaps

### Charging while hosting USB

OTG + charging behavior is device and adapter dependent. This is likely the strongest case for an optional tested hardware kit. The software should detect battery state and warn before starting a long print if external power is unavailable.

### USB serial descriptors

If multiple identical MCUs do not expose unique USB serial numbers, automatic mapping is unsafe. The app should require a one-time user label/confirmation and remember the mapping.

### CAN

SocketCAN is not assumed to exist on Android. Native support for USB-to-CAN hardware is a separate workstream. Do not advertise CAN support until tested end-to-end.

### Fire OS background behavior

The host runs as a `connectedDevice` foreground service. Long-duration testing is still required for Fire OS task killers, screen-off behavior, Wi-Fi sleep, USB detach/reconnect, and low-memory pressure.

### Android 17 local network permission

Future builds targeting Android 17 will need to account for the platform's local-network permission before exposing Moonraker/Mainsail to other LAN devices.
