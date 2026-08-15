package com.atakmap.android.plowtak.sync

import com.atakmap.android.plowtak.gis.MiniJson
import com.atakmap.android.plowtak.model.PlowVehicle
import com.atakmap.android.plowtak.model.VehicleStatus
import com.atakmap.android.plowtak.model.VehicleType

/**
 * Per-unit status payload for Data Sync.
 * Real-unit location still rides CoT PLI; this file carries blade / spread /
 * status / heading for peers.
 */
object UnitStatusMissionCodec {

    fun statusFilename(vehicleUid: String): String =
        "${sanitizeUid(vehicleUid)}-status.json"

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
        return vehicleFromMap(map)
    }

    private fun vehicleFromMap(map: Map<String, Any?>): PlowVehicle? {
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
            operatorId = MiniJson.string(map["operatorId"]) ?: "",
            operatorName = MiniJson.string(map["operatorName"]) ?: "",
            reloadCount = MiniJson.int(map["reloadCount"]) ?: 0
        )
    }

    private fun sanitizeUid(uid: String): String =
        uid.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "unknown" }

    private fun q(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
