package com.atakmap.android.plowtak.cot.codec

import com.atakmap.android.plowtak.model.RoadCondition
import com.atakmap.android.plowtak.model.RoadConditionReport

/**
 * Detail codec for quick road-condition reports. Like hazards, the event
 * uses an ordinary marker type ([CONDITION_MARKER_TYPE]) so stock ATAK
 * renders something sensible; the condition rides in this detail and
 * PlowTak supervisors get typed condition markers.
 *
 * ```
 * <__plowtak>
 *   <condition state= reporterUid= reporterCallsign= stormId= time=/>
 * </__plowtak>
 * ```
 */
object RoadConditionCotCodec {

    /** Generic map-point marker type; specific kind rides in the detail. */
    const val CONDITION_MARKER_TYPE = "b-m-p-s-m"

    fun encode(report: RoadConditionReport): DetailNode =
        DetailNode(
            DetailNode.PLOWTAK, emptyMap(),
            listOf(
                DetailNode(
                    "condition", mapOf(
                        "state" to report.condition.wireName,
                        "reporterUid" to report.reporterUid,
                        "reporterCallsign" to report.reporterCallsign,
                        "stormId" to report.stormId,
                        "time" to report.timeMs.toString()
                    )
                )
            )
        )

    fun decode(node: DetailNode, eventUid: String, lat: Double, lon: Double): RoadConditionReport? {
        val plowtak = if (node.name == DetailNode.PLOWTAK) node
        else node.firstChild(DetailNode.PLOWTAK) ?: return null
        val cond = plowtak.firstChild("condition") ?: return null
        val state = RoadCondition.fromWireName(cond.attr("state")) ?: return null
        return RoadConditionReport(
            uid = eventUid,
            condition = state,
            reporterUid = cond.attr("reporterUid") ?: "",
            reporterCallsign = cond.attr("reporterCallsign") ?: "",
            lat = lat,
            lon = lon,
            timeMs = cond.attrLong("time", System.currentTimeMillis()),
            stormId = cond.attr("stormId") ?: ""
        )
    }
}
