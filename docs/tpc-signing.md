# TAK Product Center signing & Third Party Pipeline

Handoff notes for PlowTAK so work can continue from any machine that has this
repo (UNAS: `\\192.168.1.26\Working\TAK\IdeaPlowPlugin` or `T:\TAK\IdeaPlowPlugin`).

**Last verified:** 2026-08-09  
**Successful TPP job:** `amos-halava1-leo-gov-20260809-161048`  
  (APK `PlowTAK-26.0809.1611-ATAK-5.8.0-civ-release.apk`; prior good job
  `amos-halava1-leo-gov-20260809-092536`)  
**GitHub release:** https://github.com/CopIXus/PlowTAK/releases/tag/tpc-0.1.1  
**Git commit:** `da02649` (storm join / Data Sync picker + applymapping)

---

## Why this matters

Release / Play Store **ATAK-CIV 5.8** will install a CopIX-signed plugin APK but
refuse to **Load** it:

> The signature for the plugin is INVALID

API version (`com.atakmap.app@5.8.0.CIV`) can be fine while Load stays grayed
out. Only a **TAK Product Center (TPC)** third-party signature unlocks Load on
release ATAK.

GitHub Actions `build-0.1.x` APKs are CopIX-signed — OK for developer ATAK /
smoke tests, **not** for field devices on release ATAK.

---

## Trusted certificate (from successful APK)

```text
Signer DN:
  CN=TAK Product Center ATAK Untrusted Plugin Release,
  OU=Product Center, O=TAK, L=Fort Belvoir, ST=Virginia, C=US

SHA-256: F2:4A:38:05:72:75:FC:EC:F6:7B:E9:75:AB:80:3D:12:F7:5D:C2:35:81:BE:F6:9C:BA:9E:B0:3A:15:BB:8C:17
SHA-1:   A4:F9:6B:22:C3:C6:61:8C:F8:52:69:89:B9:5D:3F:09:1C:C0:0D:1C

Key: EC secp384r1 (384-bit), SHA256withECDSA
APK schemes: v2 + v3 (v1 JAR signing: false)
Valid: 2020-10-08 → 2050-10-01
```

Verify locally:

```powershell
apksigner verify --print-certs ATAK-Plugin-PlowTAK-0.1.0-5.8.0-civ-tpc-release.apk
```

---

## How TPP actually signs (no hidden second step)

