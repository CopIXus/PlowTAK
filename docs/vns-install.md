# VNS Offline Routing — Graph Pack Install Guide

**Status:** based on static analysis of the VNS 4.0 APK (`ATAK-Plugin-vns-4.0-ac524e2c-5.8.0-civ-release.apk`, decompiled 2026-08-08). All device paths below were read directly from the plugin's decompiled code, **but on-device verification is still pending** — no physical Android device was available for this pass.

## What VNS is

The **Vehicle Navigation System (VNS)** plugin (by PAR Government / partech) adds vehicle routing to ATAK's route planning:

- Three routing engines: **Google Routing** (needs API key), **Offline Routing** (bundled GraphHopper), and **Private Routing Server**.
- Route creation snapped to roadways, conversion of manual routes, audio/visual turn cues, on-the-fly re-routing, and "Quick Nav" one-tap routing to pre-set safe points.
- Offline routing uses **GraphHopper 1.0** (built 2020-05-22, commit `d852592`) compiled directly into the plugin dex — car profile with Contraction Hierarchies (CH).

This APK targets **ATAK-CIV 5.8.0**. IdeaPlow treats VNS as a peer dependency: we install map packs into VNS's folder, we do not call its (obfuscated, private) APIs.

## Where to get VNS

Download from [tak.gov](https://tak.gov) (TAK Product Center → ATAK plugins). Install the APK matching your ATAK version exactly (`...-5.8.0-civ-release.apk` for ATAK-CIV 5.8.x), then enable it in ATAK under Settings → Tool Preferences → Plugin manager.

Do **not** redistribute the APK in this repo.

## On-device storage layout (confirmed from decompiled code)

VNS builds its paths from ATAK's `FileSystemUtils.getItem("tools")`, i.e. the ATAK data root (normally `/storage/emulated/0/atak`):

| Purpose | Path |
|---|---|
| **GraphHopper region packs** | `/storage/emulated/0/atak/tools/VNS/GH/<region-name>/` |
| Temp / staging | `/storage/emulated/0/atak/tools/VNS/tmp/` |
| Region export output | `/storage/emulated/0/atak/tools/VNS/GH-export/` |
| Active region preference key | `vns_gh_active_region` (ATAK shared prefs) |

Decompiled source (paths verbatim):

```java
h = new File(FileSystemUtils.getItem("tools"), "VNS").getAbsolutePath();
i = new File(FileSystemUtils.getItem("tools"), "VNS/GH").getAbsolutePath();
j = new File(FileSystemUtils.getItem("tools"), "VNS/tmp").getAbsolutePath();
k = new File(FileSystemUtils.getItem("tools"), "VNS/GH-export").getAbsolutePath();
```

Casing matters: **`VNS/GH`**, uppercase.

### How VNS discovers regions

At startup the region manager (`AbstractRegionManager`) lists `atak/tools/VNS/GH/` and registers **every non-empty subdirectory** as a local region (region name = folder name). No manifest or registration step is needed — a plain folder copy works. It also:

- reads the `*.poly` file inside the region folder to draw the white **Offline Routing Regions** boundary outline in Overlay Manager;
- parses the region `timestamp` file for the data date shown in the manager UI.

### Four ways a pack can arrive on the device

1. **Manual sideload (what we do below):** copy a graph folder into `atak/tools/VNS/GH/`.
2. **ATAK Import Manager:** VNS registers a `GHZImportResolver` for **`.ghz`** files (a ZIP of the graph files at the archive root, no wrapper folder). ATAK sorts the file into `tools/VNS/GH`, VNS unzips it into `GH/<filename-without-extension>/` and deletes the `.ghz`.
3. **Data package from another device:** the Region Manager's **Send** button zips a region and ships it as a TAK data package.
4. **Server download:** Additional Tools → VNS → **Manage Offline Areas** lists regions from a preconfigured private routing server (HTTP endpoint `<server>/files/ghz_1.0`) and downloads/extracts them via a background service.

## Installing the three example state packs

The repo's example packs live in `Maps/north-carolina`, `Maps/tennessee`, `Maps/virginia`. Each folder already has the exact layout VNS expects (GraphHopper flat files: `properties`, `nodes`, `edges`, `geometry`, `location_index`, `nodes_ch_car`, `shortcuts_car`, `string_index_keys`, `string_index_vals`, plus `<name>.poly`, `<name>.kml`, `timestamp`).

### Option A — adb (recommended)

With the device connected and USB debugging enabled, from the repo root:

```powershell
adb shell mkdir -p /sdcard/atak/tools/VNS/GH
adb push Maps/north-carolina /sdcard/atak/tools/VNS/GH/north-carolina
adb push Maps/tennessee      /sdcard/atak/tools/VNS/GH/tennessee
adb push Maps/virginia       /sdcard/atak/tools/VNS/GH/virginia
```

(~430 MB total; a USB-2 push takes a few minutes.)

Then restart ATAK (VNS scans the folder at plugin startup). Verify:

- Overlay Manager → **Offline Routing Regions** shows a white outline named after each folder.
- Additional Tools → VNS → **Manage Offline Areas** lists the three regions as local (Send/Delete icons).
- Routes → + → routing method **Offline Routing** → the data-set picker offers the regions.

### Option B — file manager / MTP

Copy each state folder (the folder itself, not its contents) into `atak/tools/VNS/GH/` on internal storage using any file manager or USB file transfer. Create `tools/VNS/GH` if VNS hasn't been opened yet. Restart ATAK.

### Option C — `.ghz` via ATAK import

Zip the *contents* of a state folder (files at ZIP root), rename to `north-carolina.ghz`, copy it anywhere on the device, and use ATAK Import Manager (or tap it in a file browser). VNS extracts it to `GH/north-carolina/` and deletes the archive. Useful for distributing packs to many devices via data packages.

## Compatibility: our packs vs VNS 4.0

VNS 4.0 embeds **GraphHopper 1.0**. GraphHopper refuses to load a graph whose storage version numbers differ from the library's compile-time constants. Comparison:

| Storage component | Pack (`properties`) | GraphHopper 1.0 in VNS | Match |
|---|---|---|---|
| nodes | 5 | `VERSION_NODE = 5` | ✅ |
| edges | 15 | `VERSION_EDGE = 15` | ✅ |
| geometry | 4 | `VERSION_GEOMETRY = 4` | ✅ |
| location_index | 3 | `VERSION_LOCATION_IDX = 3` | ✅ |
| string_index | 5 | `VERSION_STRING_IDX = 5` | ✅ |
| shortcuts | 6 | `VERSION_SHORTCUT = 6` | ✅ |
| car flag encoder | `car|...|version=2` | `CarFlagEncoder.getVersion() = 2` | ✅ |

Additional positives:

- Packs are CH-prepared for the car profile (`prepare.ch.done=true`, `graph.ch.profiles=[car]`, `nodes_ch_car` + `shortcuts_car` present) — matches VNS's CH-based offline engine.
- The `string_index_*` file naming is specific to the GraphHopper 1.0 line, so the packs were almost certainly built with GraphHopper 1.0 tooling — the same line VNS bundles.

**Verdict: the NC/TN/VA packs should load in VNS 4.0.** Residual risks, to be confirmed on device:

- GraphHopper also validates per-encoded-value hash versions (`graph.encoded_values=...|version=<hash>`) and the profile hash (`graph.profiles.car.version`). These match only if the pack was built with the same encoded-value defaults as GH 1.0; the file layout strongly suggests it was, but only an on-device load proves it.
- Strings in the plugin (`multiple_regions_not_supported_message`, single `vns_gh_active_region` pref) indicate **only one region is active for routing at a time** — you can store all three states, but pick the data set per route, and a route probably cannot span two packs.
- A `no_startpoint_region_data_message` string implies the route start point must fall inside the selected region's boundary.

## Data-vintage caveat

The packs were imported **2021-08-17** from OSM data dated **2021-07-13**. Fine for development and demos; production agencies should rebuild from fresh OSM extracts **using GraphHopper 1.0 tooling** (newer GraphHopper versions changed the storage format and will produce packs VNS 4.0 cannot read).

## Still pending (needs a device)

- Confirm the three packs actually load and route (encoded-value hash check).
- Confirm single-active-region behavior and cross-border routing limits.
- Confirm the exact Region Manager UX for locally sideloaded folders on ATAK 5.8.
