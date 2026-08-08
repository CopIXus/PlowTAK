# IdeaPlow CoT Detail Schema

IdeaPlow rides on standard TAK Cursor-on-Target (CoT) messages. Vehicle presence uses
ordinary PLI events (so non-IdeaPlow ATAK users still see markers); IdeaPlow semantics
are carried in a custom detail namespace `<__ideaplow>` inside the CoT `<detail>`
element.

Status: **implemented** (Phase 1). Codecs live in `cot/codec/` (framework-free,
unit-tested) and are bridged to ATAK `CotDetail` objects by `cot/CotDetailAdapter` —
details are always built through the CotEvent/CotDetail API, never string XML
templating.

## Event types

| Event | CoT type | Detail payload |
|-------|----------|----------------|
| Vehicle PLI | `a-f-G-E-V-C` | `<vehicle>` `<status>` `<geom>` `<ops>` `<operator>` |
| Coverage batch | `b-i-x-ideaplow-cov` | `<coverage>` with `<segment>` children |
| Storm session | `b-i-x-ideaplow-storm` | `<storm>` |
| Distress alert | `b-a-o-tbl` (911-alert convention) | `<alert>` |
| Distress clear | `b-a-o-can` | `<alert state="cleared">` |
| Hazard drop | per-hazard marker type (see below) | `<hazard>` |

The `b-i-x-ideaplow-*` types are non-marker "bits" types: stock ATAK clients ignore
them instead of rendering bogus markers, while every IdeaPlow client converges on the
same coverage/storm picture.

## PLI example

```xml
<event version="2.0" uid="IDEAPLOW-T-1042" type="a-f-G-E-V-C" how="m-g" ...>
  <point lat="36.1627" lon="-86.7816" hae="9999999.0" ce="9999999.0" le="9999999.0"/>
  <detail>
    <contact callsign="Plow-12"/>
    <__ideaplow>
      <vehicle type="plow" hasBlade="true" hasSalt="true" canTreat="true" role="treating"/>
      <status blade="down" salt="on" material="salt" mode="treating"/>
      <geom plowWidthM="3.0" heading="87.2"/>
      <ops stormId="2026-01-15-1736951234"/>
      <operator id="op-77" name="J. Smith"/>
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
| `canTreat` | bool | Unit may paint coverage (treat types only) |
| `role` | enum | `treating`, `presence`, `viewer` (derived, for quick filtering) |

Capability flags gate behavior on the receive side too: coverage merge **ignores**
segments from units known to be `supervisor`/`observer` — their positions never paint
"treated" roads.

### `<status>` — current equipment / activity state

| Attribute | Type | Values / notes |
|-----------|------|----------------|
| `blade` | enum | `up`, `down`, `none` (unit has no blade) |
| `salt` | enum | `on`, `off`, `none` |
| `material` | enum | `salt`, `sand`, `brine`, `prewet` |
| `mode` | enum | `treating`, `deadhead`, `loading`, `refueling`, `on_break`, `out_of_service`, `off_duty` |

`mode="treating"` is derived from the configurable treat rule (blade down only /
salt on only / either / both, evaluated against the capability profile). Deadhead
travel (blade up, salt off) never paints coverage. Manual statuses (loading etc.)
are sticky until the driver taps DRIVING.

### `<geom>` — geometry hints for swath rendering

| Attribute | Type | Values / notes |
|-----------|------|----------------|
| `plowWidthM` | float | Effective treated width in meters (blade/wing/tow preset) |
| `heading` | float | Degrees true, 0–360; omitted when unknown |

Side-of-road estimation (`side` attribute) is Phase 2.

### `<ops>` — operational context

| Attribute | Type | Values / notes |
|-----------|------|----------------|
| `stormId` | string | Active storm session ID; scopes coverage freshness |
| `routeId` | string | Assigned route/beat (Phase 2 tasking) |

Element omitted entirely when no storm session and no route assignment.

### `<operator>` — who is behind the wheel (shift login)

| Attribute | Type | Values / notes |
|-----------|------|----------------|
| `id` | string | Operator ID entered at shift start |
| `name` | string | Operator display name |

The vehicle ID (persistent per truck, in the event uid) and operator (per shift) are
deliberately separate — storms run 24/7 with crew swaps and records need both.
Element omitted when off shift.

## Coverage segments

Treated-road coverage is shared as batched, thinned segments (max 8 segments per
event, max 60 points per segment on the wire — re-simplified harder when needed):

```xml
<event uid="IDEAPLOW-T-1042-cov-1736951234000" type="b-i-x-ideaplow-cov" ...>
  <detail>
    <__ideaplow>
      <coverage stormId="2026-01-15-1736951234" count="2">
        <segment id="IDEAPLOW-T-1042-1736951234000" uid="IDEAPLOW-T-1042"
                 callsign="Plow-12" op="op-77" material="plow+salt" widthM="3.0"
                 start="1736951234000"
                 points="36.1627001,-86.7816002,0,87.2;36.1630000,-86.7810000,3000,;..."/>
      </coverage>
    </__ideaplow>
  </detail>
