package com.atakmap.android.ideaplow.cot.codec

import com.atakmap.android.ideaplow.model.RoadCondition
import com.atakmap.android.ideaplow.model.RoadConditionReport

/**
 * Detail codec for quick road-condition reports. Like hazards, the event
 * uses an ordinary marker type ([CONDITION_MARKER_TYPE]) so stock ATAK
 * renders something sensible; the condition rides in this detail and
 * IdeaPlow supervisors get typed condition markers.
 *
 * ```
 * <__ideaplow>
 *   <condition state= reporterUid= reporterCallsign= stormId= time=/>
 * </__ideaplow>
 * ```
 */
object RoadConditionCotCodec {

    /** Generic map-point marker type; specific kind rides in the detail. */
    const val CONDITION_MARKER_TYPE = "b-m-p-s-m"

    fun encode(report: RoadConditionReport): DetailNode =
        DetailNode(
            DetailNode.IDEAPLOW, emptyMap(),
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
        val ideaplow = if (node.name == DetailNode.IDEAPLOW) node
        else node.firstChild(DetailNode.IDEAPLOW) ?: return null
        val cond = ideaplow.firstChild("condition") ?: return null
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
