package com.atakmap.android.ideaplow.cot.codec

import com.atakmap.android.ideaplow.model.AlertEvent
import com.atakmap.android.ideaplow.model.AlertState
import com.atakmap.android.ideaplow.model.VehicleType

/**
 * Detail codec for distress alerts and their ack/clear workflow.
 *
 * The alert itself is sent as a high-priority emergency CoT
 * ([DISTRESS_EVENT_TYPE], the ATAK 911-alert convention) so even non-IdeaPlow
 * clients raise it; state transitions (ack/clear) are IdeaPlow-namespace
 * updates re-sent under the same event uid.
 *
 * ```
 * <__ideaplow>
 *   <alert vehicleUid= callsign= vehicleType= state= handledBy= blade= salt=/>
 * </__ideaplow>
 * ```
 */
object AlertCotCodec {

    /** ATAK emergency convention: 911 alert / cancel. */
    const val DISTRESS_EVENT_TYPE = "b-a-o-tbl"
    const val DISTRESS_CANCEL_TYPE = "b-a-o-can"

    fun encode(alert: AlertEvent): DetailNode =
        DetailNode(
            DetailNode.IDEAPLOW, emptyMap(),
            listOf(
                DetailNode(
                    "alert", mapOf(
                        "vehicleUid" to alert.vehicleUid,
                        "callsign" to alert.callsign,
                        "vehicleType" to alert.vehicleType.wireName,
                        "state" to alert.state.wireName,
                        "handledBy" to alert.handledBy,
                        "blade" to alert.bladeDown.toString(),
                        "salt" to alert.saltOn.toString(),
                        "time" to alert.timeMs.toString()
                    )
                )
            )
        )

    /**
     * Decodes an alert detail. [eventUid], [lat], [lon] come from the CoT
     * event envelope.
     */
    fun decode(node: DetailNode, eventUid: String, lat: Double, lon: Double): AlertEvent? {
        val ideaplow = if (node.name == DetailNode.IDEAPLOW) node
        else node.firstChild(DetailNode.IDEAPLOW) ?: return null
        val alert = ideaplow.firstChild("alert") ?: return null
        val vehicleUid = alert.attr("vehicleUid") ?: return null
        return AlertEvent(
            uid = eventUid,
            vehicleUid = vehicleUid,
            callsign = alert.attr("callsign") ?: "",
            vehicleType = VehicleType.fromWireName(alert.attr("vehicleType"))
                ?: VehicleType.PLOW,
            lat = lat,
            lon = lon,
            timeMs = alert.attrLong("time", System.currentTimeMillis()),
            state = AlertState.fromWireName(alert.attr("state")) ?: AlertState.ACTIVE,
            handledBy = alert.attr("handledBy") ?: "",
            bladeDown = alert.attrBool("blade"),
            saltOn = alert.attrBool("salt")
        )
    }
}
