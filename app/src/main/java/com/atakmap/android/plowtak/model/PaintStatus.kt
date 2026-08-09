package com.atakmap.android.plowtak.model

/**
 * Why the map is (or isn't) painting coverage for this unit right now.
 * Blade and spread are independent tracks with shared storm/shift/motion gates.
 */
data class PaintStatus(
    val bladePainting: Boolean,
    val spreadPainting: Boolean,
    /** Short operator-facing reason when neither track paints. */
    val reason: String
) {
    val anyPainting: Boolean get() = bladePainting || spreadPainting

    companion object {
        fun idle(reason: String) = PaintStatus(false, false, reason)
    }
}
