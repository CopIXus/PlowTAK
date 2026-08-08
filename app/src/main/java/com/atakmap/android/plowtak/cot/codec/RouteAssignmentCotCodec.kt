package com.atakmap.android.plowtak.cot.codec

import com.atakmap.android.plowtak.ops.RouteAssignment

/**
 * Detail codec for supervisor route assignments (Phase 3). One event per
 * unit, re-sent on every change under a stable per-unit uid
 * (`plowtak-route-<vehicleUid>`) so clients converge to the newest
 * decision; an empty routeId is the explicit "unassigned" tombstone.
 *
 * ```
 * <__plowtak>
 *   <routeAssign vehicle= callsign= route= source= by= time=/>
 * </__plowtak>
 * ```
 */
object RouteAssignmentCotCodec {

    const val ROUTE_EVENT_TYPE = "b-i-x-plowtak-route"

    fun eventUidFor(vehicleUid: String) = "plowtak-route-$vehicleUid"

    fun encode(a: RouteAssignment): DetailNode =
        DetailNode(
            DetailNode.PLOWTAK, emptyMap(),
            listOf(
                DetailNode(
                    "routeAssign", mapOf(
                        "vehicle" to a.vehicleUid,
                        "callsign" to a.callsign,
                        "route" to a.routeId,
                        "source" to a.source.wireName,
                        "by" to a.assignedBy,
                        "time" to a.timeMs.toString()
                    )
                )
            )
        )

    fun decode(node: DetailNode): RouteAssignment? {
        val plowtak = if (node.name == DetailNode.PLOWTAK) node
        else node.firstChild(DetailNode.PLOWTAK) ?: return null
        val ra = plowtak.firstChild("routeAssign") ?: return null
        val vehicle = ra.attr("vehicle") ?: return null
        val time = ra.attrLong("time", -1L)
        if (time < 0) return null
        return RouteAssignment(
            vehicleUid = vehicle,
            callsign = ra.attr("callsign") ?: "",
            routeId = ra.attr("route") ?: "",
            source = RouteAssignment.Source.fromWireName(ra.attr("source"))
                ?: RouteAssignment.Source.GIS,
            assignedBy = ra.attr("by") ?: "",
            timeMs = time
        )
    }
}
