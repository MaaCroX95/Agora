# Release Signing Contract

Status: authoritative release-build contract, 2026-08-27.

## 1. Goal

Agora Dev APKs intended for installation or update on Android must keep one stable application ID,
one stable signing identity, and a monotonically increasing version code. Android in-place updates are
only valid when the newly installed APK is accepted as the same package/signing lineage as the
installed APK and is not a version-code downgrade.

## 2. Signing identity

- The permanent Agora Dev signing private key is external secret material and must never be committed
  to the repository.
- GitHub Actions receives the key only through repository Actions secrets:
  `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD`.
- The expected permanent Agora Dev certificate SHA-256 fingerprint is
  `BA:4B:45:1D:80:48:90:22:35:B2:A9:0F:F3:4C:76:9E:02:BA:F1:4E:35:2C:03:97:89:42:42:DC:5C:EF:F3:BA`.
- `.jks` and `.keystore` files are ignored by Git.
- The CI keystore is reconstructed only on the ephemeral runner and is not uploaded as an artifact.
- Release builds must never silently fall back to the Android debug signing key.
- If any required signing secret is absent, empty, invalid, or the restored keystore cannot be read,
  the release build fails closed before producing an installable release artifact.
- Debug/unit-test builds remain independent of release-signing secrets.

## 3. Version-code policy

- Source/local builds retain the checked-in fallback `versionCode` unless `AGORA_VERSION_CODE` is
  supplied.
- The Build APK workflow supplies `AGORA_VERSION_CODE` as `100000 + GITHUB_RUN_NUMBER`.
- This CI range intentionally starts well above the historical source value (`30`) so the first
  permanently signed Agora Dev build establishes a monotonic update line for later CI builds.
- Once a permanently signed build is installed, future distributable builds must not use a lower
  version code.

## 4. CI verification

A distributable F-Droid release build must:

1. validate all four signing secrets without printing their values;
2. restore the keystore with restrictive file permissions;
3. validate that the configured alias is present in the keystore;
4. build `assembleFdroidRelease` with the CI version code;
5. verify the generated APK with Android `apksigner --print-certs`;
6. upload only the signed APK artifact, never the keystore or credential files.

The certificate fingerprint printed by `apksigner` is public identity metadata and may be retained in
CI logs for comparison between builds. The private key and passwords must never appear in logs.

## 5. Migration of an already installed development build

An APK already installed under a different signing certificate cannot normally be updated in place
with this new permanent key. Unless the exact old private key/signing lineage is available, the user
must export/backup Agora data, uninstall the old package once, and install the first permanently
signed APK. All later builds signed by the same permanent key and carrying a non-decreasing version
code can then use Android's ordinary update path.

## 6. Required verification for signing changes

Any future signing/build change must verify:

- debug unit-test tasks still configure and run without release secrets;
- a release task without complete signing configuration fails rather than using debug signing;
- a configured release build produces an APK whose signer fingerprint matches the permanent Agora
  Dev certificate;
- the CI-generated version code increases across later Build APK workflow runs;
- no private signing material is present in the repository diff or build artifacts.
