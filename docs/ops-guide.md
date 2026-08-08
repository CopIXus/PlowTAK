# PlowTAK Operator Guide

PlowTAK is a winter-operations plugin for ATAK-CIV 5.8. One APK serves the whole
fleet; each device is configured with a **vehicle capability type** on first run that
determines what the operator sees and can do.

## 1. Getting started

- Install ATAK-CIV 5.8.x, the VNS 4.0 plugin (offline maps — see
  [vns-install.md](vns-install.md)), and the PlowTAK plugin APK from a GitHub Release.
- Connect the device to your agency's TAK Server.
- Open the PlowTAK toolbar tool. On first launch, pick your **vehicle type** and
  options (callsign, plow width presets, materials, contractor flag).
- Settings live in the plugin drop-down (**Vehicle setup…**), not under ATAK Tool
  Preferences.

## 2. Vehicle capability types

### 2.1 Plow (blade truck)

- Publishes live position (PLI) to the fleet.
- **Records coverage** while treating: blade down and/or salt on (per treat rule)
  paints a swath the width of the active preset, colored by freshness.
- Equipment toggles: **Blade Up/Down**; **Salt On/Off** if `hasSalt`.
- Material selector (salt / sand / brine / pre-wet) and width presets
  (standard / wing / tow).
- One-tap **distress**, hazard drops, and road-condition reports.

### 2.2 SaltOnly (spreader / brine truck)

- Publishes live position; **records coverage while material is on**.
- Equipment toggles: **Spreader On/Off** and material type — no blade control.
- Same distress / hazard / condition tools as Plow.

### 2.3 Supervisor (ops lead)

- Publishes presence (non-treating) — never paints coverage.
- Fleet list, overdue-segment view, alert acknowledge, **storm session start/end**,
  cycle-time / priority settings, special zones, tasking, route assignment, export,
  live metrics.

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

## 4. Distress alerts

One tap sends a Mayday with your location, vehicle type, and last equipment state to
the fleet. Supervisors see an alert list and map pulse. TTS can announce nearby
distress when enabled.

> Distress is a situational-awareness assist, **not** a substitute for radio/911 SOP.

## 5. Offline operation

PlowTAK keeps recording coverage with no connectivity. Outbound CoT is queued in
memory and flushed when a TAK server reconnects (see [cot-schema.md](cot-schema.md)
limitations). Coverage segments themselves persist on disk for the active storm.
VNS provides the offline basemap / GraphHopper packs.

## 6. Peer dependency — VNS

Road-snap (optional) reads GraphHopper packs from the VNS install path. See
[vns-install.md](vns-install.md). Only one VNS region is active at a time.
