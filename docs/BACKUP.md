# Backup and restore (debug builds, via adb)

Uninstalling AndroidKlipper (which a signing-key change forces) deletes
`AndroidKlipperDrive`: printer config, the Moonraker database and G-code. Take
a backup first.

Replace `<pkg>` with the debug application id.

## Backup

    adb shell am broadcast -n <pkg>/dev.nozzlenaut.androidklipper.DebugControlReceiver \
      -a dev.nozzlenaut.androidklipper.DEBUG_BACKUP            # add: --ez gcodes true

- Includes `config/` and `database/` (and `gcodes/` with `--ez gcodes true`).
- While Klippy/Moonraker are running the **database is left out** (LMDB can be
  captured half-written). Stop the host first (`DEBUG_STOP`) for a full backup.
- Written to the app's external files `backups/` folder (`adb pull
  /sdcard/Android/data/<pkg>/files/backups/`) and, on Android 10+, to
  `Download/AndroidKlipper/`, which survives an uninstall.
- The result is logged to the host log (`androidklipper-host.log`).

## Restore

1. Stop the host: `... -a dev.nozzlenaut.androidklipper.DEBUG_STOP`
2. Put the zip in the app's external files `backups/` folder (`adb push`).
3. `... -a dev.nozzlenaut.androidklipper.DEBUG_RESTORE --es name <file>.zip`

The archive is validated and staged before anything is replaced. Replaced
folders are kept under `AndroidKlipperDrive/backups/pre-restore-<timestamp>/`,
and a failure part-way rolls back. Restore refuses to run while the host is up.
