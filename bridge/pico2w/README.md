# Pico 2 W AndroidKlipper Bridge (proof of concept)

This firmware turns a Raspberry Pi Pico 2 W into a tiny network USB bridge for
AndroidKlipper.

```
Klipper MCUs -> powered USB hub -> Pico 2 W -> Wi-Fi -> AndroidKlipper
```

The Pico does **not** run Klipper. It only moves raw bytes. Klippy, Moonraker and
Mainsail stay on Android.

## Hardware

- Raspberry Pi Pico 2 W
- Micro-USB OTG adapter for the Pico's USB connector
- Powered USB hub for the printer MCUs
- Regulated 5 V from the printer electronics bay

### Power note

When the Pico's Micro-USB connector is used as USB **host**, do not rely on that
connector to power the Pico from a PC. For the bench prototype, power the board
from a known-good regulated 5 V source and make sure the host/hub VBUS arrangement
is correct before connecting printer electronics.

A compliant USB host must present VBUS to the hub. The final wiring should use
the printer's regulated 5 V rail (or a 24 V -> 5 V buck) and provide 5 V to the
Pico/host VBUS without back-feeding a PC USB port. Do not have the Pico wired to
printer 5 V and a PC USB cable at the same time unless the power path is isolated.

## Firmware behavior

- joins Wi-Fi
- enables TinyUSB host mode
- supports a USB hub
- recognizes up to 3 native Klipper USB CDC MCUs
- reads each MCU USB serial number
- announces the bridge over UDP
- exposes one low-latency TCP byte stream per MCU
- enables TCP_NODELAY
- never silently discards bytes; overflow closes the session instead
- tracks reconnect and byte counters over UART diagnostics

## Build

Requires Raspberry Pi Pico SDK 2.x with Pico 2 W support.

```bash
cd bridge/pico2w
mkdir build
cd build
cmake -DPICO_SDK_PATH=/path/to/pico-sdk ..
cmake --build . -j
```

The UF2 will be:

```
build/androidklipper_bridge.uf2
```

## Wi-Fi

For the first hardware proof, edit `bridge_config.h` and set the SSID/password.
They are placeholders in the repo; do not commit real credentials.

Automatic Android provisioning is intentionally phase two. The first goal is to
prove that three USB MCUs can survive Klipper traffic over Wi-Fi.

## First test plan

1. Pico only: boot and join Wi-Fi.
2. One MCU: confirm VID/PID + serial and AndroidKlipper identify.
3. One MCU: repeated FIRMWARE_RESTART/reconnect.
4. Three MCUs through powered hub: identify all three.
5. Home + QGL.
6. Benchy with latency/byte/error telemetry.
7. Long print before declaring the transport production-ready.

See `../PROTOCOL.md` for the network contract.
