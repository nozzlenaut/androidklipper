# Debug signing key

AndroidKlipper CI builds debug-signed APKs. Android only installs an update over
an existing install when the signing key is identical. When it is not, the only
fix is uninstalling, which also deletes `AndroidKlipperDrive` (printer config,
Moonraker database, G-code). Treat the signing key as part of the user's data.

## Why the cache is not enough

Earlier CI kept the key only in the GitHub Actions cache. GitHub evicts caches
that have not been used for about seven days, and the workflow then silently
generated a brand-new key.

## Recommended setup (one time)

1. Create a keystore (or use an existing one) with alias `androiddebugkey` and
   store/key password `android` (the values `app/build.gradle.kts` expects):

       keytool -genkeypair -keystore debug.keystore -storepass android \
         -alias androiddebugkey -keypass android -dname "CN=Android Debug,O=Android,C=US" \
         -keyalg RSA -keysize 2048 -validity 10000

2. Add the repo secret `ANDROIDKLIPPER_DEBUG_KEYSTORE_B64`:

       base64 -w0 debug.keystore

3. Add the repo variable `ANDROIDKLIPPER_DEBUG_KEY_SHA256` with the certificate
   fingerprint printed by

       keytool -list -v -keystore debug.keystore -storepass android -alias androiddebugkey

   With the variable set, CI fails loudly if the key ever differs instead of
   publishing an APK that cannot update existing installs.

Keep a private offline copy of `debug.keystore`.

## Migrating from the cached key

The cached key cannot be extracted safely (publishing it as an artifact on a
public repo would let anyone sign updates for this app id). Switching to a new
key therefore needs one reinstall on each device. Back up first (see
`docs/BACKUP.md`; stop the host so the database is included) because
uninstalling erases `AndroidKlipperDrive`, then restore after reinstalling.
