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

## Phase 1 features (MVP storm tool)

- **First-run vehicle setup** — pick Plow / Salt only / Supervisor / Observer, then
  sub-options: plow width presets (8/10/12 ft, wing, tow), spreader checkbox,
  observer label, presence and distress toggles. Capabilities gate everything at
  runtime; the same APK serves all four roles.
- **Capability-gated panels** — drivers get oversized glove-friendly Blade/Salt
  toggles (only for equipped channels), one-tap statuses, hazard drops, and a
  distress button; supervisors get storm session start/stop, cycle-time setting,
  facility geofences, fleet list, and alert ack/clear; observers get a read-only
  fleet/alert view.
- **Swath recording** — while the configurable treating rule holds (blade down only /
  salt on only / either / both), GPS positions are recorded into treated-road
  segments carrying timestamp, heading, material mode, operator, and plow width.
  Points are thinned (min spacing + Douglas-Peucker) and GPS quality-gated.
- **Freshness coloring** — segments render in the "IdeaPlow" map group, stroke width
  from plow width, color by age vs the cycle-time setting: green (within cycle),
  yellow (due soon), red (overdue), dropped after the retention window.
- **Fleet + coverage CoT sharing** — periodic PLI with the `<__ideaplow>` detail
  (only when publishing presence), batched/thinned coverage events, and inbound
  merge that accepts paint **only from treat-capable units** — supervisor and
  observer positions never mark roads treated.
- **Storm sessions** — supervisor start/end broadcasts converge across the fleet;
  coverage is storm-scoped, giving "never treated this storm" semantics.
- **Distress alerts** — one-tap mayday as a 911-alert CoT with ack/clear workflow,
  pulsing map markers, and alert lists for supervisors/observers.
- **Vehicle status + facilities** — treating/deadhead derived automatically;
  loading/refueling/on-break/out-of-service set with one tap; entering a supervisor-
  defined salt-dome geofence suggests LOADING and logs a reload event (material-use
  proxy until spreader telemetry exists).
- **Shift login** — operator name/ID at shift start, attached to CoT and coverage
  records (vehicle ID and operator identity kept separate for records).
- **Hazard drops** — one-tap stranded vehicle / tree-wires down / abandoned car /
  drift-ice / damage markers shared to the fleet.
- **Offline continuity** — coverage persists locally per storm; outbound CoT queues
  when the TAK connection drops and flushes on reconnect (see schema doc for
  limitations); a foreground service keeps recording alive through Android doze
  while a shift is active.

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

- **Phase 0 — Repo & scaffold** (done): buildable ATAK 5.8 CIV plugin skeleton,
  docs, map pack policy.
- **Phase 1 — MVP storm tool** (this state): vehicle capability profiles,
  capability-gated blade/salt UI, swath recording with plow width, freshness
  coloring vs cycle time, fleet + coverage CoT sharing, storm sessions, distress
  alerts, vehicle status + facility geofences, shift login, hazard drops, offline
  store. Framework-free engine logic is unit-tested without the SDK
  (`.\gradlew.bat -p coretests test`).
- **Phase 2 — Ops hardening:** side/direction gap detection, priority cycle times and
  zones, GPS quality gating and track thinning, forgot-to-toggle nudges, tasking via
  GeoChat, records-grade export, optional GraphHopper road snap.
- **Phase 3 — Agency GIS + hardware:** lane/priority GIS import, Bluetooth
  EquipmentProvider for plow/spreader controllers, contractor onboarding, storm replay.

## License

[MIT](LICENSE) — © IdeaPlowPlugin contributors.
