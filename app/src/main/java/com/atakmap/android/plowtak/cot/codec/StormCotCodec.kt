package com.atakmap.android.plowtak.cot.codec

import com.atakmap.android.plowtak.coverage.FreshnessModel
import com.atakmap.android.plowtak.coverage.StormDefaults
import com.atakmap.android.plowtak.model.StormSession

/**
 * Detail codec for storm session start/end broadcasts.
 *
 * Older peers omit optional attrs; decode tolerates missing fields.
 */
object StormCotCodec {

    const val STORM_EVENT_TYPE = "b-i-x-plowtak-storm"

    fun encode(session: StormSession): DetailNode {
        val s = session.sanitized()
        val attrs = linkedMapOf(
            "id" to s.id,
            "start" to s.startTimeMs.toString(),
            "end" to s.endTimeMs.toString(),
            "startedBy" to s.startedBy
        )
        if (s.label.isNotEmpty()) attrs["label"] = s.label
        if (s.agency.isNotEmpty()) attrs["agency"] = s.agency
        if (s.missionName.isNotEmpty()) attrs["mission"] = s.missionName
        if (s.channel.isNotEmpty()) attrs["channel"] = s.channel
        attrs["green"] = s.greenUntilMinutes.toString()
        attrs["yellow"] = s.yellowUntilMinutes.toString()
        attrs["cycle"] = s.cycleMinutes.toString()
        if (s.cycleP1Minutes > 0) attrs["p1"] = s.cycleP1Minutes.toString()
        if (s.cycleP2Minutes > 0) attrs["p2"] = s.cycleP2Minutes.toString()
        if (s.cycleP3Minutes > 0) attrs["p3"] = s.cycleP3Minutes.toString()
        if (s.coverageRetentionHours != StormSession.DEFAULT_COVERAGE_RETENTION_HOURS) {
            attrs["retainH"] = s.coverageRetentionHours.toString()
        }
        if (s.roadConditionTtlMinutes != StormSession.DEFAULT_ROAD_CONDITION_TTL_MINUTES) {
            attrs["condTtl"] = s.roadConditionTtlMinutes.toString()
        }
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
        val cycle = storm.attr("cycle")?.toIntOrNull() ?: StormDefaults.RED_AFTER_MIN
        val hasGreen = storm.attr("green") != null
        val hasYellow = storm.attr("yellow") != null
        val migrated = if (!hasGreen || !hasYellow) {
            FreshnessModel.fromLegacyCycle(cycle)
        } else null
        return StormSession(
            id = id,
            startTimeMs = storm.attrLong("start"),
            endTimeMs = storm.attrLong("end"),
            startedBy = storm.attr("startedBy") ?: "",
            label = storm.attr("label") ?: "",
            agency = storm.attr("agency") ?: "",
            missionName = storm.attr("mission") ?: "",
            channel = storm.attr("channel") ?: "",
            greenUntilMinutes = storm.attr("green")?.toIntOrNull()
                ?: migrated?.greenUntilMinutes
                ?: StormDefaults.GREEN_UNTIL_MIN,
            yellowUntilMinutes = storm.attr("yellow")?.toIntOrNull()
                ?: migrated?.yellowUntilMinutes
                ?: StormDefaults.YELLOW_UNTIL_MIN,
            cycleMinutes = cycle,
            cycleP1Minutes = storm.attr("p1")?.toIntOrNull() ?: 0,
            cycleP2Minutes = storm.attr("p2")?.toIntOrNull() ?: 0,
            cycleP3Minutes = storm.attr("p3")?.toIntOrNull() ?: 0,
            coverageRetentionHours = storm.attr("retainH")?.toDoubleOrNull()
                ?: StormSession.DEFAULT_COVERAGE_RETENTION_HOURS,
            roadConditionTtlMinutes = storm.attr("condTtl")?.toIntOrNull()
                ?: StormSession.DEFAULT_ROAD_CONDITION_TTL_MINUTES
        ).sanitized()
    }
}
