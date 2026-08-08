package com.atakmap.android.plowtak.model

/** One GPS sample on a treated swath centerline. */
data class TrackPoint(
    val lat: Double,
    val lon: Double,
    val timeMs: Long,
    /** Degrees true, 0–360; NaN when unknown. */
    val headingDeg: Double = Double.NaN
)

/**
 * A contiguous stretch of road treated by one vehicle in one pass. The
 * centerline is the (thinned) GPS track; renderers stroke it at [widthM].
 * Segments are immutable once built and are the unit of persistence, CoT
 * sharing, and freshness coloring.
 */
data class TreatSegment(
    /** Globally unique: "<vehicleUid>-<startTimeMs>". */
    val id: String,
    val vehicleUid: String,
    val callsign: String,
    /** Storm session this pass belongs to; empty when no session active. */
    val stormId: String,
    /** Operator on shift when the pass was made (records need both IDs). */
    val operatorId: String,
    val material: MaterialMode,
    val widthM: Double,
    val points: List<TrackPoint>,
    val startTimeMs: Long,
    val endTimeMs: Long,
    /** Specific material dispensed when the spreader was on; null otherwise. */
    val spreadMaterial: Material? = null,
    /** Spreader application rate (lbs/mile) from hardware; null when unknown. */
    val applicationRateLbsPerMi: Double? = null,
    /** Road surface temperature (deg F) from hardware; null when unknown. */
    val roadTempF: Double? = null,
    /** Pass recorded by a contractor unit (payment-verification tagging). */
    val contractor: Boolean = false
) {
    init {
        require(points.size >= 2) { "TreatSegment requires at least 2 points" }
    }

    /** Representative heading: last point that has one, else NaN. */
    val headingDeg: Double
        get() = points.lastOrNull { !it.headingDeg.isNaN() }?.headingDeg ?: Double.NaN

    companion object {
        fun makeId(vehicleUid: String, startTimeMs: Long) = "$vehicleUid-$startTimeMs"
    }
}
