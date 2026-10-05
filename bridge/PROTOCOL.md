# AndroidKlipper Bridge Protocol v1

AndroidKlipper Bridge is intentionally a dumb transport. Klippy, Moonraker, config
rewriting, scheduling, and printer state all remain on the Android device.

## Network ports

- UDP 7130: discovery
- TCP 7131: MCU slot 0 raw byte stream
- TCP 7132: MCU slot 1 raw byte stream
- TCP 7133: MCU slot 2 raw byte stream

Each TCP port carries exactly one MCU's raw Klipper USB CDC byte stream. There is
no framing on the TCP data connection.

## Discovery

AndroidKlipper broadcasts this payload to UDP port 7130:

```
AKDISCOVER/1
```

A bridge replies unicast to the sender with a single JSON object:

```json
{
  "type": "androidklipper-bridge",
  "protocol": 1,
  "bridge_id": "e6614103e72f8b2c",
  "firmware": "0.1.0-poc",
  "mcu_count": 2,
  "mcus": [
    {"serial":"ABC123","port":7131},
    {"serial":"DEF456","port":7132}
  ]
}
```

AndroidKlipper maps each reported USB serial number to the same temporary PTY
mechanism already used by local USB. The user's Klipper config is still mapped
by stable MCU serial ID, so USB enumeration order does not matter.

## Disconnect/restart behavior

- A USB MCU unplug/reset causes its TCP client to be closed.
- The bridge keeps the slot/port reserved for that MCU serial when possible.
- AndroidKlipper reconnects and creates/keeps the PTY mapping by serial ID.
- Silent byte loss is never permitted. Buffer overflow is treated as a hard
  transport failure and the TCP connection is closed.

## Klipper USB identity

The proof of concept accepts Klipper native USB devices with:

- VID: 0x1d50
- PID: 0x614e

The bridge uses the device USB serial string as the stable ID.
