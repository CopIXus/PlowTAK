# PlowTAK CI / GitHub Releases

GitHub Actions builds a signed **ATAK-CIV 5.8** plugin APK on every push to
`main` (and on `v*` tags) and publishes it as a GitHub Release.

## Workflows

| Workflow | Trigger | What it does |
|----------|---------|--------------|
| [`.github/workflows/ci.yml`](../.github/workflows/ci.yml) | push / PR to `main` | Runs framework-free engine tests (`coretests`) — no ATAK SDK required |
| [`.github/workflows/release.yml`](../.github/workflows/release.yml) | push to `main`, tags `v*` | Assembles `assembleCivRelease`, uploads artifact, creates GitHub Release |

Continuous build tags look like `build-0.1.<run_number>` (same pattern as
[AndroidTAKTracker](https://github.com/CopIXus/AndroidTAKTracker) /
[WinTAKTracker](https://github.com/CopIXus/WinTAKTracker)).

## Required repository secrets

The ATAK-CIV SDK is **not** redistributed in this repo (tak.gov license). CI
decodes the jars from secrets at build time.

| Secret | Contents |
|--------|----------|
| `ATAK_MAIN_JAR_BASE64` | Base64 of ATAK-CIV **5.8** `main.jar` |
| `ATAK_TAKDEV_JAR_BASE64` | Base64 of `atak-gradle-takdev.jar` from the same SDK |
| `ANDROID_KEYSTORE_BASE64` | Base64 of the release `.jks` / `.keystore` |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Key alias |
| `ANDROID_KEY_PASSWORD` | Key password |

You can reuse the same Android signing secrets as **AndroidTAKTracker**.

### Encoding the ATAK jars (once)

From a machine that has the extracted ATAK-CIV 5.8 SDK:

```powershell
# Windows PowerShell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("T:\path\to\main.jar")) | Set-Clipboard
# paste into: gh secret set ATAK_MAIN_JAR_BASE64 -R CopIXus/PlowTAK

[Convert]::ToBase64String([IO.File]::ReadAllBytes("T:\path\to\atak-gradle-takdev.jar")) | Set-Clipboard
# paste into: gh secret set ATAK_TAKDEV_JAR_BASE64 -R CopIXus/PlowTAK
```

```bash
# Linux / macOS
base64 -w0 main.jar | gh secret set ATAK_MAIN_JAR_BASE64 -R CopIXus/PlowTAK
base64 -w0 atak-gradle-takdev.jar | gh secret set ATAK_TAKDEV_JAR_BASE64 -R CopIXus/PlowTAK
```

Copy Android keystore secrets from AndroidTAKTracker (values are not readable via
the API — re-set them from your local keystore if needed):

```powershell
gh secret set ANDROID_KEYSTORE_BASE64 -R CopIXus/PlowTAK < keystore.b64.txt
gh secret set ANDROID_KEYSTORE_PASSWORD -R CopIXus/PlowTAK
gh secret set ANDROID_KEY_ALIAS -R CopIXus/PlowTAK
gh secret set ANDROID_KEY_PASSWORD -R CopIXus/PlowTAK
```

## Local parity

```powershell
copy local.properties.example local.properties
# set sdk.path, takdev.plugin, takReleaseKey*
.\gradlew.bat :app:assembleCivRelease
.\gradlew.bat -p coretests test
```

## Notes

- Plugin APKs are **version-locked** to ATAK-CIV 5.8.x.
- Do **not** commit `main.jar`, keystores, or `local.properties`.
- VNS and GraphHopper map packs are peer/runtime assets, not CI inputs.
