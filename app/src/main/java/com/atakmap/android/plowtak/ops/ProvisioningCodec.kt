package com.atakmap.android.plowtak.ops

import com.atakmap.android.plowtak.coverage.CycleTimes
import com.atakmap.android.plowtak.gis.MiniJson
import com.atakmap.android.plowtak.model.Facility
import com.atakmap.android.plowtak.model.FacilityType
import com.atakmap.android.plowtak.model.SpecialZone
import com.atakmap.android.plowtak.model.VehicleCapability
import com.atakmap.android.plowtak.model.VehicleType
import com.atakmap.android.plowtak.model.ZoneType
import java.util.Locale

/**
 * JSON codec for [ProvisioningProfile]. Format (`"plowtak-provisioning"`
 * marker + version guard against unrelated JSON):
 *
 * ```json
 * {
 *   "format": "plowtak-provisioning",
 *   "version": 1,
 *   "name": "City of X winter ops",
 *   "agency": "City of X DPW",
 *   "created": 1700000000000,
 *   "capability": {
 *     "type": "plow", "hasBlade": true, "hasSalt": true,
 *     "canSendDistress": true, "publishPresence": true,
 *     "plowWidthM": 3.7, "wingWidthM": 4.9, "towWidthM": 0,
 *     "callsign": "CTR-Plow", "vehicleId": "", "contractor": true
 *   },
 *   "cycleTimes": { "default": 45, "p1": 30, "p2": 45, "p3": 90 },
 *   "coverageRetentionHours": 0,
 *   "roadConditionTtlMinutes": 120,
 *   "facilities": [
 *     { "id": "f1", "name": "Dome", "type": "salt_dome",
 *       "lat": 40.1, "lon": -83.1, "radiusM": 80 }
 *   ],
 *   "zones": [
 *     { "id": "z1", "name": "High St bridge", "type": "bridge",
 *       "multiplier": 0.5, "lat": 40.2, "lon": -83.2, "radiusM": 120,
 *       "polygon": [[40.2, -83.2], [40.21, -83.2], [40.21, -83.19]] }
 *   ]
 * }
 * ```
 *
 * Every section is optional; unknown keys are ignored; malformed entries
 * inside lists are skipped (a bad zone shouldn't kill facility import).
 * Capabilities go through [VehicleCapability.sanitize] on decode so a
 * hand-edited file can't produce an illegal combination.
 */
object ProvisioningCodec {

    const val FORMAT = "plowtak-provisioning"
    const val VERSION = 1

    /** File extension used for exports / recognized on import. */
    const val FILE_EXTENSION = "ipprov.json"

    // ------------------------------------------------------------ decode

    /** Parse a provisioning JSON document; null if it isn't one. */
    fun decode(json: String): ProvisioningProfile? {
        val root = MiniJson.parseObject(json) ?: return null
        if (MiniJson.string(root["format"]) != FORMAT) return null
        val version = MiniJson.int(root["version"]) ?: return null
        if (version < 1 || version > VERSION) return null

        return ProvisioningProfile(
            name = MiniJson.string(root["name"]) ?: "",
            agency = MiniJson.string(root["agency"]) ?: "",
            createdMs = MiniJson.double(root["created"])?.toLong() ?: 0L,
            capability = MiniJson.obj(root["capability"])?.let { decodeCapability(it) },
            cycleTimes = MiniJson.obj(root["cycleTimes"])?.let { decodeCycles(it) },
            coverageRetentionHours = MiniJson.double(root["coverageRetentionHours"]),
            roadConditionTtlMinutes = MiniJson.int(root["roadConditionTtlMinutes"]),
            facilities = MiniJson.array(root["facilities"])
                ?.mapNotNull { MiniJson.obj(it)?.let { o -> decodeFacility(o) } }
                ?: emptyList(),
            zones = MiniJson.array(root["zones"])
                ?.mapNotNull { MiniJson.obj(it)?.let { o -> decodeZone(o) } }
                ?: emptyList()
        )
    }

