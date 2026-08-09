package com.atakmap.android.plowtak.cot

import com.atakmap.android.cot.MarkerDetailHandler
import com.atakmap.android.maps.Marker
import com.atakmap.android.plowtak.cot.codec.DetailNode
import com.atakmap.coremap.cot.event.CotDetail
import com.atakmap.coremap.cot.event.CotEvent

/**
 * Registers `<__plowtak>` with CotDetailManager so ATAK preserves PlowTAK
 * marker metadata through CoT round-trips (TAK “Custom CoT Details” guidance).
 */
class PlowTakDetailHandler : MarkerDetailHandler {

    override fun toCotDetail(marker: Marker, detail: CotDetail) {
        if (!marker.getMetaBoolean(META_FLAG, false)) return
        val plowtak = CotDetail(DetailNode.PLOWTAK)
        val vehicleType = marker.getMetaString(META_VEHICLE_TYPE, null)
        if (!vehicleType.isNullOrEmpty()) {
            val vehicle = CotDetail("vehicle")
            vehicle.setAttribute("type", vehicleType)
            plowtak.addChild(vehicle)
        }
        val hazardKind = marker.getMetaString(META_HAZARD_KIND, null)
        if (!hazardKind.isNullOrEmpty()) {
            val hazard = CotDetail("hazard")
            hazard.setAttribute("kind", hazardKind)
            plowtak.addChild(hazard)
        }
        val condition = marker.getMetaString(META_CONDITION, null)
        if (!condition.isNullOrEmpty()) {
            val node = CotDetail("condition")
            node.setAttribute("state", condition)
            plowtak.addChild(node)
        }
        if (plowtak.childCount() > 0) {
            detail.addChild(plowtak)
        }
    }

    override fun toMarkerMetadata(marker: Marker, event: CotEvent, detail: CotDetail) {
        marker.setMetaBoolean(META_FLAG, true)
        // detail is the __plowtak element itself when registered by name.
        detail.getFirstChildByName(0, "vehicle")?.getAttribute("type")?.let {
            marker.setMetaString(META_VEHICLE_TYPE, it)
        }
        detail.getFirstChildByName(0, "hazard")?.getAttribute("kind")?.let {
            marker.setMetaString(META_HAZARD_KIND, it)
        }
        detail.getFirstChildByName(0, "condition")?.getAttribute("state")?.let {
            marker.setMetaString(META_CONDITION, it)
        }
    }

    companion object {
        const val ELEMENT = DetailNode.PLOWTAK
        private const val META_FLAG = "plowtak.detail"
        private const val META_VEHICLE_TYPE = "plowtak.vehicleType"
        private const val META_HAZARD_KIND = "plowtak.hazardKind"
        private const val META_CONDITION = "plowtak.condition"
    }
}
