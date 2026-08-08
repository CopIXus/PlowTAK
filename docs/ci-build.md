# PlowTAK CI / GitHub Releases

GitHub Actions builds a signed **ATAK-CIV 5.8** plugin APK on every push to
`main` (and on `v*` tags / manual **Run workflow**) and publishes it as a
GitHub Release.

## Workflows

| Workflow | Trigger | What it does |
|----------|---------|--------------|
| [`.github/workflows/ci.yml`](../.github/workflows/ci.yml) | push / PR to `main` | Engine unit tests (`coretests`) — no ATAK SDK required |
| [`.github/workflows/release.yml`](../.github/workflows/release.yml) | push to `main`, tags `v*`, `workflow_dispatch` | Downloads SDK jars, assembles `assembleCivRelease`, publishes Release |

Continuous build tags look like `build-0.1.<run_number>`.

## Why a private SDK cache repo?

GitHub Actions **secrets are capped at 64 KB**. `main.jar` is ~33 MB, so it
cannot be stored as `ATAK_MAIN_JAR_BASE64`. Instead, CI downloads a slim zip
from a **private** org repo:

- [CopIXus/atak-civ-sdk-cache](https://github.com/CopIXus/atak-civ-sdk-cache) (private)
- Release tag `atak-civ-5.8.0.1` → asset `atak-civ-5.8.0.1-ci-jars.zip`
  (`main.jar` + `atak-gradle-takdev.jar` only)

Do **not** make that cache repo public — the ATAK SDK is distributed under
tak.gov terms and must not be redistributed.

## Required repository secrets (PlowTAK)

| Secret | Contents |
|--------|----------|
| `CI_SDK_REPO_TOKEN` | GitHub PAT (or `gh` oauth token) with `repo` scope that can read `atak-civ-sdk-cache` |
| `ATAK_SDK_CACHE_REPO` | `CopIXus/atak-civ-sdk-cache` |
| `ATAK_SDK_RELEASE_TAG` | `atak-civ-5.8.0.1` |
| `ANDROID_KEYSTORE_BASE64` | Base64 of the release `.jks` |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Key alias |
| `ANDROID_KEY_PASSWORD` | Key password |

Android signing secrets can match [AndroidTAKTracker](https://github.com/CopIXus/AndroidTAKTracker).

### Refreshing the SDK cache (new ATAK version)

1. Download `ATAK-CIV-x.y.z-SDK.zip` from [tak.gov](https://tak.gov) → ATAK-CIV →
   **Developer Resources**.
2. Zip just `main.jar` + `atak-gradle-takdev.jar`.
3. Publish a new release on `atak-civ-sdk-cache` and update
   `ATAK_SDK_RELEASE_TAG` on PlowTAK.

```powershell
$sdk = "T:\TAK\ATAK-CIV-5.8.0.1-SDK\ATAK-CIV-5.8.0.1-SDK"
Compress-Archive -Path "$sdk\main.jar","$sdk\atak-gradle-takdev.jar" `
  -DestinationPath "T:\TAK\atak-civ-5.8.0.1-ci-jars.zip" -Force
gh release create atak-civ-5.8.0.1 -R CopIXus/atak-civ-sdk-cache `
  --title "ATAK-CIV 5.8.0.1 CI jars" `
  "T:\TAK\atak-civ-5.8.0.1-ci-jars.zip#atak-civ-5.8.0.1-ci-jars.zip"
```

### Rotating `CI_SDK_REPO_TOKEN`

Prefer a fine-grained PAT limited to read contents/releases on
`CopIXus/atak-civ-sdk-cache`, then:

```powershell
gh secret set CI_SDK_REPO_TOKEN -R CopIXus/PlowTAK
```

## Local build

SDK is already extractable locally after a tak.gov download:

```powershell
# Example paths after Expand-Archive of ATAK-CIV-5.8.0.1-SDK.zip
copy local.properties.example local.properties
# set sdk.path / takdev.plugin to the extracted SDK folder
.\gradlew.bat :app:assembleCivRelease
.\gradlew.bat -p coretests test
```

## Notes

- Plugin APKs are **version-locked** to ATAK-CIV 5.8.x.
- Do **not** commit `main.jar`, keystores, or `local.properties`.
- VNS and GraphHopper map packs are peer/runtime assets, not CI inputs.
