# Happy Hare v4 for the AndroidKlipper Moto G test build

This development branch extends `test/moto-side-by-side-2026-10-25`. Its debug
APK keeps the `.mototest` application ID so it cannot overwrite the working
Voron AndroidKlipper package. **Do not merge into production or update the Moto
app without confirming the debug signing certificate matches.** Never uninstall
the Moto app without first backing up printer_data.

## What the build does

`scripts/vendor-happy-hare.sh` pins `moggieuk/Happy-Hare` at commit
`70aa90fb9aa18787de445235bd26a0665168c441` and copies its Klipper Python
modules into both import locations used by AndroidKlipper, plus the Moonraker
`mmu_server` component. This runs **after** AndroidKlipper's pinned Klipper and
Moonraker vendor/patch steps. It does not run Happy Hare's Linux installer.

Packaging these modules alone is safe: **no MMU sections or Moonraker
`[mmu_server]` sections are enabled automatically**, no motor moves are made,
and existing `printer.cfg` remains unmodified. The Happy Hare Mainsail panel
will not show functional MMU state until we install/enable the correct v4 MMU
configuration and Moonraker component on the Moto.

## Device-specific facts from successful tests

- Moto G TEST Moonraker: `http://192.168.1.217:7125/`. Do not touch production.
- 4-lane PicoMMU; FYSETC ERB v2 MCU `mmu`, servo `mmu:gpio23`.
- Servo angles lanes 0,1,2,3 = **0, 25, 50, 80 degrees**.
- Motor is physically on the ERB **selector** header, but must be configured
  as the Happy Hare **gear** stepper using `gpio16` STEP, `gpio15` DIR (not
  inverted), `!gpio14` ENABLE, `gpio17` UART; 16 microsteps, proven motor
  run current 0.90 A; calibrated `rotation_distance=18.4` with current hardware.
- 2-wire switch at floating 4-to-1 hub: `^mmu:gpio22`. In the temporary test
  configuration this pin is owned by `manual_stepper pico_gear_test` as an
  endstop. It must be released from that temporary owner before Happy Hare
  can claim it as a hub/bowden/extruder-entry sensor.
- Temporary `PICO_LOAD_TO_HUB` stops on the GPIO22 endstop and backs off 50 mm.
  User confirmed successful lane 1 test; lanes 0/1 have PTFE runs installed.
- Crossbow cutter macro **not written**; full PTFE routing and lanes 2/3
  still incomplete. No automatic toolchange until those tasks are addressed.

## Next work after APK build

1. Safely back up the Moto app config/data and confirm new APK signing identity
   and `dev.nozzlenaut.androidklipper.mototest` package.
2. Install new test APK only after sign-off and back-up. Verify Moto's existing
   manual controls still work and `Klipper Ready` before enabling Happy Hare.
3. Generate the correct Happy Hare v4 PicoMMU four-lane config and Moonraker
   `[mmu_server]` stanza; stage alongside temporary configs, **never enable
   conflicting stepper/servo/sensor pins**. Validate board pin polarity.
4. Migrate one input at a time into Happy Hare, verify MMU status and Mainsail
   MMU panel, then test gate selection/load/unload before full toolchanges.

The standalone `extension_manager.py` on this base branch is experimental and
is not required for bundled-module import. Bundling modules does **not** prove
runtime Happy Hare compatibility; device testing is still required.
