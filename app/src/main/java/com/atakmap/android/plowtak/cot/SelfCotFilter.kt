package com.atakmap.android.plowtak.cot

/**
 * Decides whether an inbound CoT uid is an echo of this unit's own traffic.
 *
 * Exact match only for the self PLI uid. Derived event uids (coverage,
 * distress, conditions, hazards) use a `"$self-"` prefix so peers whose
 * vehicle uid shares a string prefix (e.g. `PLOWTAK-T-1` vs `PLOWTAK-T-10`)
 * are never dropped.
 */
object SelfCotFilter {

    fun isSelfEcho(eventUid: String?, selfUid: String): Boolean {
        if (eventUid.isNullOrEmpty() || selfUid.isEmpty()) return false
        if (eventUid == selfUid) return true
        return eventUid.startsWith("$selfUid-")
    }
}
