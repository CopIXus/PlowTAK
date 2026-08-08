# Changelog

All notable changes to IdeaPlowPlugin will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Phase 1 MVP storm tool:
  - Vehicle capability model (Plow / Salt only / Supervisor / Observer) with
    first-run setup flow and capability-gated Driver / Supervisor / Observer panels.
  - Coverage engine: treating-rule-driven swath recording (point thinning +
    Douglas-Peucker simplification), freshness model (green/yellow/red/expired vs
    cycle time), storm-scoped in-memory + flat-file coverage store.
  - CoT layer built on CotEvent/CotDetail (no string XML): periodic PLI with
    `<__ideaplow>` detail, batched/thinned coverage sharing, distress alerts with
    ack/clear (`b-a-o-tbl`/`b-a-o-can`), storm session broadcasts, hazard drops;
    inbound coverage merge gated to treat-capable units only.
  - Map rendering: freshness-colored coverage polylines in MapGroup "IdeaPlow",
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

- Phase 0 scaffold: ATAK-CIV 5.8 plugin project (Kotlin, package `com.atakmap.android.ideaplow`)
  with lifecycle, tool, map component, and placeholder drop-down UI.
- Repository skeleton: README, LICENSE (MIT), docs (CoT schema, operator guide),
  `.gitignore` policy excluding offline map packs and SDK jars.
