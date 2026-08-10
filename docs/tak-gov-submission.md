# tak.gov Third Party Pipeline — lean source zip

Canonical submission rules for PlowTAK (and a good checklist for any new ATAK
plugin). Derived from TAK Product Center practice and
`TAK_GOV_SUBMISSION_INSTRUCTIONS_GENERIC.txt`.

Portal: https://tak.gov/user_builds (must be logged in).

## Goal

Upload a **small source zip** so TPP can run `./gradlew assembleCivRelease`,
inject the TPC keystore, and return a Load-able release APK.

| Expectation | Detail |
|-------------|--------|
| Size | Usually **100–800 KB** for a single plugin (source + wrapper). **Not** multi‑MB docs/screenshots. |
| Root folder | Exactly one top folder: `PlowTAK/…` — that name becomes the APK base name |
| Paths | POSIX `/` only (never PowerShell `Compress-Archive`) |
| Secrets | Never `local.properties`, never `.jks` / keystores |
| SDK | Never `app/libs/main.jar`, never `.takdev/` — TPP resolves the SDK |

## Preferred packager (this repo)

```powershell
cd T:\TAK\IdeaPlowPlugin
python .tmp-apk\build_tpp_zip.py
# → .tmp-apk\PlowTAK-user-build.zip
```

The script **allowlists** Gradle + `app/src` (+ tests) + `template.local.properties`
and **rejects** zips that still contain `docs/`, `.takdev/`, `libs/`, or secrets.

## What must be in the zip

Under `PlowTAK/`:

- `build.gradle`, `settings.gradle`, `gradle.properties`
- `gradlew`, `gradlew.bat`, `gradle/wrapper/**` (**including `gradle-wrapper.jar`**)
- `template.local.properties` (placeholders only)
- `README.md`
- `app/build.gradle`, `app/proguard-gradle.txt`
- `app/src/main/**` (manifest, java/kotlin, res, assets)
- `app/src/test/**` (optional but fine)

PlowTAK-specific (do not drop):

- `-applymapping <atak.proguard.mapping>` in `app/proguard-gradle.txt`
- `-repackageclasses atakplugin.PlowTAK`
- `com.atakmap.app.component` discovery activity in the manifest
- `assembleCivRelease` + `atak-gradle-takdev`

## What must stay out

- `docs/` (README hero / architecture PNGs blew our zip to ~9 MB)
- `.git/`, `.gradle/`, `.idea/`, `build/`, `app/build/`
- `.takdev/`, `app/libs/`
- `local.properties`, `*.jks`, `*.apk`
- `.cursor/`, `.tmp-apk/`, `Maps/`, `Releases/`

## Upload (Cursor browser, no file dialog)

1. Serve the zip: `python .tmp-apk\cors_server.py` (listens on `127.0.0.1:8765`)
2. On logged-in `user_builds`, fetch → `File` → assign `#user_build_upload_file` → Submit
3. Wait for **Success** (~15–25 min); download the result zip and publish a `tpc-*` GitHub Release

See [tpc-signing.md](tpc-signing.md) for signing details, failure history, and
device install.

## Verify before upload

```powershell
$z = ".tmp-apk\PlowTAK-user-build.zip"
(Get-Item $z).Length   # prefer < 1–2 MB
python -c "import zipfile; z=zipfile.ZipFile(r'$z'); n=z.namelist(); print(len(n)); print([x for x in n if not x.startswith('PlowTAK/')][:5]); print(any(x.endswith('gradle-wrapper.jar') for x in n)); print([x for x in n if 'docs/' in x or '.takdev' in x or '/libs/' in x][:5])"
```

## ATAK version targeting

Official TPC APKs are **version-matched** to the ATAK release they were built
against (`ext.ATAK_VERSION` in `app/build.gradle`). Fleet on 5.8 needs a 5.8
artifact; prefer `gov.tak.api.*` over obfuscated internals where possible.

## New plugin checklist

1. Root folder name = intended APK name (`settings.gradle` `rootProject.name`)
2. Lean allowlist zip (this doc), not “zip the whole repo”
3. `template.local.properties` with placeholders
4. ProGuard: plugin-specific `-repackageclasses` + real `-keep` package + ATAK `-applymapping`
5. Discovery activity present
6. Smoke-build the extracted zip locally before TPP if anything is new
