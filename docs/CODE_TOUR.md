# AndroidKlipper code tour

Current as of the October 8, 2026 hardening candidate. Historical checkpoint files
record earlier builds and should not be used as descriptions of current code.

## Android host and UI

- `MainActivity.kt`: setup, USB permission requests, status, LAN address, and
  advanced controls. It passes the current keep-awake setting to the kiosk in
  an Intent; SharedPreferences must not be relied on across processes.
- `MainsailActivity.kt`: isolated `:mainsail` WebView process, local UI and kiosk
  wake flag (ON by default). A screen-off option does not prove a device can
  print asleep; the G2 requires the existing awake behavior.
- `KlipperHostService.kt`: foreground service, CPU wake lock, generic MCU
  discovery, Moonraker-first onboarding, include readiness, and Klippy startup.
  Duplicate starts preserve the running host. Removing the activity task leaves
  the foreground service in place rather than promoting it again.
- `HostDiagnostics.kt`: persisted lifecycle and USB evidence.
- `PersistentPrinterDrive.kt`: persistent config, G-code, database, logs and
  backup directories. A complete user-facing backup/restore flow is still open.

## USB and Klipper

- `usb/UsbDeviceScanner.kt` and `UsbPermissionReceiver.kt`: discovery and chained
  permissions. Supported serial devices become MCUs after protocol probing.
- `usb/UsbSerialSession.kt`: physical USB/PTY forwarding, bounded writes and
  teardown. The October 8 pass does not change this transport or its timing.
- `pty/PtyBridge.kt` and `cpp/pty_bridge.cpp`: raw PTY byte transport.
- `python/hostprobe.py`: diagnostic imports, MCU identify and smoke tests.
- `python/persistent_host.py`: runs the uploaded persistent config. Stable USB
  identities map to PTYs at connection time; there is no fixed three-MCU model
  or remote NUC config bootstrap. Normal RESTART remains inside Klippy;
  FIRMWARE_RESTART returns to Kotlin for USB rebinding.
- `config/ConfigRewriter.kt`: compatibility helper; the normal persistent-host
  path does not rewrite the user's config to ephemeral PTY names.

## Moonraker, Mainsail and telemetry

- `python/moonraker_runner.py`: embedded Moonraker lifecycle and local network
  configuration. It can start before printer.cfg exists.
- `MainsailServer.kt`: APK web assets, local/LAN config, uncached battery endpoint
  and service-worker cache cleanup across app updates.
- `scripts/patch-moonraker.py`: Android compatibility patches, including metadata
  extraction in a worker thread rather than launching Android app_process as
  though it were a Python interpreter. The parser is upstream Moonraker's.
- `scripts/patch-mainsail.py` and `mainsail-battery.ts`: chart integration and a
  bounded asynchronous sampler. Temperature updates do not wait for battery
  HTTP requests. Missing/stale battery data becomes a gap, not a made-up zero.
  Samples are browser-session data, not persistent battery history.

## Build and validation

Run all three `scripts/vendor-*.sh` scripts before building. They pin upstream
Klipper, Moonraker and Mainsail and apply readable patches. Generated upstream
files and the web bundle are ignored rather than edited by hand.

- `scripts/check-project.py`: static invariants, including frozen USB/timing
  assumptions. These are guardrails, not a replacement for behavioral tests.
- `scripts/tests/test_metadata.py`: actual patched worker and pinned parser,
  layer/time/thumbnail extraction, nested filenames and error recovery.
- `scripts/tests/battery.test.cjs`: sampler failures, timeout, stale/invalid
  readings, late responses, and chart updates without temperature sensors.
- `app/src/test/`: Android JVM tests.
- `.github/workflows/android.yml`: builds assets, runs tests/checks, builds and
  inspects the APK, then publishes this branch's successful prerelease candidates.

See `HARDENING_2026-10-08.md` for the remaining device acceptance gate. Camera and
Pico experiments remain separate and are not part of this candidate.
