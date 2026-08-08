package com.atakmap.android.ideaplow.report

import com.atakmap.android.ideaplow.coverage.DirectionModel
import com.atakmap.android.ideaplow.coverage.GeoMath
import com.atakmap.android.ideaplow.model.TreatSegment
import com.atakmap.android.ideaplow.report.ExportFormat.iso
import com.atakmap.android.ideaplow.report.ExportFormat.jsonString
import com.atakmap.android.ideaplow.report.ExportFormat.num

/**
 * Records-grade GeoJSON export of a storm session: one FeatureCollection
 * with treated segments as LineStrings and alerts / hazards / conditions as
 * Points, every feature stamped with the identity + timestamps agencies
 * need for after-action review and claims defense. Pure string building —
 * no JSON library dependency.
 */
object GeoJsonExporter {

    fun export(data: StormExportData): String {
        val features = mutableListOf<String>()
        data.segments.forEach { features.add(segmentFeature(it)) }
        data.alerts.forEach { alert ->
            features.add(
                pointFeature(
                    alert.lat, alert.lon, mapOf(
                        "type" to "alert",
                        "uid" to alert.uid,
                        "vehicle" to alert.vehicleUid,
                        "callsign" to alert.callsign,
                        "state" to alert.state.wireName,
                        "handledBy" to alert.handledBy,
                        "time" to iso(alert.timeMs)
                    )
                )
            )
        }
        data.hazards.forEach { hz ->
            features.add(
                pointFeature(
                    hz.lat, hz.lon, mapOf(
                        "type" to "hazard",
                        "uid" to hz.uid,
                        "kind" to hz.type.wireName,
                        "reporter" to hz.reporterCallsign,
                        "photo" to hz.photoFile,
                        "time" to iso(hz.timeMs)
                    )
                )
            )
        }
        data.conditions.forEach { cond ->
            features.add(
                pointFeature(
                    cond.lat, cond.lon, mapOf(
                        "type" to "condition",
                        "uid" to cond.uid,
                        "state" to cond.condition.wireName,
                        "reporter" to cond.reporterCallsign,
                        "time" to iso(cond.timeMs)
                    )
                )
            )
        }

        val header = listOf(
            "\"type\":\"FeatureCollection\"",
            "\"ideaplow\":{" + props(
                mapOf(
                    "stormId" to data.stormId,
                    "generatedAt" to iso(data.generatedAtMs),
                    "exportedBy" to data.callsign,
                    "vehicleUid" to data.vehicleUid
                )
            ) + "}"
        ).joinToString(",")

        return "{" + header + ",\"features\":[" + features.joinToString(",") + "]}"
    }

    private fun segmentFeature(seg: TreatSegment): String {
        val coords = seg.points.joinToString(",") { p ->
            "[${num(p.lon)},${num(p.lat)}]"
        }
        val propMap = linkedMapOf(
            "type" to "segment",
            "id" to seg.id,
            "vehicle" to seg.vehicleUid,
            "callsign" to seg.callsign,
            "operator" to seg.operatorId,
            "storm" to seg.stormId,
            "material" to seg.material.wireName,
            "spreadMaterial" to (seg.spreadMaterial?.wireName ?: ""),
            "start" to iso(seg.startTimeMs),
            "end" to iso(seg.endTimeMs)
        )
        val numericProps = listOf(
            "\"widthM\":" + num(seg.widthM, 2),
            "\"lengthM\":" + num(MetricsCalculator.segmentLengthM(seg), 1),
            "\"startMs\":" + seg.startTimeMs,
            "\"endMs\":" + seg.endTimeMs
        ).joinToString(",")
        val heading = seg.headingDeg
        val headingProps = if (!heading.isNaN()) {
            ",\"headingDeg\":" + num(heading, 1) +
                    ",\"side\":" + jsonString(DirectionModel.sideOfRoad(heading).wireName)
        } else ""

        return "{\"type\":\"Feature\",\"geometry\":{\"type\":\"LineString\"," +
                "\"coordinates\":[" + coords + "]},\"properties\":{" +
                props(propMap) + "," + numericProps + headingProps + "}}"
    }

    private fun pointFeature(lat: Double, lon: Double, properties: Map<String, String>): String =
        "{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\"," +
                "\"coordinates\":[${num(lon)},${num(lat)}]},\"properties\":{" +
                props(properties) + "}}"

    private fun props(map: Map<String, String>): String =
        map.entries.joinToString(",") { (k, v) -> jsonString(k) + ":" + jsonString(v) }
}
