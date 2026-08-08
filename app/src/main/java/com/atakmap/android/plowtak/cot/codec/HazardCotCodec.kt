package com.atakmap.android.plowtak.cot.codec

import com.atakmap.android.plowtak.model.HazardEvent
import com.atakmap.android.plowtak.model.HazardType

/**
 * Detail codec for one-tap hazard drops. The CoT event uses the hazard's
 * marker [HazardType.cotType] (so stock ATAK shows a sensible marker); the
 * specific hazard kind rides in this detail.
 *
 * ```
 * <__plowtak>
 *   <hazard kind= reporterUid= reporterCallsign= stormId= time=/>
 * </__plowtak>
 * ```
 */
object HazardCotCodec {

    fun encode(hazard: HazardEvent): DetailNode =
        DetailNode(
            DetailNode.PLOWTAK, emptyMap(),
            listOf(
                DetailNode(
                    "hazard", buildMap {
                        put("kind", hazard.type.wireName)
                        put("reporterUid", hazard.reporterUid)
                        put("reporterCallsign", hazard.reporterCallsign)
                        put("stormId", hazard.stormId)
                        put("time", hazard.timeMs.toString())
                        if (hazard.hasPhoto) put("photo", hazard.photoFile)
                    }
                )
            )
        )

    fun decode(node: DetailNode, eventUid: String, lat: Double, lon: Double): HazardEvent? {
        val plowtak = if (node.name == DetailNode.PLOWTAK) node
        else node.firstChild(DetailNode.PLOWTAK) ?: return null
        val hazard = plowtak.firstChild("hazard") ?: return null
        val kind = HazardType.fromWireName(hazard.attr("kind")) ?: return null
        return HazardEvent(
            uid = eventUid,
            type = kind,
            reporterUid = hazard.attr("reporterUid") ?: "",
            reporterCallsign = hazard.attr("reporterCallsign") ?: "",
            lat = lat,
            lon = lon,
            timeMs = hazard.attrLong("time", System.currentTimeMillis()),
            stormId = hazard.attr("stormId") ?: "",
            photoFile = hazard.attr("photo") ?: ""
        )
    }
}
