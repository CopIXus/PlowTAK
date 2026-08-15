# PlowTAK CoT Detail Schema

PlowTAK rides on standard TAK Cursor-on-Target (CoT) messages. Vehicle presence uses
ordinary PLI events (so non-PlowTAK ATAK users still see markers); PlowTAK semantics
are carried in a custom detail namespace `<__plowtak>` inside the CoT `<detail>`
element.

Status: **implemented** (Phase 1 + Phase 2). Codecs live in `cot/codec/`
(framework-free, unit-tested) and are bridged to ATAK `CotDetail` objects by
`cot/CotDetailAdapter` — details are always built through the CotEvent/CotDetail API,
never string XML templating.

## Event types

| Event | CoT type | Detail payload |
|-------|----------|----------------|
| Vehicle PLI | `a-f-G-E-V-C` | `contact` + `remarks` (unit status label) |
| Coverage batch | `b-i-x-plowtak-cov` | `<coverage>` with `<segment>` children |
| Storm session | `b-i-x-plowtak-storm` | `<storm>` |
| Distress alert | `b-a-o-tbl` (911-alert convention) | `<alert>` |
| Distress clear | `b-a-o-can` | `<alert state="cleared">` |
| Hazard drop | per-hazard marker type (see below) | `<hazard>` |
| Special zone (Phase 2) | `b-i-x-plowtak-zone` | `<zone>` |
| Supervisor task (Phase 2) | `b-i-x-plowtak-task` | `<task>` |
| Road condition (Phase 2) | `b-m-p-s-m` (stock map point) | `<condition>` |
| Route assignment (Phase 3) | `b-i-x-plowtak-route` | `<routeAssign>` |

The `b-i-x-plowtak-*` types are non-marker "bits" types: stock ATAK clients ignore
them instead of rendering bogus markers, while every PlowTAK client converges on the
same coverage/storm picture.

Custom detail tags use the TAK double-underscore convention (`<__plowtak>`), matching
the ATAK Developer Guide “Custom CoT Details” guidance. Outbound fleet traffic uses
`CotMapComponent.getExternalDispatcher().dispatchToBroadcast(...)` so every configured
network output receives the event (see “Sending CoT Messages through ATAK”).

## PLI example

Self PLI is a stock `a-f-G-E-V-C` event with `contact` callsign and a `remarks`
element set to the current unit-status label (e.g. Driving / Loading). Blade /
spread / detailed status for peers still syncs via Data Sync `{uid}-status.json`.

```xml
<event version="2.0" uid="PLOWTAK-T-1042" type="a-f-G-E-V-C" how="m-g" ...>
  <point lat="36.1627" lon="-86.7816" hae="9999999.0" ce="9999999.0" le="9999999.0"/>
  <detail>
    <contact callsign="Plow-12"/>
    <remarks>Driving</remarks>
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
| `material` | enum | `salt`, `sand`, `brine`, `prewet` (driver-selected material) |
| `preset` | enum | `standard`, `wing`, `tow` — active width preset (Phase 2) |
| `mode` | enum | `treating`, `deadhead`, `loading`, `refueling`, `on_break`, `out_of_service`, `off_duty` |

`mode="treating"` is derived from the configurable treat rule (blade down only /
salt on only / either / both, evaluated against the capability profile). Deadhead
travel (blade up, salt off) never paints coverage. Manual statuses (loading etc.)
are sticky until the driver taps DRIVING.

### `<geom>` — geometry hints for swath rendering

| Attribute | Type | Values / notes |
|-----------|------|----------------|
| `plowWidthM` | float | Effective treated width in meters (follows the active preset) |
| `heading` | float | Degrees true, 0–360; omitted when unknown |
| `side` | enum | `right`, `left` — side of the corridor being painted, estimated from heading; emitted only while TREATING (Phase 2) |

### `<ops>` — operational context

| Attribute | Type | Values / notes |
|-----------|------|----------------|
| `stormId` | string | Active storm session ID; scopes coverage freshness |
| `routeId` | string | Assigned route/beat |
| `reloads` | int | Reloads logged this storm from salt-dome geofence entries (Phase 2); feeds supervisor live metrics |

Element omitted entirely when no storm session, no route assignment, and no reloads.

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
<event uid="PLOWTAK-T-1042-cov-1736951234000" type="b-i-x-plowtak-cov" ...>
  <detail>
    <__plowtak>
      <coverage stormId="2026-01-15-1736951234" count="2">
        <segment id="PLOWTAK-T-1042-1736951234000" uid="PLOWTAK-T-1042"
                 callsign="Plow-12" op="op-77" material="plow+salt" mat="salt"
                 widthM="3.0" start="1736951234000"
                 points="36.1627001,-86.7816002,0,87.2;36.1630000,-86.7810000,3000,;..."/>
      </coverage>
    </__plowtak>
  </detail>
</event>
```

