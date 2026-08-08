# PlowTAK

<p align="center">
  <img src="docs/images/plowtak-logo.png" alt="PlowTAK logo" width="220"/>
</p>

**PlowTAK** is an ATAK plugin for winter road operations — snow plow fleet coverage
tracking over TAK Server. Published by **CopIX**.

Every plow publishes its live position and equipment state (blade down, spreader on).
Every other plow, supervisor, and observer on the same TAK Server sees **where roads
have been treated in the last X minutes**, which priority segments are overdue, plow
direction/side of road, and emergency (distress) alerts — with offline maps and
routing provided by the VNS plugin.

![PlowTAK coverage map](docs/images/plowtak-coverage-map.png)

## Architecture

![PlowTAK architecture](docs/images/plowtak-architecture.png)

```mermaid
flowchart LR
  subgraph Device["Each ATAK device"]
    UI["Driver / Supervisor / Observer UI"]
    Ctrl["PlowTakController"]
    Swath["SwathBuilder + CoverageStore"]
    CotPub["PlowCotPublisher"]
    CotLis["PlowCotListener"]
    Overlay["CoverageOverlay + Fleet markers"]
    UI --> Ctrl
    Ctrl --> Swath
    Ctrl --> CotPub
    CotLis --> Ctrl
    Swath --> Overlay
    Ctrl --> Overlay
  end
  CotPub <-->|"CoT / TAK"| TAK["TAK Server"]
  CotLis <-->|"CoT / TAK"| TAK
  VNS["VNS plugin<br/>offline maps & routing"] -.-> Device
```

## Roles

One APK, four per-device roles (chosen in settings on first run):

![PlowTAK roles](docs/images/plowtak-roles.png)

| Type | Who | Publishes PLI | Records coverage | Equipment toggles |
|------|-----|---------------|------------------|-------------------|
| **Plow** | Blade truck (optionally with spreader) | Yes | Yes, when treating | Blade; Salt if equipped |
| **SaltOnly** | Spreader / brine truck, no blade | Yes | Yes, when material on | Salt/material only |
| **Supervisor** | Ops lead driving or checking routes | Yes (non-treating) | No | None (inspection mode) |
| **Observer** | Responders, traffic control, EOC viewers | Optional | No | None (read-only) |

## Data flow & Data Sync batching

Coverage and ops events ride standard TAK Cursor-on-Target with a `<__plowtak>`
detail namespace. Live PLI stays frequent; **coverage is batched and thinned** so the
mesh stays usable in a storm. The planned sharing architecture uses **Data Sync
5-minute chunks** (time-windowed coverage packages) for efficient fleet
convergence — that batching path is the target design even where full Data Sync
integration is still landing.

![PlowTAK Data Sync batching](docs/images/plowtak-datasync.png)

```mermaid
flowchart TB
  GPS["GPS + equipment state"] --> Gate["GpsGate / treat rule"]
  Gate -->|treating| Swath["SwathBuilder"]
  Swath --> Store["CoverageStore"]
  Store --> Local["Local storm persistence"]
  Store --> Batch["Coverage batch / thin<br/>(planned: Data Sync 5-min chunks)"]
  Batch --> Queue["OutboundCotQueue"]
  Queue --> TAK["TAK Server"]
  TAK --> In["PlowCotListener"]
  In --> Merge["Merge treat-capable paint only"]
  Merge --> Store
  Store --> Map["PlowTAK map group<br/>freshness colors"]
```

## Feature summary

- **Capability-gated UI** — glove-friendly Blade/Salt toggles, distress, hazards,
  road conditions; supervisor storm session, cycle times, zones, tasking, export;
  observer read-only fleet/alerts.
- **Swath recording** — treat-rule gated segments with heading, material, width;
  GPS quality gate, thinning, optional GraphHopper road-snap.
- **Freshness map** — "PlowTAK" map group colored by age vs cycle time (green /
  yellow / red), direction-aware half-treated rendering, priority/zone overrides.
- **Fleet CoT** — PLI with `<__plowtak>`, batched coverage (`b-i-x-plowtak-*`),
  storm sessions, distress (911-alert convention), tasks, zones, conditions.
- **Ops hardening** — forgot-to-toggle nudges, voice alerts, night palette, shift
  login, facility geofences, post-storm GeoJSON/CSV export, live metrics.
- **Offline continuity** — local coverage store + outbound queue through doze via
  foreground shift service; VNS for offline basemap/routing.

See [docs/ops-guide.md](docs/ops-guide.md) and
[docs/cot-schema.md](docs/cot-schema.md) (`<__plowtak>` schema).

## Requirements

- **Host:** ATAK-CIV **5.8.x** on Android. Plugins are version-locked: a PlowTAK APK
  built against the 5.8 SDK only loads in ATAK-CIV 5.8.
- **Peer dependency:** **VNS plugin 4.0** (Vehicle Navigation System) for offline
  basemaps/routing. Download from [tak.gov](https://tak.gov) — not redistributed
  here. PlowTAK does not call private VNS APIs.
- **TAK Server** connectivity for fleet sharing (PlowTAK keeps recording offline and
  syncs when connectivity returns).

## Building

The ATAK SDK is required and is **not** included in this repository.

1. Download the **ATAK-CIV 5.8 SDK** from [tak.gov](https://tak.gov) (registration
   required). You need:
   - `main.jar` — the ATAK API (`compileOnly`)
   - `atak-gradle-takdev.jar` — TAK dev Gradle plugin (offline fallback)
2. Copy `local.properties.example` to `local.properties` and set `sdk.dir`,
   `sdk.path`, `takdev.plugin`, and signing keystore keys (see the example file).
3. Build with JDK 17:

   ```powershell
   .\gradlew assembleCivDebug
   ```

   Release APK name:
   `ATAK-Plugin-PlowTAK-<version>--5.8.0-civ-release.apk`

4. Install the APK, then enable it in ATAK's Plugins manager.

Framework-free engine tests (no ATAK SDK):

```powershell
.\gradlew.bat -p coretests test
```

## Offline map packs

Dev GraphHopper packs for **North Carolina**, **Tennessee**, and **Virginia** live
in `Maps/` locally but are **not tracked in git** (~489 MB); they ship as **GitHub
Release assets**. Device install steps: [docs/vns-install.md](docs/vns-install.md).

> Production agencies should generate fresh GraphHopper extracts for their AOR.

## Docs

| Doc | Contents |
|-----|----------|
| [docs/ops-guide.md](docs/ops-guide.md) | Operator guide (roles, coverage colors, distress) |
| [docs/cot-schema.md](docs/cot-schema.md) | `<__plowtak>` CoT detail schema |
| [docs/vns-install.md](docs/vns-install.md) | VNS / GraphHopper pack install |
| [CHANGELOG.md](CHANGELOG.md) | Release history |

## License

[MIT](LICENSE) — © CopIX / PlowTAK contributors.
