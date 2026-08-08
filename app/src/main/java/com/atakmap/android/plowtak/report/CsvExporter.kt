package com.atakmap.android.plowtak.report

import com.atakmap.android.plowtak.coverage.DirectionModel
import com.atakmap.android.plowtak.model.AlertEvent
import com.atakmap.android.plowtak.model.HazardEvent
import com.atakmap.android.plowtak.model.OperatorShift
import com.atakmap.android.plowtak.model.ReloadEvent
import com.atakmap.android.plowtak.model.RoadConditionReport
import com.atakmap.android.plowtak.model.TreatSegment
import com.atakmap.android.plowtak.report.ExportFormat.csvRow
import com.atakmap.android.plowtak.report.ExportFormat.iso
import com.atakmap.android.plowtak.report.ExportFormat.num

/**
 * Records-grade CSV exports, one table per record type. RFC-4180-style
 * quoting; ISO-8601 UTC timestamps alongside raw epoch millis so both
 * humans and databases are happy.
 */
object CsvExporter {

    fun segmentsCsv(segments: List<TreatSegment>): String {
        val rows = mutableListOf(
            csvRow(
                "id", "vehicleUid", "callsign", "operatorId", "stormId",
                "material", "spreadMaterial", "widthM", "lengthM",
                "headingDeg", "side", "startUtc", "endUtc", "startMs", "endMs", "points",
                // Phase 3: contractor tag (payment verification) + hardware
                // telemetry when a Bluetooth controller supplied it.
                "contractor", "rateLbsPerMi", "roadTempF"
            )
        )
        for (seg in segments) {
            val heading = seg.headingDeg
            rows.add(
                csvRow(
                    seg.id, seg.vehicleUid, seg.callsign, seg.operatorId, seg.stormId,
                    seg.material.wireName, seg.spreadMaterial?.wireName ?: "",
                    num(seg.widthM, 2), num(MetricsCalculator.segmentLengthM(seg), 1),
                    if (heading.isNaN()) "" else num(heading, 1),
                    if (heading.isNaN()) "" else DirectionModel.sideOfRoad(heading).wireName,
                    iso(seg.startTimeMs), iso(seg.endTimeMs),
                    seg.startTimeMs.toString(), seg.endTimeMs.toString(),
                    seg.points.size.toString(),
                    if (seg.contractor) "true" else "",
                    seg.applicationRateLbsPerMi?.let { num(it, 1) } ?: "",
                    seg.roadTempF?.let { num(it, 1) } ?: ""
                )
            )
        }
        return rows.joinToString("\r\n") + "\r\n"
    }

    fun alertsCsv(alerts: List<AlertEvent>): String {
        val rows = mutableListOf(
            csvRow(
                "uid", "vehicleUid", "callsign", "vehicleType", "state",
                "handledBy", "lat", "lon", "timeUtc", "timeMs"
            )
        )
        for (a in alerts) {
            rows.add(
                csvRow(
                    a.uid, a.vehicleUid, a.callsign, a.vehicleType.wireName,
                    a.state.wireName, a.handledBy,
                    num(a.lat), num(a.lon), iso(a.timeMs), a.timeMs.toString()
                )
            )
        }
        return rows.joinToString("\r\n") + "\r\n"
    }

    fun hazardsCsv(hazards: List<HazardEvent>): String {
        val rows = mutableListOf(
            csvRow(
                "uid", "kind", "reporterUid", "reporterCallsign", "stormId",
                "photo", "lat", "lon", "timeUtc", "timeMs"
            )
        )
        for (h in hazards) {
            rows.add(
                csvRow(
                    h.uid, h.type.wireName, h.reporterUid, h.reporterCallsign, h.stormId,
                    h.photoFile, num(h.lat), num(h.lon), iso(h.timeMs), h.timeMs.toString()
                )
            )
        }
        return rows.joinToString("\r\n") + "\r\n"
    }

    fun conditionsCsv(conditions: List<RoadConditionReport>): String {
        val rows = mutableListOf(
            csvRow(
                "uid", "condition", "reporterUid", "reporterCallsign", "stormId",
                "lat", "lon", "timeUtc", "timeMs"
            )
        )
        for (c in conditions) {
            rows.add(
                csvRow(
                    c.uid, c.condition.wireName, c.reporterUid, c.reporterCallsign,
                    c.stormId, num(c.lat), num(c.lon), iso(c.timeMs), c.timeMs.toString()
                )
            )
        }
        return rows.joinToString("\r\n") + "\r\n"
    }

    fun reloadsCsv(reloads: List<ReloadEvent>): String {
        val rows = mutableListOf(
            csvRow("facilityId", "facilityName", "operatorId", "timeUtc", "timeMs")
        )
        for (r in reloads) {
            rows.add(
                csvRow(r.facilityId, r.facilityName, r.operatorId, iso(r.timeMs), r.timeMs.toString())
            )
        }
        return rows.joinToString("\r\n") + "\r\n"
    }

    fun shiftsCsv(shifts: List<OperatorShift>): String {
        val rows = mutableListOf(
            csvRow(
                "operatorName", "operatorId", "startUtc", "endUtc",
                "startMs", "endMs", "durationMin"
            )
        )
        for (s in shifts) {
            val endIso = if (s.isActive) "" else iso(s.endTimeMs)
            val durationMin =
                if (s.isActive) "" else ((s.endTimeMs - s.startTimeMs) / 60_000L).toString()
            rows.add(
                csvRow(
                    s.operatorName, s.operatorId, iso(s.startTimeMs), endIso,
                    s.startTimeMs.toString(),
                    if (s.isActive) "" else s.endTimeMs.toString(),
                    durationMin
                )
            )
        }
        return rows.joinToString("\r\n") + "\r\n"
    }
}
