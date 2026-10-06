# AndroidKlipper compatibility

## Baseline

- Android API 24+
- ARMv7 or ARM64
- USB host/OTG support
- directly USB-connected Klipper MCU(s)
- native Klipper CDC USB is the best-tested path

AndroidKlipper also inherits CH34x, CP210x, FTDI, and Prolific detection from usb-serial-for-android, but those paths are not yet considered print-proven.

## The important power rule

For long-duration printing, **simultaneous charging + USB host mode is a hardware requirement**.

A device may support USB-C and OTG perfectly and still refuse to accept charge while it remains the USB host. A powered hub cannot override that device-level USB role/power policy.

Devices which fail this requirement are still usable for battery-powered prints.

## Tested Android devices

| Device | USB printer host | Charge while hosting | Observed status |
| --- | --- | --- | --- |
| Retroid Pocket G2 | Yes | **Yes** | Best-tested AndroidKlipper host; sustained prints and long idle testing passed |
| Fire HD 8 (KFRAPWI) | Yes | **No with tested hubs/cables** | Printer control works; use as a battery-powered host unless another power setup is proven |
| Moto G (2024), XT2413V | Yes | **No with tested powered OTG setups** | Clean-device onboarding, Mainsail, normal config, READY, and homing passed; powered OTG did not remain reliable |

Power behavior should be recorded per exact device and hub/cable combination rather than generalized across a brand or Android version.

## Generic printer onboarding

The current generic onboarding checkpoint no longer assumes the development Voron's three MCU IDs.

Normal behavior is:

1. discover supported attached USB serial devices;
2. request Android USB permissions serially;
3. identify the connected Klipper MCUs;
4. start Moonraker and Mainsail;
5. accept the user's normal config tree through Mainsail;
6. wait for referenced include files;
7. start Klippy once the config tree is complete.

Stable USB identities are mapped to PTYs at runtime. PTY numbers may change between boots without requiring the user's config to be rewritten.

## Persistent printer data

`AndroidKlipperDrive` keeps persistent printer data in the app's private storage:

- `config/`
- `gcodes/`
- `database/`
- `backups/`

Transient logs/comms stay separate. Existing persistent data wins during migration so APK updates do not overwrite a user's printer files.

## Device name

Klipper receives the Android device's configured user-visible name when available, otherwise manufacturer/model. This prevents generic `localhost`-style host naming in Mainsail.

Config-defined object names are preserved. For example, a migrated `[temperature_sensor NUC Temp]` remains named `NUC Temp` until the user renames that section; AndroidKlipper does not silently mutate it.

## LAN access

Mainsail and Moonraker are available to other devices on the same trusted LAN. The Android app shows the current remote Mainsail address and provides a copy button after startup.

## Screen and idle behavior

A CPU wake lock alone is not sufficient on every Android device. Some devices independently suspend or power-gate USB host traffic when the display sleeps.

The embedded Mainsail kiosk keeps the display logically awake on devices which require it. The panel may be dimmed manually, but forcing real display sleep can still be device-dependent.

## USB detach/reconnect

Firmware-restart recovery is hardened and can rebind MCUs which re-enumerate during `FIRMWARE_RESTART`.

Arbitrary physical cable detach/reconnect is not yet considered fully automatic/release-proven. A visible recovery path remains preferable to silently guessing after USB topology changes.

## Duplicate MCU identities

Automatic mapping requires stable unique USB serial identity. If multiple identical devices expose no unique serial number, AndroidKlipper should require a one-time user-confirmed mapping before calling that setup safe.

## G-code metadata

The in-process Android Moonraker metadata patch is included. Static/project checks cover the patched path, but layer count, ETA, and thumbnail/SVG extraction still need a dedicated on-device validation checkpoint before being marked proven.

## Camera

Camera support is deliberately outside the v1 host core. It can be developed independently without changing the proven USB/Klipper timing path.

## CAN

SocketCAN is not assumed to exist on Android. Native USB-to-CAN support is a separate workstream and should not be advertised until tested end-to-end.
