# Experimental extension support: Happy Hare

This work is isolated on the test branch. **Do not flash it to a printing
machine yet.** There is no usable installation UI or end-to-end Android test.

## Supported starting point

- Happy Hare v4 upstream source layout (Python in `extras/`, Moonraker
  component in `components/`, configuration templates in `config/`).
- `extension_manager.install_archive(..., "happy_hare", ...)` stages files
  under AndroidKlipper's app-private printer_data/extensions directory,
  checks paths and size limits, and keeps the previous install for rollback.
- `extension_manager.activate(root, "extras")` extends Klipper's Python
  package import path. Moonraker uses `"moonraker.components"`.
- Linux `install.sh` is never run by Android.
- The Py tests use a synthetic archive, **not a real Happy Hare release**.

## Still required before functional MMU support

1. Verify archive provenance and pin a compatible upstream Happy Hare version.
2. Hook activation into persistent_host and moonraker_runner before plugin
   discovery; no mid-print installation or reload.
3. Generate the correct PicoMMU + FYSETC ERB V1/V2 configs (pins, channels,
   sensors, toolhead, cutter), without overwriting printer.cfg or user macros.
4. Configure [mmu_server] and Klipper includes, with backup and rollback.
5. Expose a controlled install/enable/disable flow in the Android UI.
6. Check Happy Hare Python dependencies and integration with packaged
   Klipper/Moonraker; then build APK and test two USB MCUs on real hardware.

The user-supplied staging ZIP is a separate six-patch series based on
17007d60. It must be applied and full CI validated before testing on a printer.
