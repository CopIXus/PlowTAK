# IdeaPlow CoT Detail Schema

IdeaPlow rides on standard TAK Cursor-on-Target (CoT) messages. Vehicle presence uses
ordinary PLI events (so non-IdeaPlow ATAK users still see markers); IdeaPlow semantics
are carried in a custom detail namespace `<__ideaplow>` inside the CoT `<detail>`
element.

Status: **draft** — Phase 0 documents the schema; Phase 1 implements the codec
(`cot/` package).

## Example

```xml
<event version="2.0" uid="IDEAPLOW-PLOW-12" type="a-f-G-E-V" ...>
  <point lat="36.1627" lon="-86.7816" hae="9999999.0" ce="4.5" le="9999999.0"/>
  <detail>
    <contact callsign="Plow-12"/>
    <__ideaplow>
      <vehicle type="plow" hasBlade="true" hasSalt="true" role="treating"/>
      <status blade="down" salt="on" material="salt" mode="treating"/>
      <geom plowWidthM="3.0" heading="87.2" side="right"/>
      <ops routeId="P1" priority="1" stormId="2026-01-15-A"/>
    </__ideaplow>
  </detail>
</event>
```

## Elements

### `<vehicle>` — what this unit is (capability profile)

| Attribute | Type | Values / notes |
|-----------|------|----------------|
| `type` | enum | `plow`, `saltonly`, `supervisor`, `observer` |
| `hasBlade` | bool | Plow blade fitted |
| `hasSalt` | bool | Spreader/material dispensing fitted |
| `role` | enum | Current high-level role: `treating`, `presence`, `viewer` |

Capability flags gate behavior on the receive side too: coverage merge **ignores**
PLI from `supervisor`/`observer` units — their positions never paint "treated" roads.

### `<status>` — current equipment / activity state

| Attribute | Type | Values / notes |
|-----------|------|----------------|
| `blade` | enum | `up`, `down`, `none` (unit has no blade) |
| `salt` | enum | `on`, `off`, `none` |
| `material` | enum | `salt`, `sand`, `brine`, `prewet` (v1.1) |
| `mode` | enum | `treating`, `deadhead`, `loading`, `refueling`, `on_break`, `out_of_service`, `off_duty` |

`mode="treating"` is derived from the configurable treat rule (blade down and/or
material on). Deadhead travel (blade up, salt off) must never paint coverage.

### `<geom>` — geometry hints for swath rendering

| Attribute | Type | Values / notes |
|-----------|------|----------------|
| `plowWidthM` | float | Effective treated width in meters (blade/wing/tow preset) |
| `heading` | float | Degrees true, 0–360 |
| `side` | enum | `left`, `right`, `unknown` — side-of-road estimate |

### `<ops>` — operational context

| Attribute | Type | Values / notes |
|-----------|------|----------------|
| `routeId` | string | Assigned route/beat identifier (optional) |
| `priority` | int | Route priority class (1 = highest) |
| `stormId` | string | Active storm session ID, e.g. `2026-01-15-A`; scopes coverage freshness |

## Coverage segments

Treated-road coverage is shared as batched/thinned track CoT (or custom detail
geometry) so all IdeaPlow clients converge on the same "last treated" picture.
Encoding details (point thinning, Douglas-Peucker, time buckets) are a Phase 1/2
concern — full-resolution tracks would flood TAK Server bandwidth.

Planned segment attributes: timestamp, vehicle UID, heading, side estimate, material
mode, plow width, storm ID.

## Distress alerts

One-tap distress ("Mayday / Need Assist") is sent as an emergency-type CoT with the
`<__ideaplow>` detail attached so receiving units can show vehicle type and last
equipment state. Acknowledge/clear workflow keeps storms from filling with stale SOS.
