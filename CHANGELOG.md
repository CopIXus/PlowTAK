# Changelog

All notable changes to PlowTAK will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Plugin versions use **yy.mmdd.HHmm** UTC (build timestamp), e.g. `26.0809.0935`.

## [Unreleased]

## [0.1.7] — 2026-08-15 (TPC `tpc-0.1.7`)

TPC job `amos-halava1-leo-gov-20260815-214930` →
`PlowTAK-26.0815.2149-ATAK-5.8.0-civ-release.apk`
([release](https://github.com/CopIXus/PlowTAK/releases/tag/tpc-0.1.7)).

### Added
- Freeform plow / wing / tow widths in Setup (feet; 0 = not fitted).
- Tow plow bar on the driver/HUD graphic when tow width is configured.

### Fixed
- Tasks screen crash (plugin strings resolved via plugin context).

## [0.1.6] — 2026-08-15 (TPC `tpc-0.1.6`)

TPC job `amos-halava1-leo-gov-20260815-205020` →
`PlowTAK-26.0815.2050-ATAK-5.8.0-civ-release.apk`
([release](https://github.com/CopIXus/PlowTAK/releases/tag/tpc-0.1.6)).

### Added
- Storm-level coverage colors/timers (cycle, P1–P3, retention; 0 = stay red).
- Mine-first Tasks screen with snooze and ops sync.

### Changed
- GIS priority in overlay/tasking; Supervisor cycle binds from active storm.

## [0.1.5] — 2026-08-12 (TPC `tpc-0.1.5`)

TPC job `amos-halava1-leo-gov-20260812-140635` →
`PlowTAK-26.0812.1406-ATAK-5.8.0-civ-release.apk`
([release](https://github.com/CopIXus/PlowTAK/releases/tag/tpc-0.1.5)).

### Fixed
- Coverage Data Sync ADD/REMOVE churn from gzip + `Content-Encoding` uploads;
  peers and CloudTAK now get stable plain `*-live.geojson`.
- Skip Data Sync local paint when a hazard/condition UID is already on the map.
- CIV release compile (`MapView` import).

### Changed
- Hazards: CoT broadcast plus mission association for CloudTAK Map Items.

## [0.1.4] — 2026-08-10 (TPC `tpc-0.1.4`)

TPC job `amos-halava1-leo-gov-20260810-123525` →
`PlowTAK-26.0810.1835-ATAK-5.8.0-civ-release.apk`
([release](https://github.com/CopIXus/PlowTAK/releases/tag/tpc-0.1.4)).

### Added
- Map HUD under menus (persisted setting); Storm as a full panel.
- Road-condition stale TTL (default 2h) with expired condition cleanup.
- Lean tak.gov submission notes (`docs/tak-gov-submission.md`).

### Changed
- Data Sync storm missions create/upload via Marti without requiring the Data
  Sync plugin on-device for PlowTAK’s path; mission delete removed from the
  plugin (admin-only on server).
- Hazard/condition peer labels; smaller Done/Save pills and UI polish.

### Fixed
- Duplicate Wet road-condition markers from unstable GeoJSON hashes.
- Mission create HTTP 500 when PUT had an empty JSON body.

### Added (earlier unreleased)
- **Storm catalog + join** — remote storms are heard but not auto-joined; each
  device picks which agency storm to report into (agency · designator · id).
- **Supervisor start dialog** — agency, storm designator, optional mission
  override, and per-device Data Sync server picker.
- **Per-user Data Sync server preference** (`plowtak.datasync.server`) — coverage
  uploads target the selected TAK server while a storm is joined.

### Changed
- Versioning is now **yy.mmdd.HHmm** UTC (e.g. `26.0809.0935`); APK files are
  named `PlowTAK-<version>-ATAK-5.8.0-<flavor>-<type>.apk`.
- GitHub Releases are no longer auto-published on every `main` push — publish
  manually after the TPC-signed APK is downloaded from tak.gov.
- ATAK toolbar / settings icon replaced with the plow-truck graphic
  (`ic_plowtak`).
- Release ProGuard restores HelloWorld `-applymapping <atak.proguard.mapping>`
  so TPC-signed APKs Load on obfuscated release ATAK (`IServiceController` →
  `gov.tak.api.plugin.a`).

### Fixed
- Third Party Pipeline (`tak.gov/user_builds`) build fixes so TPC can produce a
  Load-able release APK: skip local `main.jar` when takrepo is enabled; fall back
  to takdev `${buildDir}/android_keystore` when no local keystore is configured;
  set `bundle.storeArchive.enable = false` per the ATAK plugin template.
- Load failure on release ATAK-CIV when TPC minify left unmapped SDK plugin API
  names in the dex.

### Added
- **QuickPic hazard photos** — long-press a driver-panel hazard button to launch
  ATAK QuickPic; captured image attaches to the hazard marker for TAK sync
  (`QuickPicHazardCapture`).
- **Data Sync 5-minute coverage chunks** — while a storm is active, upload gzip
  GeoJSON to mission `plowtak-coverage-{stormId}` (`MissionCoverageSync` /
  `MissionCoverageCodec`); fail-open if Data Sync is unavailable.
- **Bluetooth equipment wiring** — optional paired plow/spreader controller
  (setup + Tool Preferences) feeds blade/salt via `BluetoothEquipmentProvider`.
- **Task GeoChat ping** — `publishTask` also sends a GeoChat message to the
  target contact when known (fail-open).
- **Durable outbound CoT queue** — disk-backed queue survives process death;
  local coverage re-queued for share on controller start.
- **`<__plowtak>` MarkerDetailHandler** — registers with CotDetailManager for
  marker detail round-trip.
- **ATAK Tool Preferences** — PlowTAK screen for TTS, direction-aware coverage,
  Bluetooth, and Data Sync info.
- **Supervisor route UI** — long-press fleet truck → Task / Assign route /
  Clear route; route id shown in the fleet list.

### Fixed
- Plugin discovery: add the required fictitious `com.atakmap.app.component`
  activity so ATAK 4.6.0.2+ can find and load the APK (was installing but not
  appearing under Plugins).
- CoT self-echo filter no longer uses bare `startsWith(selfUid)` (dropped peer
  units like `PLOWTAK-T-10` when self was `PLOWTAK-T-1`). Matching is exact PLI
  uid or `"$selfUid-"` derived events (`SelfCotFilter`).
- Publish/listen/record identity unified on `effectiveUid` (contractor CTR-* in
  an active storm).
- External CoT now uses `dispatchToBroadcast` per TAK “Sending CoT Messages”
  guidance (was bare `dispatch`).
- Route assignments wired end-to-end; hazard/condition details logged for export.
- ProGuard keeps aligned with ATAK-CIV 5.8 SDK sample (`IPlugin`,
  `AbstractPluginTool`, shift service, map component).

### Changed
- Docs: README + diagrams updated for shipped QuickPic, Data Sync, Bluetooth,
  durable queue, Tool Preferences, and supervisor route UI.
- Relicensed under **PlowTAK Free Application License 1.0** (CopIX LLC), matching
  WinTAKTracker: free to use, source available, do not sell the application.
  Prior MIT snapshots remain under MIT (see LICENSE §8).
- Added GitHub Actions CI (`coretests`) and Release workflow that builds a signed
  ATAK-CIV 5.8 plugin APK when ATAK SDK + keystore secrets are configured
  (`docs/ci-build.md`).
- **Renamed project from IdeaPlowPlugin / IdeaPlow to PlowTAK.** Package
  `com.atakmap.android.ideaplow` → `com.atakmap.android.plowtak`; CoT detail
  `<__ideaplow>` → `<__plowtak>`; event types `b-i-x-ideaplow-*` →
  `b-i-x-plowtak-*`; map group, prefs, export paths, ProGuard namespace, and APK
  base name updated accordingly. Kotlin types use the `PlowTak*` prefix.

### Added
- Phase 2 ops hardening:
  - Direction-aware coverage: bearing-binned opposite-direction detection
    (`coverage/DirectionModel`), side-of-road estimate from heading (shared in PLI
    `<geom side=>`), dashed "half-treated" rendering for one-way-only corridors.
  - Priority cycle times (P1/P2/P3 overrides) and supervisor special zones
    (bridge/ramp/hill/school, circle or polygon, cycle multipliers) shared over
    `b-i-x-plowtak-zone`; per-segment cycle resolution (`coverage/CycleResolver`)
    picks the strictest applicable cycle for freshness coloring.
  - Material selector (Salt / Sand / Brine / Pre-wet) carried per coverage segment
    (SegmentCodec v2, backward-compatible) and in PLI; driver width presets
    (standard / wing / tow) with live effective-width switching, configured in setup.
  - GPS quality gating (`coverage/GpsGate`): teleport-jump rejection and stationary
    jitter suppression; grid spatial index (`coverage/SegmentIndex`) and
    max-segment-count pruning for performance with large coverage stores.
  - Forgot-to-toggle sanity engine (`ops/ToggleSanity`): not-treating nudge,
    overspeed-with-blade confirm, treating-in-facility confirm — prompts only,
    surfaced as dialog + TTS, never auto-flips equipment.
  - Supervisor tasking (`ops/TaskManager` + `b-i-x-plowtak-task`): long-press a
    fleet truck to task it, nearest-truck suggestion, driver ACK/DECLINE buttons,
    escalation timer re-alerts the supervisor; GeoChat send is an SDK-fixup stub.
  - Hazard photo attachment field (camera capture is an SDK-fixup stub) and
    road-condition quick reports (bare/wet/slush/snow-covered/ice) as labeled
    stock markers with typed PlowTAK detail.
  - TTS voice alerts (task received, route overdue, distress nearby, sanity
    prompts) with settings toggle; black/amber night palette for the driver panel.
  - Post-storm export: records-grade GeoJSON + CSV (segments, alerts, conditions,
    reloads, shifts) via pure-Kotlin exporters; live supervisor metrics
    (`report/MetricsCalculator`): lane-miles treated/hour, % within cycle,
    reload counts per truck.
  - Optional GraphHopper road-snap (`coverage/RoadSnapper`): minimal pure-Kotlin
    read-only reader for GH 1.0 `nodes`/`edges`/`geometry` files with a tower-node
    grid index; setting default off, fail-open to raw GPS; verified against the
    Virginia pack.
  - Coretests expanded to cover all new framework-free logic.

- Phase 1 MVP storm tool:
  - Vehicle capability model (Plow / Salt only / Supervisor / Observer) with
    first-run setup flow and capability-gated Driver / Supervisor / Observer panels.
  - Coverage engine: treating-rule-driven swath recording (point thinning +
    Douglas-Peucker simplification), freshness model (green/yellow/red/expired vs
    cycle time), storm-scoped in-memory + flat-file coverage store.
  - CoT layer built on CotEvent/CotDetail (no string XML): periodic PLI with
    `<__plowtak>` detail, batched/thinned coverage sharing, distress alerts with
    ack/clear (`b-a-o-tbl`/`b-a-o-can`), storm session broadcasts, hazard drops;
    inbound coverage merge gated to treat-capable units only.
  - Map rendering: freshness-colored coverage polylines in MapGroup "PlowTAK",
    fleet markers with type/status color and stale grey-out, pulsing distress
    markers.
  - Ops: vehicle status enum with one-tap changes, supervisor-defined facility
    geofences with salt-dome reload logging and LOADING suggestions, operator
    shift login (vehicle vs operator identity), storm session start/end with
    fleet convergence.
  - Equipment abstraction: manual blade/salt provider wired to driver toggles;
    Bluetooth provider stub with settings placeholder.
  - Foreground shift service (doze survival) and best-effort offline CoT queue
    with flush-on-reconnect.
  - Standalone `coretests/` JVM harness: 65 JUnit tests covering the framework-free
    engine (runs without the ATAK SDK).

- Phase 0 scaffold: ATAK-CIV 5.8 plugin project (Kotlin, package `com.atakmap.android.plowtak`)
  with lifecycle, tool, map component, and placeholder drop-down UI.
- Repository skeleton: README, LICENSE (MIT), docs (CoT schema, operator guide),
  `.gitignore` policy excluding offline map packs and SDK jars.
