package com.atakmap.android.plowtak.sync

import com.atakmap.android.plowtak.gis.MiniJson
import com.atakmap.android.plowtak.model.Material
import com.atakmap.android.plowtak.model.PlowVehicle
import com.atakmap.android.plowtak.model.VehicleStatus
import com.atakmap.android.plowtak.model.VehicleType

/**
 * Per-unit (or demo-host) status payload for Data Sync.
 * Real-unit location still rides CoT PLI; this file carries blade / spread /
 * status / heading for peers. Demo hosts publish a FeatureCollection of all
 * synthetic units (positions included — demos never send CoT to the server).
 */
object UnitStatusMissionCodec {

    fun statusFilename(vehicleUid: String): String =
        "${sanitizeUid(vehicleUid)}-status.json"

    fun demoFilename(hostUid: String): String =
        "${sanitizeUid(hostUid)}-demo-fleet.geojson"

    fun encodeStatus(vehicle: PlowVehicle, stormId: String): ByteArray {
        val json = buildString {
            append('{')
            append("\"uid\":").append(q(vehicle.uid)).append(',')
            append("\"callsign\":").append(q(vehicle.callsign)).append(',')
            append("\"type\":").append(q(vehicle.type.wireName)).append(',')
            append("\"status\":").append(q(vehicle.status.wireName)).append(',')
            append("\"lat\":").append(vehicle.lat).append(',')
            append("\"lon\":").append(vehicle.lon).append(',')
            append("\"headingDeg\":").append(vehicle.headingDeg).append(',')
            append("\"lastUpdateMs\":").append(vehicle.lastUpdateMs).append(',')
            append("\"hasBlade\":").append(vehicle.hasBlade).append(',')
            append("\"hasSalt\":").append(vehicle.hasSalt).append(',')
            append("\"bladeDown\":").append(vehicle.bladeDown).append(',')
            append("\"saltOn\":").append(vehicle.saltOn).append(',')
            append("\"stormId\":").append(q(stormId.ifEmpty { vehicle.stormId })).append(',')
            append("\"operatorId\":").append(q(vehicle.operatorId)).append(',')
            append("\"operatorName\":").append(q(vehicle.operatorName)).append(',')
            append("\"reloadCount\":").append(vehicle.reloadCount).append(',')
            append("\"demo\":false")
            append('}')
        }
        return json.toByteArray(Charsets.UTF_8)
    }

    fun decodeStatus(bytes: ByteArray): PlowVehicle? {
        val map = MiniJson.parseObject(bytes.toString(Charsets.UTF_8)) ?: return null
        return vehicleFromMap(map, demo = false)
    }

    fun encodeDemoFleet(stormId: String, hostUid: String, units: List<PlowVehicle>): ByteArray {
        val now = System.currentTimeMillis()
        val sb = StringBuilder()
        sb.append("{\"type\":\"FeatureCollection\",")
        sb.append("\"properties\":{")
        sb.append("\"stormId\":").append(q(stormId)).append(',')
        sb.append("\"hostUid\":").append(q(hostUid)).append(',')
        sb.append("\"generatedAtMs\":").append(now)
        sb.append("},\"features\":[")
        units.forEachIndexed { i, u ->
            if (i > 0) sb.append(',')
            sb.append("{\"type\":\"Feature\",\"geometry\":{")
            sb.append("\"type\":\"Point\",\"coordinates\":[")
            sb.append(u.lon).append(',').append(u.lat).append("]},")
            sb.append("\"properties\":{")
            sb.append("\"uid\":").append(q(u.uid)).append(',')
            sb.append("\"callsign\":").append(q(u.callsign)).append(',')
            sb.append("\"type\":").append(q(u.type.wireName)).append(',')
            sb.append("\"status\":").append(q(u.status.wireName)).append(',')
            sb.append("\"headingDeg\":").append(u.headingDeg).append(',')
            sb.append("\"lastUpdateMs\":").append(u.lastUpdateMs).append(',')
            sb.append("\"hasBlade\":").append(u.hasBlade).append(',')
            sb.append("\"hasSalt\":").append(u.hasSalt).append(',')
            sb.append("\"bladeDown\":").append(u.bladeDown).append(',')
            sb.append("\"saltOn\":").append(u.saltOn).append(',')
            sb.append("\"stormId\":").append(q(u.stormId.ifEmpty { stormId })).append(',')
            sb.append("\"operatorId\":").append(q(u.operatorId)).append(',')
            sb.append("\"operatorName\":").append(q(u.operatorName)).append(',')
            sb.append("\"reloadCount\":").append(u.reloadCount).append(',')
            sb.append("\"demo\":true")
            sb.append("}}")
        }
        sb.append("]}")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    fun decodeDemoFleet(bytes: ByteArray): List<PlowVehicle> {
        val root = MiniJson.parseObject(bytes.toString(Charsets.UTF_8)) ?: return emptyList()
        val features = MiniJson.array(root["features"]) ?: return emptyList()
        val out = ArrayList<PlowVehicle>()
        for (f in features) {
            val feat = MiniJson.obj(f) ?: continue
            val props = MiniJson.obj(feat["properties"]) ?: continue
            val geom = MiniJson.obj(feat["geometry"])
            val coords = MiniJson.array(geom?.get("coordinates"))
            val lon = MiniJson.double(coords?.getOrNull(0)) ?: continue
            val lat = MiniJson.double(coords?.getOrNull(1)) ?: continue
            val merged = props.toMutableMap()
            merged["lat"] = lat
            merged["lon"] = lon
            vehicleFromMap(merged, demo = true)?.let { out.add(it) }
        }
        return out
    }

    private fun vehicleFromMap(map: Map<String, Any?>, demo: Boolean): PlowVehicle? {
        val uid = MiniJson.string(map["uid"]) ?: return null
        val lat = MiniJson.double(map["lat"]) ?: return null
        val lon = MiniJson.double(map["lon"]) ?: return null
        return PlowVehicle(
            uid = uid,
            callsign = MiniJson.string(map["callsign"]) ?: uid,
            type = VehicleType.fromWireName(MiniJson.string(map["type"])) ?: VehicleType.PLOW,
            status = VehicleStatus.fromWireName(MiniJson.string(map["status"]))
                ?: VehicleStatus.DEADHEAD,
            lat = lat,
            lon = lon,
            headingDeg = MiniJson.double(map["headingDeg"]) ?: Double.NaN,
            lastUpdateMs = (map["lastUpdateMs"] as? Number)?.toLong()
                ?: System.currentTimeMillis(),
            hasBlade = MiniJson.bool(map["hasBlade"]) ?: false,
            hasSalt = MiniJson.bool(map["hasSalt"]) ?: false,
            bladeDown = MiniJson.bool(map["bladeDown"]) ?: false,
            saltOn = MiniJson.bool(map["saltOn"]) ?: false,
            stormId = MiniJson.string(map["stormId"]) ?: "",
            operatorId = MiniJson.string(map["operatorId"]) ?: if (demo) "demo" else "",
            operatorName = MiniJson.string(map["operatorName"]) ?: "",
            reloadCount = MiniJson.int(map["reloadCount"]) ?: 0
        )
    }

    private fun sanitizeUid(uid: String): String =
        uid.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "unknown" }

    private fun q(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
