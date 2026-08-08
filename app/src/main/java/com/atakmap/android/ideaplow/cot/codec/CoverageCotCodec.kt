package com.atakmap.android.ideaplow.cot.codec

import com.atakmap.android.ideaplow.coverage.SegmentCodec
import com.atakmap.android.ideaplow.coverage.SwathBuilder
import com.atakmap.android.ideaplow.model.Material
import com.atakmap.android.ideaplow.model.MaterialMode
import com.atakmap.android.ideaplow.model.TreatSegment
import java.util.Locale

/**
 * Batches [TreatSegment]s into a compact `<__ideaplow>` coverage detail for
 * fleet sharing, and decodes inbound ones. Coverage rides its own CoT event
 * type ([COVERAGE_EVENT_TYPE]) so plain ATAK clients ignore it instead of
 * rendering bogus markers.
 *
 * ```
 * <__ideaplow>
 *   <coverage stormId="..." count="2">
 *     <segment id= uid= callsign= op= material= widthM= start= points=.../>
 *   </coverage>
 * </__ideaplow>
 * ```
 */
object CoverageCotCodec {

    /** Custom bits-family type: non-marker, ignored by stock ATAK. */
    const val COVERAGE_EVENT_TYPE = "b-i-x-ideaplow-cov"

    /** Max segments per CoT event to bound message size. */
    const val MAX_SEGMENTS_PER_EVENT = 8

    /** Wire thinning: re-simplify harder if a segment exceeds this. */
    const val MAX_POINTS_PER_WIRE_SEGMENT = 60
    private const val WIRE_SIMPLIFY_TOLERANCE_M = 4.0

    fun encode(stormId: String, segments: List<TreatSegment>): DetailNode {
        require(segments.size <= MAX_SEGMENTS_PER_EVENT) {
            "batch too large: ${segments.size}"
        }
        val segNodes = segments.map { seg ->
            val points =
                if (seg.points.size > MAX_POINTS_PER_WIRE_SEGMENT)
                    SwathBuilder.simplify(seg.points, WIRE_SIMPLIFY_TOLERANCE_M)
                        .take(MAX_POINTS_PER_WIRE_SEGMENT)
                else seg.points
            DetailNode(
                "segment", buildMap {
                    put("id", seg.id)
                    put("uid", seg.vehicleUid)
                    put("callsign", seg.callsign)
                    put("op", seg.operatorId)
                    put("material", seg.material.wireName)
                    seg.spreadMaterial?.let { put("mat", it.wireName) }
                    put("widthM", String.format(Locale.US, "%.1f", seg.widthM))
                    put("start", seg.startTimeMs.toString())
                    // Phase 3: hardware telemetry + contractor tagging,
                    // omitted entirely when absent so v2 payloads are
                    // byte-identical (older receivers ignore extras).
                    if (seg.contractor) put("contractor", "true")
                    seg.applicationRateLbsPerMi?.let {
                        put("rate", String.format(Locale.US, "%.1f", it))
                    }
                    seg.roadTempF?.let {
                        put("temp", String.format(Locale.US, "%.1f", it))
                    }
                    put("points", SegmentCodec.encodePoints(points, seg.startTimeMs))
                }
            )
        }
        return DetailNode(
            DetailNode.IDEAPLOW, emptyMap(),
            listOf(
                DetailNode(
                    "coverage",
                    mapOf("stormId" to stormId, "count" to segments.size.toString()),
                    segNodes
                )
            )
        )
    }

    /** Decodes a coverage detail; malformed segments are skipped. */
    fun decode(node: DetailNode): List<TreatSegment> {
        val ideaplow = if (node.name == DetailNode.IDEAPLOW) node
        else node.firstChild(DetailNode.IDEAPLOW) ?: return emptyList()
        val coverage = ideaplow.firstChild("coverage") ?: return emptyList()
        val stormId = coverage.attr("stormId") ?: ""

        return coverage.childrenNamed("segment").mapNotNull { seg ->
            val id = seg.attr("id") ?: return@mapNotNull null
            val uid = seg.attr("uid") ?: return@mapNotNull null
            val start = seg.attrLong("start", -1L)
            if (start < 0) return@mapNotNull null
            val points = SegmentCodec.decodePoints(seg.attr("points") ?: "", start)
            if (points.size < 2) return@mapNotNull null
            TreatSegment(
                id = id,
                vehicleUid = uid,
                callsign = seg.attr("callsign") ?: "",
                stormId = stormId,
                operatorId = seg.attr("op") ?: "",
                material = MaterialMode.fromWireName(seg.attr("material"))
                    ?: MaterialMode.NONE,
                widthM = seg.attrDouble("widthM", 3.0),
                points = points,
                startTimeMs = start,
                endTimeMs = points.last().timeMs,
                spreadMaterial = Material.fromWireName(seg.attr("mat")),
                contractor = seg.attr("contractor") == "true",
                applicationRateLbsPerMi = seg.attr("rate")?.toDoubleOrNull(),
                roadTempF = seg.attr("temp")?.toDoubleOrNull()
            )
        }
    }
}