On [tak.gov/user_builds](https://tak.gov/user_builds) the ephemeral builder:

1. Injects `takrepo.url=https://artifacts.tak.gov/artifactory/maven-release`
2. takdev resolves `com.atakmap.app.civ.release:keystore` (e.g. `keystore-5.8.0.1.jks`)
3. Copies it to `app/build/android_keystore`
4. Gradle `signingConfigs.release` signs APK/AAB with template credentials:
   - alias: `wintec_mapping`
   - store/key password: `tnttnt`

From a successful `build.log`:

```text
civRelease => Copied keystore from repository keystore-5.8.0.1.jks into
  /app/plugin-src/PlowTAK/app/build/android_keystore
...
> Task :app:packageCivRelease
> Task :app:assembleCivRelease
> Task :app:signCivReleaseBundle
```

The trusted signature **is** that Gradle release signing. There is no separate
post-process “magic” resign with a different private key after the build.

---

## Can we sign ourselves later?

**Yes — with tak.gov developer credentials pulling the keystore from artifactory.**  
**No — by committing or redistributing the `.jks` in this public GitHub repo.**

### Local / CI reproduction outline

1. In `local.properties` (never commit):

   ```properties
   takrepo.url=https://artifacts.tak.gov/artifactory/maven-release
   takrepo.user=<tak.gov username>
   takrepo.password=<tak.gov password>
   ```

2. Do **not** set `takReleaseKeyFile` (so signing falls back to
   `${buildDir}/android_keystore` after takdev copies the artifact).

3. `.\gradlew.bat :app:assembleCivRelease`

4. Confirm cert DN / SHA-256 match the TPC values above.

See also [ci-build.md](ci-build.md) § “TAK Product Center signing”.

---

## Submission zip requirements

Portal: https://tak.gov/user_builds (must be logged in).

| Requirement | PlowTAK status |
|-------------|----------------|
| Single root folder in zip; name becomes APK base name | `PlowTAK/` |
| Gradle + wrapper at that root | yes |
| `assembleCivRelease` defined | yes |
| `atak-gradle-takdev` used for SDK | yes (`3.+` when takrepo on) |
| `-repackageclasses atakplugin.PlowTAK` | yes (`app/proguard-gradle.txt`) |
| `-applymapping <atak.proguard.mapping>` | **required** — without it, release Load fails with `ClassNotFoundException: IServiceController` (SDK names never remapped to host `gov.tak.api.plugin.a`) |
| `com.atakmap.app.component` discovery activity | yes (`AndroidManifest.xml`) |
| `bundle { storeArchive { enable = false } }` | yes (`app/build.gradle`) |
| POSIX `/` paths in zip (not Windows `\`) | **required** — see below |

### Do not use PowerShell `Compress-Archive`

It stores **backslash** paths. Linux TPP unpack fails in ~2 seconds with no
useful download (“Sorry, the build file is not available.”).

Build the zip with Python `zipfile` (forward slashes) or WSL `zip -r`.

Example (from repo root on a Windows machine):

```powershell
# Stage under .tmp-apk/submit-root/PlowTAK (exclude .git, build, Maps, local.properties, *.jks, *.apk)
# Then:
python -c "import os,zipfile; from pathlib import Path; src=Path(r'.tmp-apk/submit-root/PlowTAK'); out=Path(r'.tmp-apk/PlowTAK-user-build.zip');
zf=zipfile.ZipFile(out,'w',zipfile.ZIP_DEFLATED);
[zf.writestr(zipfile.ZipInfo('PlowTAK/'+p.relative_to(src).as_posix(), date_time=p.stat().st_mtime_ns and __import__('time').localtime(p.stat().st_mtime)[:6]), p.read_bytes()) for p in src.rglob('*') if p.is_file()];
zf.close(); print(out, out.stat().st_size)"
```

(Prefer the fuller script used previously under `.tmp-apk/` that also sets
`gradlew` executable mode `0755`.)

Exclude: `.git`, `build/`, `.gradle/`, `Maps/`, `local.properties`, `*.jks`,
`*.keystore`, `*.apk`, `.tmp-apk/`.

---

## Build failures we already hit (and fixes)

| Attempt | Symptom | Root cause | Fix in repo |
|---------|---------|------------|-------------|
| 1 | Failed in ~2s, download empty | Windows `\` zip paths | Rebuild zip with POSIX `/` |
| 2 | `compileCivReleaseKotlin` — missing `atak-sdk/main.jar` | `compileOnly files(.../main.jar)` always on | Only when `!isDevKitEnabled()` |
| 3 | `packageCivRelease` — `SigningConfig "release" missing storeFile` | No keystore on TPP when local.properties absent | Fallback to `${buildDir}/android_keystore` + `tnttnt` / `wintec_mapping` |
| 4 | **Success** (signed) but Fold8 Load toast “Failed to load PlowTAK” | APK still referenced `IServiceController`; release ATAK only has obfuscated `gov.tak.api.plugin.a` | Add HelloWorld’s `-applymapping <atak.proguard.mapping>` to `app/proguard-gradle.txt` |
| 5 | **Success** | — | Also added `bundle.storeArchive.enable = false` |

Relevant files: `app/build.gradle` (signingConfigs, bundle block, dependencies), `app/proguard-gradle.txt` (`-applymapping`).

After a TPP build, verify the signed APK dex has **zero** `IServiceController` strings and a non-zero `Lgov/tak/api/plugin/a;` count before installing on release ATAK.

Failure zips from the portal contain `build.log` (Gradle) plus Fortify /
dependency-check reports — not a signed APK. Read `build.log` first.

---

## Published artifacts

### GitHub

- Tag / release: **`tpc-0.1.0`**
  https://github.com/CopIXus/PlowTAK/releases/tag/tpc-0.1.0
- Files:
  - `ATAK-Plugin-PlowTAK-0.1.0-5.8.0-civ-tpc-release.apk`
  - `ATAK-Plugin-PlowTAK-0.1.0-5.8.0-civ-tpc-release.apk.sha256`
  - `ATAK-Plugin-PlowTAK-0.1.0-5.8.0-civ-tpc-release.aab`
  - `ATAK-Plugin-PlowTAK-0.1.0-5.8.0-civ-tpc-release.aab.sha256`

APK SHA-256:

```text
3c6de684566a16e5f7a433b7ecb0a347a4fea231e5b583932f6d565993925122
```

### UNAS local copies (not in git)

On the share (gitignored APKs / working folder):

```text
Releases/tpc-0.1.0/
  ATAK-Plugin-PlowTAK-0.1.0-5.8.0-civ-tpc-release.apk
  ATAK-Plugin-PlowTAK-0.1.0-5.8.0-civ-tpc-release.apk.sha256
  ATAK-Plugin-PlowTAK-0.1.0-5.8.0-civ-tpc-release.aab
  ATAK-Plugin-PlowTAK-0.1.0-5.8.0-civ-tpc-release.aab.sha256
  NOTES.txt
  (optional) full TPP download zip / build.log
```

UNC: `\\192.168.1.26\Working\TAK\IdeaPlowPlugin\Releases\tpc-0.1.0\`

---

## Device install checklist

1. Uninstall any older CopIX-signed PlowTAK if present.
2. Install the **tpc-0.1.0** APK.
3. ATAK → Package Mgmt / Plugins → PlowTAK → **Load** (should enable).
4. Peer dependency: **VNS 4.0** from tak.gov for offline maps.

---

## Automation limits

There is **no supported public API** to upload to user_builds / poll / download.
Practical workflow:

1. CI or a script builds a clean `PlowTAK-user-build.zip` (POSIX paths).
2. Human uploads on tak.gov/user_builds (~15–25 min).
3. Download success zip → publish TPC APK to GitHub Release (`tpc-*` tags).

Do not scrape tak.gov sessions in Actions.

### Cursor browser: upload + save without dialogs

Native file-picker / Save-As dialogs cannot be driven reliably from automation.
Work around them with a local CORS bridge under `.tmp-apk/` (gitignored):

1. **Upload (no Choose File dialog)**  
   Serve the zip: `python .tmp-apk/cors_server.py` (`127.0.0.1:8765`).  
   On the logged-in `user_builds` page, `fetch` the zip, wrap it in a `File`,
   assign via `DataTransfer` to `#user_build_upload_file`, then click Submit.

2. **Download (no Save As dialog)**  
   Prefer clicking the Success **download** link while the Cursor browser has
   downloads configured not to prompt. The file often appears under
   `%USERPROFILE%\Downloads\` as a `*.tmp` that is already a valid zip — copy
   it to `.tmp-apk/<job-id>.zip`.

   Fallback if fetch works same-origin: run `python .tmp-apk/recv_server.py`
   (`127.0.0.1:8766`), `fetch` the download with `credentials: 'include'`, and
   `POST` the blob to localhost. Note: TPC download URLs currently **opaque-
   redirect** off tak.gov, so in-page `fetch` usually fails; the click→Downloads
   path is what works.

`Browser.setDownloadBehavior` is **denied** in this browser host — do not rely
on it.

---

## Related docs

| Doc | Role |
|-----|------|
| [ci-build.md](ci-build.md) | GitHub Actions + short TPC signing section |
| [ops-guide.md](ops-guide.md) | Operator usage |
| [cot-schema.md](cot-schema.md) | CoT detail schema |
| [../README.md](../README.md) | Install note: need TPC-signed APK for release ATAK |
| [../CHANGELOG.md](../CHANGELOG.md) | TPP gradle fixes under Unreleased |

---

## Quick resume on another computer

```powershell
# Map / open UNAS repo
cd T:\TAK\IdeaPlowPlugin   # or \\192.168.1.26\Working\TAK\IdeaPlowPlugin
git pull

# Read this file + ci-build.md
# Install from Releases\tpc-0.1.0\ or GitHub release tpc-0.1.0

# Next TPC submission after code changes:
# 1) rebuild POSIX zip of PlowTAK/
# 2) upload https://tak.gov/user_builds
# 3) on Success, publish new tpc-* GitHub Release + copy into Releases\
```
