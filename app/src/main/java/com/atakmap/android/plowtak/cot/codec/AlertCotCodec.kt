package com.atakmap.android.plowtak.cot.codec

import com.atakmap.android.plowtak.model.AlertEvent
import com.atakmap.android.plowtak.model.AlertState
import com.atakmap.android.plowtak.model.VehicleType

/**
 * Detail codec for distress alerts and their ack/clear workflow.
 *
 * The alert itself is sent as a high-priority emergency CoT
 * ([DISTRESS_EVENT_TYPE], the ATAK 911-alert convention) so even non-PlowTak
 * clients raise it; state transitions (ack/clear) are PlowTak-namespace
 * updates re-sent under the same event uid.
 *
 * ```
 * <__plowtak>
 *   <alert vehicleUid= callsign= vehicleType= state= handledBy= blade= salt=/>
 * </__plowtak>
 * ```
 */
object AlertCotCodec {

    /** ATAK emergency convention: 911 alert / cancel. */
    const val DISTRESS_EVENT_TYPE = "b-a-o-tbl"
    const val DISTRESS_CANCEL_TYPE = "b-a-o-can"

    fun encode(alert: AlertEvent): DetailNode =
        DetailNode(
            DetailNode.PLOWTAK, emptyMap(),
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
        val plowtak = if (node.name == DetailNode.PLOWTAK) node
        else node.firstChild(DetailNode.PLOWTAK) ?: return null
        val alert = plowtak.firstChild("alert") ?: return null
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
