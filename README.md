# IdeaPlowPlugin

**IdeaPlow** is an ATAK plugin for winter road operations — snow plow fleet coverage
tracking over TAK Server.

Every plow publishes its live position and equipment state (blade down, spreader on).
Every other plow, supervisor, and observer on the same TAK Server sees **where roads
have been treated in the last X minutes**, which priority segments are overdue, plow
direction/side of road, and emergency (distress) alerts — with offline maps and
routing provided by the VNS plugin.

## Vehicle capability model

One APK, four per-device roles (chosen in settings on first run):

| Type | Who | Publishes PLI | Records coverage | Equipment toggles |
|------|-----|---------------|------------------|-------------------|
| **Plow** | Blade truck (optionally with spreader) | Yes | Yes, when treating | Blade; Salt if equipped |
| **SaltOnly** | Spreader / brine truck, no blade | Yes | Yes, when material on | Salt/material only |
| **Supervisor** | Ops lead driving or checking routes | Yes (non-treating) | No | None (inspection mode) |
| **Observer** | Responders, traffic control, EOC viewers | Optional | No | None (read-only) |

See [docs/ops-guide.md](docs/ops-guide.md) for the operator guide and
[docs/cot-schema.md](docs/cot-schema.md) for the `<__ideaplow>` CoT detail schema.

## Requirements

- **Host:** ATAK-CIV **5.8.x** on Android. Plugins are version-locked: an IdeaPlow APK
  built against the 5.8 SDK only loads in ATAK-CIV 5.8.
- **Peer dependency:** **VNS plugin 4.0** (Vehicle Navigation System) for offline
  basemaps/routing. Download it from [tak.gov](https://tak.gov) — the VNS APK is
  **not** distributed with this repository. IdeaPlow does not call private VNS APIs;
  it only relies on VNS for the operator's offline map/navigation experience.
- **TAK Server** connectivity for fleet sharing (IdeaPlow keeps recording offline and
  syncs when connectivity returns).

## Building

The ATAK SDK is required and is **not** included in this repository.

1. Download the **ATAK-CIV 5.8 SDK** from [tak.gov](https://tak.gov) (registration
   required). You need from it:
   - `main.jar` — the ATAK API (consumed as `compileOnly`)
   - `atak-gradle-takdev.jar` — the TAK dev Gradle plugin (offline fallback when no
     takrepo Maven access is configured)
2. Copy `local.properties.example` to `local.properties` and fill in the paths:
   - `sdk.dir` — Android SDK location
   - `sdk.path` — path to the extracted ATAK-CIV SDK (directory containing `main.jar`)
   - `takdev.plugin` — path to `atak-gradle-takdev.jar`
   - `takDebugKeyFile` / `takReleaseKeyFile` and related keystore keys (see the example
     file) for plugin signing
3. Build with JDK 17:

   ```powershell
   .\gradlew assembleCivDebug
   ```

   Release output follows the ATAK plugin naming convention:
   `ATAK-Plugin-IdeaPlowPlugin-<version>--5.8.0-civ-release.apk`

4. Install the APK on the device, then load it from ATAK's Plugins manager.

## Offline map packs

Development/test GraphHopper routing packs for **North Carolina**, **Tennessee**, and
**Virginia** (car profile, OSM ~2021 vintage) live in `Maps/` locally but are **not
tracked in git** (~489 MB). They are distributed as **GitHub Release assets** instead.

To install map packs on a device for use with VNS, see
[docs/vns-install.md](docs/vns-install.md).

> Production agencies should generate fresh GraphHopper extracts for their AOR rather
> than using the 2021-vintage dev packs.

## Roadmap

- **Phase 0 — Repo & scaffold** (this state): buildable ATAK 5.8 CIV plugin skeleton,
  docs, map pack policy.
- **Phase 1 — MVP storm tool:** vehicle capability profiles, capability-gated
  blade/salt UI, swath recording with plow width, freshness coloring vs cycle time,
  fleet + coverage CoT sharing, storm sessions, distress alerts, vehicle status +
  facility geofences, shift login, hazard drops, offline store.
- **Phase 2 — Ops hardening:** side/direction gap detection, priority cycle times and
  zones, GPS quality gating and track thinning, forgot-to-toggle nudges, tasking via
  GeoChat, records-grade export, optional GraphHopper road snap.
- **Phase 3 — Agency GIS + hardware:** lane/priority GIS import, Bluetooth
  EquipmentProvider for plow/spreader controllers, contractor onboarding, storm replay.

## License

[MIT](LICENSE) — © IdeaPlowPlugin contributors.
