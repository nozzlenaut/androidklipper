# Fire HD 8 diagnostic test

This is the first on-device test for the native Android transport. It is intentionally non-printing.

## Before testing

- Leave the printer in a safe idle state.
- Connect the Fire tablet to the printer through the same OTG path used for the Octo4a proof.
- The diagnostic build only opens USB devices advertising Klipper's native USB VID:PID `1d50:614e`.
- The test does not send printer configuration, motion, heater, fan, or GPIO commands.

## Test

1. Install the latest `androidklipper-debug-apk`.
2. Open AndroidKlipper.
3. Tap **Scan USB**.
4. Confirm the expected Klipper MCUs are listed.
5. Tap **Grant USB access and start host test**.
6. Grant the Android USB permission prompt for each Klipper MCU.
7. Wait for the report to finish, then tap **Copy diagnostic report**.

## Ideal result

For the current three-MCU Voron test machine the report should show:

- `Klipper core imports OK`
- `Klipper import sweep OK: ... modules`
- `c_helper OK`
- three MCU entries
- a unique USB serial for each MCU, if the firmware exposes one
- three different PTY paths
- `pySerial OK` for each PTY
- `Klipper identify OK` for each MCU

The two RP2040 devices may have the same product name. Their USB serial descriptors are what matter for stable mapping.

## Useful failures

- **No known Klipper USB MCU found**: Android sees no `1d50:614e` device. Re-check OTG/cabling.
- **waiting for USB permission**: permission has not been granted for that device yet.
- **USB serial unavailable**: transport can still be tested, but automatic persistent MCU mapping is not safe yet.
- **pySerial ERROR**: the Android-created PTY is not behaving like the POSIX serial endpoint Klipper expects.
- **Klipper identify ERROR**: the PTY opened, but the real Klipper protocol handshake failed. Keep the full copied report.
- **USB bridge error**: the physical USB/PTY transport failed or detached.

Do not move on to real Klippy configuration until all expected MCUs pass this test.
