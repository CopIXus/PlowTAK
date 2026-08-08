# PlowTAK Operator Guide (skeleton)

> Status: **skeleton** — Phase 0. Sections will be filled in as Phase 1 features land.

PlowTAK is a winter-operations plugin for ATAK-CIV 5.8. One APK serves the whole
fleet; each device is configured with a **vehicle capability type** on first run that
determines what the operator sees and can do.

## 1. Getting started

- Install ATAK-CIV 5.8.x, the VNS 4.0 plugin (offline maps — see
  [vns-install.md](vns-install.md)), and the PlowTAK plugin APK.
- Connect the device to your agency's TAK Server.
- On first launch, pick your **vehicle type** and sub-options (callsign, plow width,
  materials carried).

## 2. Vehicle capability types

### 2.1 Plow (blade truck)

For trucks with a plow blade, optionally also carrying salt/material.

- Publishes live position (PLI) to the fleet.
- **Records coverage** while treating: blade down (and/or salt on, per the configured
  treat rule) paints a swath the width of your blade, colored by freshness.
- Equipment toggles: **Blade Up/Down**; **Salt On/Off** if the truck is configured
  with `hasSalt`.
- Driver treating panel with oversized toggles; one-tap **distress** button.
- _TODO (Phase 1): screenshots, toggle walkthrough, status states (loading/refueling),
  hazard drops._

### 2.2 SaltOnly (spreader / brine truck)

For material trucks with no blade.

- Publishes live position; **records coverage while material is on**.
- Equipment toggles: **Spreader On/Off** and material type only — no blade control.
- Driver treating panel + distress, same as Plow.
- _TODO (Phase 1): material type selection (salt / sand / brine / pre-wet)._

### 2.3 Supervisor (ops lead)

For supervisors driving or checking routes.

- Publishes presence (non-treating) — driving a route **never paints coverage**, even
  with perfect GPS.
- No equipment panel (inspection mode).
- Extra UI: fleet list, overdue-segment map, alert list with acknowledge, **storm
  session start/end**, cycle-time settings.
- _TODO (Phase 1/2): storm session workflow, cycle-time configuration, tasking._

### 2.4 Observer (responders, traffic control, EOC)

Read-only situational awareness for units outside the plow fleet.

- Optional presence marker (configurable; can be receive-only).
- Sees live coverage, fleet status, hazards, and alerts — **no treat controls**, never
  alters coverage.
- _TODO (Phase 1): observer labels (Fire / EMS / Traffic / EOC)._

## 3. Reading the coverage map

- **Green** — treated within the cycle time.
- **Yellow** — aging, due soon.
- **Red** — overdue, or never treated this storm.
- **Grey/transparent** — expired beyond the retention window.

_TODO (Phase 1): cycle times per priority class, direction/side-of-road display._

## 4. Distress alerts

One tap sends a Mayday with your location, vehicle type, and last equipment state to
the fleet. Supervisors see an alert list and map pulse.

> Distress is a situational-awareness assist, **not** a substitute for radio/911 SOP.

## 5. Offline operation

PlowTAK keeps recording coverage with no connectivity and syncs to TAK Server when
the connection returns. VNS provides the offline basemap.

_TODO (Phase 1): local store behavior, storage limits._
