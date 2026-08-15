# Demo storm fleet (removed)

The in-plugin **Demo storm fleet** (Settings → Start/Stop demo) was removed from
PlowTAK. It may return later as a separate simulation tool.

## What it did

- Spawned ~30 synthetic `DemoPlow` units near GPS / self marker / map center.
- Geodesic motion by default; optional GraphHopper pack via `DemoRoadWalker`.
- Painted coverage, toggled status/hazards, and shared a Data Sync snapshot
  (`{hostUid}-demo-fleet.geojson` + demo coverage chunks). Demos did not send
  CoT PLI to the server.

## Recover from git

```text
git log -- app/src/main/java/com/atakmap/android/plowtak/demo/
git checkout <commit-before-removal> -- \
  app/src/main/java/com/atakmap/android/plowtak/demo/ \
  # plus Setup UI, PlowTakController.toggleDemoFleet, and MissionCoverageSync
  # demo upload/pull branches from that same commit
```

Key paths before removal:

- `app/src/main/java/com/atakmap/android/plowtak/demo/DemoFleetSimulator.kt`
- `app/src/main/java/com/atakmap/android/plowtak/demo/DemoRoadWalker.kt`
- Setup block in `panel_setup.xml` / `SetupPanel.kt`
- `PlowTakController.toggleDemoFleet` + `MissionCoverageSync` demo GeoJSON path
- `UnitStatusMissionCodec.encodeDemoFleet` / `decodeDemoFleet`
