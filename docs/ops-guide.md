# PlowTAK Operator Guide

PlowTAK is a winter-operations plugin for ATAK-CIV 5.8. One APK serves the whole
fleet; each device is configured with a **vehicle capability type** on first run that
determines what the operator sees and can do.

## 1. Getting started

- Install ATAK-CIV 5.8.x, the VNS 4.0 plugin (offline maps — see
  [vns-install.md](vns-install.md)), and the PlowTAK plugin APK from a GitHub Release.
- In ATAK, open **Settings → Tool Preferences / Plugins**, enable **PlowTAK**, then
  restart ATAK if prompted. The PlowTAK icon should appear on the toolbar.
- Connect the device to your agency's TAK Server(s). Choose which server PlowTAK
  uses for Data Sync from the PlowTAK panel (one selected server per device).
- Open the PlowTAK toolbar tool. On first launch, pick your **vehicle type** and
  options (callsign, plow width presets, materials, contractor flag, optional
  Bluetooth equipment controller).
- Vehicle details live in the plugin drop-down (**Vehicle setup…**). Shared ops
  toggles (voice alerts, direction-aware coverage, Bluetooth on/off) also appear
  under **ATAK Tools → PlowTAK**.

## 2. Vehicle capability types

### 2.1 Plow (blade truck)

- Publishes live position (PLI) to the fleet.
- **Records coverage** while treating: blade down and/or salt on (per treat rule)
  paints a swath the width of the active preset, colored by freshness.
- Equipment toggles: **Blade Up/Down**; **Salt On/Off** if `hasSalt`. Optional
  Bluetooth controller can drive those states when enabled in setup.
- Material selector (salt / sand / brine / pre-wet) and width presets
  (standard / wing / tow).
- One-tap **distress**, hazard drops, and road-condition reports.
- **Long-press a hazard button** to capture a photo with ATAK **QuickPic**; the
  image attaches to the hazard marker for TAK attachment sync.

### 2.2 SaltOnly (spreader / brine truck)

- Publishes live position; **records coverage while material is on**.
- Equipment toggles: **Spreader On/Off** and material type — no blade control.
- Same distress / hazard / condition tools as Plow (including QuickPic long-press).

### 2.3 Supervisor (ops lead)

- Publishes presence (non-treating) — never paints coverage.
- Fleet list, overdue-segment view, alert acknowledge, **storm session start/end**,
  cycle-time / priority settings, special zones, tasking, route assignment, export,
  live metrics.
- **Long-press a fleet truck** → Task / Assign route / Clear route. Assigned route
  ids appear in the fleet list. Tasks also ping the target via GeoChat when the
  contact is known.

### 2.4 Observer (responders, traffic control, EOC)

- Optional presence marker (configurable).
- Sees live coverage, fleet status, hazards, and alerts — **no treat controls**.
- Observer labels (Fire / EMS / Traffic / EOC) set in vehicle setup.

## 3. Reading the coverage map

- **Green** — treated within the cycle time.
- **Yellow** — aging, due soon.
- **Red** — overdue, or never treated this storm.
- **Grey/transparent** — expired beyond the retention window.
- Dashed “half-treated” lines indicate only one travel direction has been painted.
- Special zones (bridge / ramp / hill / school) tighten the cycle for segments inside
  them; priority classes (P1/P2/P3) can override the default cycle.

## 4. Storms, agencies, and Data Sync

Multiple agencies may start concurrent storms on the same mesh. PlowTAK **does
not auto-join** remote storms — each device picks what it reports into.

### Supervisor — start a storm
1. Open PlowTAK → **Start storm session**.
2. Enter **Agency** (e.g. VDOT) and a **designator** (e.g. I-81 North).
3. Optional: **Mission override** (otherwise `plowtak-coverage-{stormId}`).
4. Confirm the **Data Sync server** (button in the dialog or on the panel).
   Uploads go to that one selected TAK server (not fan-out to every connection).

### Every role — join a storm
Use **Join / pick storm** (supervisor) or **Join storm** (driver / observer).
Heard storms appear as `Agency · Designator · id`. Leave a storm to stop
reporting without ending it for others.

### Coverage sharing
- **Live CoT** — thinned coverage batches (~20s) keep the mesh usable.
- **Data Sync** — while you have **joined** a storm, treat-capable units upload
  5-minute gzip GeoJSON chunks to that storm’s mission on the selected server.
  Late joiners pull those mission files for catch-up. Uploads are fail-open if
  Data Sync or the server is unavailable.

## 5. Distress alerts

One tap sends a Mayday with your location, vehicle type, and last equipment state to
the fleet. Supervisors see an alert list and map pulse. TTS can announce nearby
distress when enabled.

> Distress is a situational-awareness assist, **not** a substitute for radio/911 SOP.

## 6. Offline operation

PlowTAK keeps recording coverage with no connectivity. Outbound CoT is held in a
**durable on-disk queue** and flushed when a TAK server reconnects; after restart,
local coverage is re-queued for share (see [cot-schema.md](cot-schema.md)).
Coverage segments themselves persist on disk for the active storm. VNS provides the
offline basemap / GraphHopper packs.

## 7. Peer dependency — VNS

Road-snap (optional) reads GraphHopper packs from the VNS install path. See
[vns-install.md](vns-install.md). Only one VNS region is active at a time.