- `points` format: `lat,lon,dtMs,heading;...` — `dtMs` is the offset from `start`
  (keeps payloads compact); `heading` is empty when unknown.
- `material`: `plow`, `salt`, `plow+salt`, `none` (treatment mode).
- `mat` (Phase 2): the specific dispensed material (`salt`, `sand`, `brine`,
  `prewet`) when the spreader was on; omitted otherwise. Older clients ignore it,
  and v2 receivers default it to absent when decoding v1 segments.
- Segments are recorded only while the treat rule holds; point streams are thinned
  on ingest (min 5 m spacing) and simplified on close (Douglas-Peucker, 2 m
  tolerance), so wire payloads stay small on TAK bandwidth.
- Receivers dedupe by segment `id` and drop segments from a different storm than
  their active session.

## Storm session

```xml
<__plowtak>
  <storm id="2026-01-15-1736951234" start="1736951234000" end="0" startedBy="Sup-1"/>
</__plowtak>
```

`end="0"` while active. Convergence without a server authority: clients adopt a
remote *active* session whose `start` is newer than their local one; an end
broadcast for the current id ends it everywhere. Coverage stores are re-scoped on
session change, giving "never treated **this storm**" semantics.

## Distress alerts

Sent as ATAK's 911-alert convention (`b-a-o-tbl`) so even non-PlowTAK clients raise
it; cleared with `b-a-o-can`. State transitions re-send under the same event uid
(`<vehicleUid>-distress`):

```xml
<__plowtak>
  <alert vehicleUid="PLOWTAK-T-1042" callsign="Plow-12" vehicleType="plow"
         state="active" handledBy="" blade="true" salt="false" time="1736951234000"/>
</__plowtak>
```

- `state`: `active` → `acked` (supervisor tap) → `cleared` (long-press, or sender
  cancels their own). Acked alerts hold steady yellow; cleared alerts disappear.
- A stale `active` re-send cannot resurrect an alert already cleared locally.

## Hazard drops

One-tap hazards use ordinary marker types so stock ATAK renders something sensible,
plus the PlowTAK detail for the specific kind:

| Hazard (`kind`) | Marker CoT type |
|-----------------|-----------------|
| `stranded` (stranded vehicle) | `a-n-G-E-V` |
| `tree_wires` (tree / wires down) | `b-m-p-s-m` |
| `abandoned` (abandoned car blocking) | `a-n-G-E-V` |
| `drift_ice` (drift / ice patch) | `b-m-p-s-m` |
| `damage` (sign/mailbox strike) | `b-m-p-s-m` |

```xml
<__plowtak>
  <hazard kind="tree_wires" reporterUid="PLOWTAK-T-1042" reporterCallsign="Plow-12"
          stormId="2026-01-15-1736951234" time="1736951234000"
          photo="hazard-1736951234000.jpg"/>
</__plowtak>
```

`photo` (Phase 2) is optional: the filename of an attached photo following ATAK's
attachment convention (camera-intent capture is an SDK-fixup stub pending plugin
ActivityResult plumbing). Typed `<hazard>` / `<condition>` details are decoded into
the storm export log; stock ATAK still renders the marker from the event type.

## Special zones (Phase 2)