    private fun decodeCapability(o: Map<String, Any?>): VehicleCapability? {
        val type = VehicleType.fromWireName(MiniJson.string(o["type"])) ?: return null
        val defaults = VehicleCapability.defaultsFor(type)
        return VehicleCapability.sanitize(
            defaults.copy(
                hasBlade = MiniJson.bool(o["hasBlade"]) ?: defaults.hasBlade,
                hasSalt = MiniJson.bool(o["hasSalt"]) ?: defaults.hasSalt,
                canSendDistress = MiniJson.bool(o["canSendDistress"])
                    ?: defaults.canSendDistress,
                publishPresence = MiniJson.bool(o["publishPresence"])
                    ?: defaults.publishPresence,
                plowWidthM = MiniJson.double(o["plowWidthM"]) ?: defaults.plowWidthM,
                wingWidthM = MiniJson.double(o["wingWidthM"]) ?: defaults.wingWidthM,
                towWidthM = MiniJson.double(o["towWidthM"]) ?: defaults.towWidthM,
                callsign = MiniJson.string(o["callsign"]) ?: "",
                vehicleId = MiniJson.string(o["vehicleId"]) ?: "",
                observerLabel = MiniJson.string(o["observerLabel"]) ?: "",
                contractor = MiniJson.bool(o["contractor"]) ?: false
            )
        )
    }

    private fun decodeCycles(o: Map<String, Any?>): CycleTimes = CycleTimes(
        defaultMinutes = MiniJson.int(o["default"]) ?: 45,
        p1Minutes = MiniJson.int(o["p1"]) ?: 0,
        p2Minutes = MiniJson.int(o["p2"]) ?: 0,
        p3Minutes = MiniJson.int(o["p3"]) ?: 0
    )

    private fun decodeFacility(o: Map<String, Any?>): Facility? {
        val type = FacilityType.fromWireName(MiniJson.string(o["type"])) ?: return null
        val lat = MiniJson.double(o["lat"]) ?: return null
        val lon = MiniJson.double(o["lon"]) ?: return null
        return Facility(
            id = MiniJson.string(o["id"]) ?: return null,
            name = MiniJson.string(o["name"]) ?: "",
            type = type,
            lat = lat,
            lon = lon,
            radiusM = MiniJson.double(o["radiusM"]) ?: 50.0
        )
    }

    private fun decodeZone(o: Map<String, Any?>): SpecialZone? {
        val type = ZoneType.fromWireName(MiniJson.string(o["type"])) ?: return null
        val lat = MiniJson.double(o["lat"]) ?: return null
        val lon = MiniJson.double(o["lon"]) ?: return null
        val polygon = MiniJson.array(o["polygon"])?.mapNotNull { v ->
            val pair = MiniJson.array(v) ?: return@mapNotNull null
            if (pair.size != 2) return@mapNotNull null
            val pLat = MiniJson.double(pair[0]) ?: return@mapNotNull null
            val pLon = MiniJson.double(pair[1]) ?: return@mapNotNull null
            pLat to pLon
        } ?: emptyList()
        return SpecialZone(
            id = MiniJson.string(o["id"]) ?: return null,
            name = MiniJson.string(o["name"]) ?: "",
            type = type,
            cycleMultiplier = (MiniJson.double(o["multiplier"])
                ?: type.defaultMultiplier).coerceIn(0.05, 1.0),
            centerLat = lat,
            centerLon = lon,
            radiusM = MiniJson.double(o["radiusM"]) ?: 100.0,
            polygon = polygon
        )
    }

    // ------------------------------------------------------------ encode

