package com.atakmap.android.plowtak.cot.codec

import com.atakmap.android.plowtak.model.StormSession

/**
 * Detail codec for storm session start/end broadcasts.
 *
 * Older peers omit optional attrs; decode tolerates missing fields.
 */
object StormCotCodec {

    const val STORM_EVENT_TYPE = "b-i-x-plowtak-storm"

    fun encode(session: StormSession): DetailNode {
        val attrs = linkedMapOf(
            "id" to session.id,
            "start" to session.startTimeMs.toString(),
            "end" to session.endTimeMs.toString(),
            "startedBy" to session.startedBy
        )
        if (session.label.isNotEmpty()) attrs["label"] = session.label
        if (session.agency.isNotEmpty()) attrs["agency"] = session.agency
        if (session.missionName.isNotEmpty()) attrs["mission"] = session.missionName
        if (session.channel.isNotEmpty()) attrs["channel"] = session.channel
        if (session.cycleMinutes != 45) attrs["cycle"] = session.cycleMinutes.toString()
        return DetailNode(
            DetailNode.PLOWTAK, emptyMap(),
            listOf(DetailNode("storm", attrs))
        )
    }

    fun decode(node: DetailNode): StormSession? {
        val plowtak = if (node.name == DetailNode.PLOWTAK) node
        else node.firstChild(DetailNode.PLOWTAK) ?: return null
        val storm = plowtak.firstChild("storm") ?: return null
        val id = storm.attr("id") ?: return null
        return StormSession(
            id = id,
            startTimeMs = storm.attrLong("start"),
            endTimeMs = storm.attrLong("end"),
            startedBy = storm.attr("startedBy") ?: "",
            label = storm.attr("label") ?: "",
            agency = storm.attr("agency") ?: "",
            missionName = storm.attr("mission") ?: "",
            channel = storm.attr("channel") ?: "",
            cycleMinutes = storm.attr("cycle")?.toIntOrNull() ?: 45
        )
    }
}
