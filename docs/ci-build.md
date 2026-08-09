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

## TAK Product Center signing (release ATAK Load)

Play Store / release ATAK-CIV rejects plugins signed only with a private CopIX
or Android debug keystore (“The signature for the plugin is INVALID”). A
Load-able CIV release APK must be signed with the **TAK Product Center**
third-party plugin key.

### What TPP actually does

On [tak.gov/user_builds](https://tak.gov/user_builds), takdev downloads Maven
artifact `com.atakmap.app.civ.release:keystore` (e.g. `keystore-5.8.0.1.jks`)
into `app/build/android_keystore`, then Gradle `signingConfigs.release` signs
the APK/AAB. The resulting cert is:

```text
CN=TAK Product Center ATAK Untrusted Plugin Release,
OU=Product Center, O=TAK, L=Fort Belvoir, ST=Virginia, C=US
SHA-256: F2:4A:38:05:72:75:FC:EC:F6:7B:E9:75:AB:80:3D:12:F7:5D:C2:35:81:BE:F6:9C:BA:9E:B0:3A:15:BB:8C:17
```

There is no separate post-build “magic” resign step beyond that keystore —
the trusted signature **is** the Gradle release signing with the takrepo
keystore (template alias/password `wintec_mapping` / `tnttnt`).

### Can we sign ourselves later?

**Yes, with tak.gov developer credentials** — not by copying a private key into
GitHub. Locally (or in CI with secrets):

1. Set `takrepo.url`, `takrepo.user`, `takrepo.password` in `local.properties`
   (or CI env) so `isDevKitEnabled()` is true.
2. Build `assembleCivRelease` **without** overriding `takReleaseKeyFile`, so
   signing falls back to `${buildDir}/android_keystore` after takdev copies the
   release keystore artifact.
3. Do **not** commit or redistributing that `.jks` — pull it from artifactory
   each build.

GitHub Actions today still produces a CopIX-signed APK (useful for smoke tests
against developer ATAK). For devices running **release** ATAK-CIV, publish the
TPC/user_builds APK (or a local takrepo-signed twin) on the GitHub Release.

Submission zip tips: use POSIX `/` paths inside the zip (not PowerShell
`Compress-Archive` backslashes); single root folder `PlowTAK/`.

Full handoff (failures, cert fingerprints, UNAS `Releases/` mirror, resume on
another PC): [tpc-signing.md](tpc-signing.md).

## Notes

- Plugin APKs are **version-locked** to ATAK-CIV 5.8.x.
- Do **not** commit `main.jar`, keystores, or `local.properties`.
- VNS and GraphHopper map packs are peer/runtime assets, not CI inputs.