    /** Serialize for the supervisor "export provisioning package" action. */
    fun encode(profile: ProvisioningProfile): String {
        val sb = StringBuilder(1024)
        sb.append("{\n")
        sb.append("  \"format\": ").append(MiniJson.quote(FORMAT)).append(",\n")
        sb.append("  \"version\": ").append(VERSION)
        if (profile.name.isNotEmpty()) {
            sb.append(",\n  \"name\": ").append(MiniJson.quote(profile.name))
        }
        if (profile.agency.isNotEmpty()) {
            sb.append(",\n  \"agency\": ").append(MiniJson.quote(profile.agency))
        }
        if (profile.createdMs > 0) {
            sb.append(",\n  \"created\": ").append(profile.createdMs)
        }
        profile.capability?.let { cap ->
            sb.append(",\n  \"capability\": {\n")
            sb.append("    \"type\": ").append(MiniJson.quote(cap.type.wireName)).append(",\n")
            sb.append("    \"hasBlade\": ").append(cap.hasBlade).append(",\n")
            sb.append("    \"hasSalt\": ").append(cap.hasSalt).append(",\n")
            sb.append("    \"canSendDistress\": ").append(cap.canSendDistress).append(",\n")
            sb.append("    \"publishPresence\": ").append(cap.publishPresence).append(",\n")
            sb.append("    \"plowWidthM\": ").append(num(cap.plowWidthM)).append(",\n")
            sb.append("    \"wingWidthM\": ").append(num(cap.wingWidthM)).append(",\n")
            sb.append("    \"towWidthM\": ").append(num(cap.towWidthM)).append(",\n")
            sb.append("    \"callsign\": ").append(MiniJson.quote(cap.callsign)).append(",\n")
            sb.append("    \"vehicleId\": ").append(MiniJson.quote(cap.vehicleId)).append(",\n")
            if (cap.observerLabel.isNotEmpty()) {
                sb.append("    \"observerLabel\": ")
                    .append(MiniJson.quote(cap.observerLabel)).append(",\n")
            }
            sb.append("    \"contractor\": ").append(cap.contractor).append("\n  }")
        }
        profile.cycleTimes?.let { c ->
            sb.append(",\n  \"cycleTimes\": { \"default\": ").append(c.defaultMinutes)
            sb.append(", \"p1\": ").append(c.p1Minutes)
            sb.append(", \"p2\": ").append(c.p2Minutes)
            sb.append(", \"p3\": ").append(c.p3Minutes).append(" }")
        }
        profile.coverageRetentionHours?.let { h ->
            sb.append(",\n  \"coverageRetentionHours\": ").append(num(h))
        }
        profile.roadConditionTtlMinutes?.let { m ->
            sb.append(",\n  \"roadConditionTtlMinutes\": ").append(m)
        }
        if (profile.facilities.isNotEmpty()) {
            sb.append(",\n  \"facilities\": [\n")
            profile.facilities.forEachIndexed { i, f ->
                sb.append("    { \"id\": ").append(MiniJson.quote(f.id))
                sb.append(", \"name\": ").append(MiniJson.quote(f.name))
                sb.append(", \"type\": ").append(MiniJson.quote(f.type.wireName))
                sb.append(", \"lat\": ").append(num(f.lat))
                sb.append(", \"lon\": ").append(num(f.lon))
                sb.append(", \"radiusM\": ").append(num(f.radiusM)).append(" }")
                if (i < profile.facilities.size - 1) sb.append(',')
                sb.append('\n')
            }
            sb.append("  ]")
        }
        if (profile.zones.isNotEmpty()) {
            sb.append(",\n  \"zones\": [\n")
            profile.zones.forEachIndexed { i, z ->
                sb.append("    { \"id\": ").append(MiniJson.quote(z.id))
                sb.append(", \"name\": ").append(MiniJson.quote(z.name))
                sb.append(", \"type\": ").append(MiniJson.quote(z.type.wireName))
                sb.append(", \"multiplier\": ").append(num(z.cycleMultiplier))
                sb.append(", \"lat\": ").append(num(z.centerLat))
                sb.append(", \"lon\": ").append(num(z.centerLon))
                sb.append(", \"radiusM\": ").append(num(z.radiusM))
                if (z.polygon.isNotEmpty()) {
                    sb.append(", \"polygon\": [")
                    sb.append(z.polygon.joinToString(", ") { (a, b) ->
                        "[${num(a)}, ${num(b)}]"
                    })
                    sb.append(']')
                }
                sb.append(" }")
                if (i < profile.zones.size - 1) sb.append(',')
                sb.append('\n')
            }
            sb.append("  ]")
        }
        sb.append("\n}\n")
        return sb.toString()
    }

    /**
     * Round-trip-stable number formatting: integers stay integral, doubles
     * keep up to 7 decimals (cm precision at these latitudes).
     */
    private fun num(v: Double): String {
        if (v == v.toLong().toDouble()) return v.toLong().toString()
        return String.format(Locale.US, "%.7f", v).trimEnd('0').trimEnd('.')
    }
}
