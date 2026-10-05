# AndroidKlipper Pico W Setup + Benchmark firmware

Temporary bench firmware for a **regular Raspberry Pi Pico W**.

This build intentionally does **not** enable USB host mode. It is safe to leave
the Pico W plugged into a computer over Micro-USB while testing setup and Wi-Fi.

## Setup

1. Flash `androidklipper_setup_bench.uf2`.
2. Connect a phone/laptop to:
   - SSID: `AndroidKlipper-Setup`
   - password: `androidklipper`
3. Open `http://192.168.4.1/` if the captive portal does not open itself.
4. Enter the Wi-Fi network that the Android device and bridge will share.
5. Credentials are saved in Pico flash.
6. After joining that Wi-Fi, the Pico starts an iperf2-compatible TCP server.

## Bench

After provisioning, run an iperf2 client from another machine on the same
network against the Pico's IP. Ping can be used for latency/jitter checks.

This firmware is only for setup/network validation. The production bridge UF2
will use the Pico's USB port as USB host and must be powered separately.