</event>
```

- `points` format: `lat,lon,dtMs,heading;...` — `dtMs` is the offset from `start`
  (keeps payloads compact); `heading` is empty when unknown.
- `material`: `plow`, `salt`, `plow+salt`, `none`.
- Segments are recorded only while the treat rule holds; point streams are thinned
  on ingest (min 5 m spacing) and simplified on close (Douglas-Peucker, 2 m
  tolerance), so wire payloads stay small on TAK bandwidth.
- Receivers dedupe by segment `id` and drop segments from a different storm than
  their active session.

## Storm session

```xml
<__ideaplow>
  <storm id="2026-01-15-1736951234" start="1736951234000" end="0" startedBy="Sup-1"/>
</__ideaplow>
```

`end="0"` while active. Convergence without a server authority: clients adopt a
remote *active* session whose `start` is newer than their local one; an end
broadcast for the current id ends it everywhere. Coverage stores are re-scoped on
session change, giving "never treated **this storm**" semantics.

## Distress alerts

Sent as ATAK's 911-alert convention (`b-a-o-tbl`) so even non-IdeaPlow clients raise
it; cleared with `b-a-o-can`. State transitions re-send under the same event uid
(`<vehicleUid>-distress`):

```xml
<__ideaplow>
  <alert vehicleUid="IDEAPLOW-T-1042" callsign="Plow-12" vehicleType="plow"
         state="active" handledBy="" blade="true" salt="false" time="1736951234000"/>
</__ideaplow>
```

- `state`: `active` → `acked` (supervisor tap) → `cleared` (long-press, or sender
  cancels their own). Acked alerts hold steady yellow; cleared alerts disappear.
- A stale `active` re-send cannot resurrect an alert already cleared locally.

## Hazard drops

One-tap hazards use ordinary marker types so stock ATAK renders something sensible,
plus the IdeaPlow detail for the specific kind:

| Hazard (`kind`) | Marker CoT type |
|-----------------|-----------------|
| `stranded` (stranded vehicle) | `a-n-G-E-V` |
| `tree_wires` (tree / wires down) | `b-m-p-s-m` |
| `abandoned` (abandoned car blocking) | `a-n-G-E-V` |
| `drift_ice` (drift / ice patch) | `b-m-p-s-m` |
| `damage` (sign/mailbox strike) | `b-m-p-s-m` |

```xml
<__ideaplow>
  <hazard kind="tree_wires" reporterUid="IDEAPLOW-T-1042" reporterCallsign="Plow-12"
          stormId="2026-01-15-1736951234" time="1736951234000"/>
</__ideaplow>
```

## Offline queue — documented limitations

Outbound events are dispatched internally immediately (local map always current) and
externally through a best-effort queue (`cot/OutboundCotQueue`):

- Queueing triggers when no configured TAK server reports connected; events flush on
  reconnect. PLI events supersede each other in the queue (only the newest per uid
  is kept); coverage/alert/storm events queue individually (bounded at 500).
- The queue is **in-memory only**: events pending at process death are lost. Coverage
  itself is safe — segments persist in the storm-scoped `CoverageStore` file and
  remain available locally; re-share after restart is a Phase 2 enhancement.
- Server connectivity detection uses the CotMapComponent server status surface and
  is optimistic when undetectable (ATAK's own comms layer also buffers briefly).
