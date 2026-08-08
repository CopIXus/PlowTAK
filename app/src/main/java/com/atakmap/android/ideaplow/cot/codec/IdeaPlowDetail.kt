package com.atakmap.android.ideaplow.cot.codec

import com.atakmap.android.ideaplow.coverage.DirectionModel
import com.atakmap.android.ideaplow.coverage.RoadSide
import com.atakmap.android.ideaplow.model.Material
import com.atakmap.android.ideaplow.model.MaterialMode
import com.atakmap.android.ideaplow.model.VehicleCapability
import com.atakmap.android.ideaplow.model.VehicleStatus
import com.atakmap.android.ideaplow.model.VehicleType
import com.atakmap.android.ideaplow.model.WidthPreset
import java.util.Locale

/**
 * The `<__ideaplow>` PLI detail: everything a receiving IdeaPlow client needs
 * to render this unit and gate coverage merging. Encodes to / decodes from a
 * [DetailNode] tree matching docs/cot-schema.md:
 *
 * ```
 * <__ideaplow>
 *   <vehicle type= hasBlade= hasSalt= canTreat= role=/>
 *   <status blade= salt= material= preset= mode=/>
 *   <geom plowWidthM= heading= side=/>
 *   <ops stormId= routeId=/>
 *   <operator id= name=/>
 * </__ideaplow>
 * ```
 */
data class IdeaPlowDetail(
    val vehicleType: VehicleType,
    val hasBlade: Boolean,
    val hasSalt: Boolean,
    val canTreat: Boolean,
    val status: VehicleStatus,
    val bladeDown: Boolean,
    val saltOn: Boolean,
    val material: Material,
    val plowWidthM: Double,
    val headingDeg: Double,
    val stormId: String = "",
    val routeId: String = "",
    val operatorId: String = "",
    val operatorName: String = "",
    /** Active effective-width preset. */
    val widthPreset: WidthPreset = WidthPreset.STANDARD,
    /** Reloads logged this storm (salt-dome geofence entries). */
    val reloadCount: Int = 0,
    /** Hired (contractor) unit — publishes under a CTR-<storm>-<n> uid. */
    val contractor: Boolean = false
) {

    /** Side of the corridor a treating pass is painting, from heading. */
    val side: RoadSide get() = DirectionModel.sideOfRoad(headingDeg)

    /** High-level role attribute for quick filtering by receivers. */
    val role: String
        get() = when {
            status == VehicleStatus.TREATING -> "treating"
            vehicleType == VehicleType.OBSERVER -> "viewer"
            else -> "presence"
        }

    fun toNode(): DetailNode {
        val children = mutableListOf(
            DetailNode(
                "vehicle", buildMap {
                    put("type", vehicleType.wireName)
                    put("hasBlade", hasBlade.toString())
                    put("hasSalt", hasSalt.toString())
                    put("canTreat", canTreat.toString())
                    put("role", role)
                    // Present only for hired units (older receivers ignore).
                    if (contractor) put("contractor", "true")
                }
            ),
            DetailNode(
                "status", mapOf(
                    "blade" to when {
                        !hasBlade -> "none"
                        bladeDown -> "down"
                        else -> "up"
                    },
                    "salt" to when {
                        !hasSalt -> "none"
                        saltOn -> "on"
                        else -> "off"
                    },
                    "material" to material.wireName,
                    "preset" to widthPreset.wireName,
                    "mode" to status.wireName
                )
            ),
            DetailNode(
                "geom", buildMap {
                    put("plowWidthM", fmt(plowWidthM))
                    if (!headingDeg.isNaN()) {
                        put("heading", fmt(headingDeg))
                        if (status == VehicleStatus.TREATING) {
                            put("side", side.wireName)
                        }
                    }
                }
            )
        )
        if (stormId.isNotEmpty() || routeId.isNotEmpty() || reloadCount > 0) {
            children.add(
                DetailNode("ops", buildMap {
                    if (stormId.isNotEmpty()) put("stormId", stormId)
                    if (routeId.isNotEmpty()) put("routeId", routeId)
                    if (reloadCount > 0) put("reloads", reloadCount.toString())
                })
            )
        }
        if (operatorId.isNotEmpty() || operatorName.isNotEmpty()) {
            children.add(
                DetailNode("operator", buildMap {
                    if (operatorId.isNotEmpty()) put("id", operatorId)
                    if (operatorName.isNotEmpty()) put("name", operatorName)
                })
            )
        }
        return DetailNode(DetailNode.IDEAPLOW, emptyMap(), children)
    }

    companion object {

        private fun fmt(v: Double): String =
            String.format(Locale.US, "%.1f", v)

        /** Build the outbound detail from local state. */
        fun fromLocalState(
            cap: VehicleCapability,
            status: VehicleStatus,
            bladeDown: Boolean,
            saltOn: Boolean,
            material: Material,
            headingDeg: Double,
            stormId: String,
            operatorId: String,
            operatorName: String,
            widthPreset: WidthPreset = WidthPreset.STANDARD,
            reloadCount: Int = 0
        ) = IdeaPlowDetail(
            vehicleType = cap.type,
            hasBlade = cap.hasBlade,
            hasSalt = cap.hasSalt,
            canTreat = cap.canTreat,
            status = status,
            bladeDown = bladeDown,
            saltOn = saltOn,
            material = material,
            // Preset changes the advertised effective width live.
            plowWidthM = cap.widthFor(widthPreset),
            headingDeg = headingDeg,
            stormId = stormId,
            operatorId = operatorId,
            operatorName = operatorName,
            widthPreset = widthPreset,
            reloadCount = reloadCount,
            contractor = cap.contractor
        )

        /** Returns null if the node is not a valid `<__ideaplow>` detail. */
        fun fromNode(node: DetailNode): IdeaPlowDetail? {
            if (node.name != DetailNode.IDEAPLOW) return null
            val vehicle = node.firstChild("vehicle") ?: return null
            val type = VehicleType.fromWireName(vehicle.attr("type")) ?: return null
            val status = node.firstChild("status")
            val geom = node.firstChild("geom")
            val ops = node.firstChild("ops")
            val operator = node.firstChild("operator")

            return IdeaPlowDetail(
                vehicleType = type,
                hasBlade = vehicle.attrBool("hasBlade"),
                hasSalt = vehicle.attrBool("hasSalt"),
                canTreat = vehicle.attrBool("canTreat"),
                status = VehicleStatus.fromWireName(status?.attr("mode"))
                    ?: VehicleStatus.DEADHEAD,
                bladeDown = status?.attr("blade") == "down",
                saltOn = status?.attr("salt") == "on",
                material = Material.fromWireName(status?.attr("material")) ?: Material.SALT,
                plowWidthM = geom?.attrDouble("plowWidthM", 0.0) ?: 0.0,
                headingDeg = geom?.attrDouble("heading") ?: Double.NaN,
                stormId = ops?.attr("stormId") ?: "",
                routeId = ops?.attr("routeId") ?: "",
                operatorId = operator?.attr("id") ?: "",
                operatorName = operator?.attr("name") ?: "",
                widthPreset = WidthPreset.fromWireName(status?.attr("preset"))
                    ?: WidthPreset.STANDARD,
                reloadCount = ops?.attrLong("reloads", 0L)?.toInt() ?: 0,
                contractor = vehicle.attr("contractor") == "true"
            )
        }
    }
}
