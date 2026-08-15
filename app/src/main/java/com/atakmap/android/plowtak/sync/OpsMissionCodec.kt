package com.atakmap.android.plowtak.sync

import com.atakmap.android.plowtak.gis.MiniJson
import com.atakmap.android.plowtak.model.SpecialZone
import com.atakmap.android.plowtak.model.TaskEvent
import com.atakmap.android.plowtak.model.TaskKind
import com.atakmap.android.plowtak.model.TaskState
import com.atakmap.android.plowtak.model.ZoneType
import com.atakmap.android.plowtak.ops.RouteAssignment

/**
 * Per-unit ops snapshot for Data Sync: route assignments, zones, tasks,
 * and tasking snoozes this device authored or currently holds. Peers merge
 * on pull.
 */
object OpsMissionCodec {

    fun filename(vehicleUid: String): String =
        "${sanitizeUid(vehicleUid)}-ops.json"

    fun encode(
        stormId: String,
        routes: List<RouteAssignment>,
        zones: List<SpecialZone>,
        tasks: List<TaskEvent>,
        snoozes: Map<String, Long> = emptyMap()
    ): ByteArray {
        val sb = StringBuilder()
        sb.append('{')
        sb.append("\"stormId\":").append(q(stormId)).append(',')
        sb.append("\"routes\":[")
        routes.forEachIndexed { i, r ->
            if (i > 0) sb.append(',')
            sb.append('{')
            sb.append("\"vehicleUid\":").append(q(r.vehicleUid)).append(',')
            sb.append("\"callsign\":").append(q(r.callsign)).append(',')
            sb.append("\"routeId\":").append(q(r.routeId)).append(',')
            sb.append("\"source\":").append(q(r.source.wireName)).append(',')
            sb.append("\"assignedBy\":").append(q(r.assignedBy)).append(',')
            sb.append("\"timeMs\":").append(r.timeMs)
            sb.append('}')
        }
        sb.append("],\"zones\":[")
        zones.forEachIndexed { i, z ->
            if (i > 0) sb.append(',')
            sb.append('{')
            sb.append("\"id\":").append(q(z.id)).append(',')
            sb.append("\"name\":").append(q(z.name)).append(',')
            sb.append("\"type\":").append(q(z.type.wireName)).append(',')
            sb.append("\"cycleMultiplier\":").append(z.cycleMultiplier).append(',')
            sb.append("\"centerLat\":").append(z.centerLat).append(',')
            sb.append("\"centerLon\":").append(z.centerLon).append(',')
            sb.append("\"radiusM\":").append(z.radiusM)
            sb.append('}')
        }
        sb.append("],\"tasks\":[")
        tasks.forEachIndexed { i, t ->
            if (i > 0) sb.append(',')
            sb.append('{')
            sb.append("\"uid\":").append(q(t.uid)).append(',')
            sb.append("\"kind\":").append(q(t.kind.wireName)).append(',')
            sb.append("\"state\":").append(q(t.state.wireName)).append(',')
            sb.append("\"description\":").append(q(t.description)).append(',')
            sb.append("\"refId\":").append(q(t.refId)).append(',')
            sb.append("\"targetVehicleUid\":").append(q(t.targetVehicleUid)).append(',')
            sb.append("\"targetCallsign\":").append(q(t.targetCallsign)).append(',')
            sb.append("\"assignedBy\":").append(q(t.assignedBy)).append(',')
            sb.append("\"lat\":").append(t.lat).append(',')
            sb.append("\"lon\":").append(t.lon).append(',')
            sb.append("\"timeMs\":").append(t.timeMs).append(',')
            sb.append("\"stateTimeMs\":").append(t.stateTimeMs).append(',')
            sb.append("\"stateBy\":").append(q(t.stateBy))
            sb.append('}')
        }
        sb.append("],\"snoozes\":[")
        snoozes.entries.forEachIndexed { i, (id, dueByMs) ->
            if (i > 0) sb.append(',')
            sb.append('{')
            sb.append("\"id\":").append(q(id)).append(',')
            sb.append("\"dueByMs\":").append(dueByMs)
            sb.append('}')
        }
        sb.append("]}")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    data class Snapshot(
        val stormId: String,
        val routes: List<RouteAssignment>,
        val zones: List<SpecialZone>,
        val tasks: List<TaskEvent>,
        val snoozes: Map<String, Long> = emptyMap()
    )

    fun decode(bytes: ByteArray): Snapshot? {
        val map = MiniJson.parseObject(bytes.toString(Charsets.UTF_8)) ?: return null
        val stormId = MiniJson.string(map["stormId"]) ?: ""
        val routes = ArrayList<RouteAssignment>()
        for (item in MiniJson.array(map["routes"]).orEmpty()) {
            val o = MiniJson.obj(item) ?: continue
            val vehicleUid = MiniJson.string(o["vehicleUid"]) ?: continue
            val source = RouteAssignment.Source.fromWireName(MiniJson.string(o["source"]))
                ?: RouteAssignment.Source.GIS
            routes.add(
                RouteAssignment(
                    vehicleUid = vehicleUid,
                    callsign = MiniJson.string(o["callsign"]) ?: "",
                    routeId = MiniJson.string(o["routeId"]) ?: "",
                    source = source,
                    assignedBy = MiniJson.string(o["assignedBy"]) ?: "",
                    timeMs = (o["timeMs"] as? Number)?.toLong() ?: 0L
                )
            )
        }
        val zones = ArrayList<SpecialZone>()
        for (item in MiniJson.array(map["zones"]).orEmpty()) {
            val o = MiniJson.obj(item) ?: continue
            val id = MiniJson.string(o["id"]) ?: continue
            val type = ZoneType.fromWireName(MiniJson.string(o["type"])) ?: continue
            val centerLat = MiniJson.double(o["centerLat"]) ?: continue
            val centerLon = MiniJson.double(o["centerLon"]) ?: continue
            zones.add(
                SpecialZone(
                    id = id,
                    name = MiniJson.string(o["name"]) ?: "",
                    type = type,
                    cycleMultiplier = MiniJson.double(o["cycleMultiplier"]) ?: type.defaultMultiplier,
                    centerLat = centerLat,
                    centerLon = centerLon,
                    radiusM = MiniJson.double(o["radiusM"]) ?: 0.0
                )
            )
        }
        val tasks = ArrayList<TaskEvent>()
        for (item in MiniJson.array(map["tasks"]).orEmpty()) {
            val o = MiniJson.obj(item) ?: continue
            val uid = MiniJson.string(o["uid"]) ?: continue
            val kind = TaskKind.fromWireName(MiniJson.string(o["kind"])) ?: continue
            val state = TaskState.fromWireName(MiniJson.string(o["state"])) ?: TaskState.PENDING
            val timeMs = (o["timeMs"] as? Number)?.toLong() ?: 0L
            tasks.add(
                TaskEvent(
                    uid = uid,
                    targetVehicleUid = MiniJson.string(o["targetVehicleUid"]) ?: "",
                    targetCallsign = MiniJson.string(o["targetCallsign"]) ?: "",
                    assignedBy = MiniJson.string(o["assignedBy"]) ?: "",
                    kind = kind,
                    refId = MiniJson.string(o["refId"]) ?: "",
                    lat = MiniJson.double(o["lat"]) ?: 0.0,
                    lon = MiniJson.double(o["lon"]) ?: 0.0,
                    description = MiniJson.string(o["description"]) ?: "",
                    timeMs = timeMs,
                    state = state,
                    stateTimeMs = (o["stateTimeMs"] as? Number)?.toLong() ?: timeMs,
                    stateBy = MiniJson.string(o["stateBy"]) ?: ""
                )
            )
        }
        val snoozes = LinkedHashMap<String, Long>()
        for (item in MiniJson.array(map["snoozes"]).orEmpty()) {
            val o = MiniJson.obj(item) ?: continue
            val id = MiniJson.string(o["id"]) ?: continue
            val due = (o["dueByMs"] as? Number)?.toLong() ?: continue
            snoozes[id] = due
        }
        return Snapshot(stormId, routes, zones, tasks, snoozes)
    }

    private fun sanitizeUid(uid: String): String =
        uid.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "unknown" }

    private fun q(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