Supervisor-defined bridge/ramp/hill/school zones with tighter cycle multipliers,
shared like facility geofences so freshness coloring converges fleet-wide. Removal
is a re-send of the same zone with `removed="true"`:

```xml
<event uid="plowtak-zone-zone-1736951234000" type="b-i-x-plowtak-zone" ...>
  <detail>
    <__plowtak>
      <zone id="zone-1736951234000" name="Miller Rd bridge" kind="bridge" mult="0.50"
            lat="36.1627000" lon="-86.7816000" radiusM="200.0" by="Sup-1"
            time="1736951234000"/>
    </__plowtak>
  </detail>
</event>
```

- `kind`: `bridge`, `ramp`, `hill`, `school`; `mult` is the cycle-time multiplier
  (0.5 = half the cycle time — stricter).
- Circle zones use `lat`/`lon`/`radiusM`; polygon zones add
  `poly="lat,lon;lat,lon;..."` (polygon wins for containment when present).
- Segments whose midpoint falls inside a zone are colored against the **stricter**
  of the zone-tightened and priority cycle times.

## Supervisor tasks (Phase 2)

Tasking rides `b-i-x-plowtak-task`. The task and every state transition
(ack/decline/cancel) re-send under the **same event uid** so all clients converge;
escalation timers are local bookkeeping and never ride the wire:

```xml
<event uid="plowtak-task-PLOWTAK-S-1-1736951234000" type="b-i-x-plowtak-task" ...>
  <detail>
    <__plowtak>
      <task target="PLOWTAK-T-1042" targetCallsign="Plow-12" by="Sup-1"
            kind="segment" ref="" desc="Treat the flagged stretch"
            state="pending" stateBy="" stateTime="1736951234000"
            time="1736951234000"/>
    </__plowtak>
  </detail>
</event>
```

- `kind`: `segment` (overdue segment) or `hazard`; `ref` optionally names the
  segment/hazard id.
- `state`: `pending` → `acked` / `declined` / `cancelled` (terminal states never
  regress to pending, regardless of clock skew).
- The event point is the task location; nearest-truck suggestion happens on the
  supervisor side before sending. A GeoChat message alongside the task is an
  SDK-fixup stub (`ChatManagerMapComponent`).

## Road conditions (Phase 2)

Quick driver reports (bare/wet/slush/snow-covered/ice) at the current position.
Uses a stock map-point type so every ATAK client renders a labeled marker (the
contact callsign carries the condition); PlowTAK clients also get the typed
detail:

```xml
<event uid="plowtak-cond-PLOWTAK-T-1042-1736951234000" type="b-m-p-s-m"
       how="h-g-i-g-o" ...>
  <detail>
    <contact callsign="Ice (Plow-12)"/>
    <remarks>Road Ice reported by Plow-12</remarks>
    <__plowtak>
      <condition state="ice" reporterUid="PLOWTAK-T-1042" reporterCallsign="Plow-12"
                 stormId="2026-01-15-1736951234" time="1736951234000"/>
    </__plowtak>
  </detail>
</event>
```

`state`: `bare`, `wet`, `slush`, `snow_covered`, `ice`.

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

## TAK Data Sync — mission coverage chunks

In addition to CoT coverage batches, each truck uploads a **live GeoJSON chunk** to a
TAK Server mission while a storm is active (`sync/MissionCoverageSync`):

- One mission per storm: `plowtak-coverage-{stormId}` (storm id sanitized for URLs).
- Filename: `{vehicleUid}-{yyyyMMddHH}-live.geojson.gz` (current UTC hour window).
- Replaced every **5 minutes** (and on storm start); prior content hash is DELETEd
  when the new upload succeeds. Last hash/filename persist in prefs.
- Endpoints (best-effort, fail-open): `PUT/GET /Marti/api/missions/{name}`,
  `PUT /Marti/sync/missionupload?hash=&filename=`, then mission `contents` associate.
- Encoding is framework-free (`MissionCoverageCodec`); HTTP uses ATAK
  `TakHttpClient2.GetHttpClient(baseUrl)` against the connected server's Marti API port.
