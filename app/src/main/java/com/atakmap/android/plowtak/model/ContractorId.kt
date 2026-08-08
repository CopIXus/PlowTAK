package com.atakmap.android.plowtak.model

/**
 * Per-storm temporary UID scheme for hired (contractor) trucks:
 * `CTR-<stormId>-<n>`. Contractor units publish under this UID instead of
 * their persistent install UID so agency records key on the engagement
 * (this storm, contractor slot n) rather than on whatever device the hired
 * operator happens to carry — and so a truck hired for two storms yields
 * two cleanly separable record sets for payment verification.
 *
 * The slot number is assigned locally at storm adoption (persisted per
 * storm by `prefs/VehicleCapabilityStore`); collisions across contractors
 * are avoided by deriving the slot from the stable install UID.
 */
object ContractorId {

    const val PREFIX = "CTR"

    /** Build the temporary UID for a contractor slot in a storm. */
    fun uidFor(stormId: String, slot: Int): String {
        require(stormId.isNotEmpty()) { "contractor UID requires a storm" }
        return "$PREFIX-${sanitize(stormId)}-$slot"
    }

    /**
     * Stable slot number derived from the persistent install UID, so the
     * same device maps to the same slot all storm without coordination.
     * Range 100..999 keeps it short and human-readable on markers.
     */
    fun slotFor(installUid: String): Int {
        var h = 0
        for (c in installUid) h = (h * 31 + c.code) and 0x7FFFFFFF
        return 100 + (h % 900)
    }

    /** True when a CoT uid follows the contractor scheme. */
    fun isContractorUid(uid: String): Boolean =
        uid.startsWith("$PREFIX-") && uid.count { it == '-' } >= 2

    /** Storm id embedded in a contractor uid, or null. */
    fun stormOf(uid: String): String? {
        if (!isContractorUid(uid)) return null
        return uid.removePrefix("$PREFIX-").substringBeforeLast('-')
            .ifEmpty { null }
    }

    private fun sanitize(stormId: String): String =
        stormId.replace(Regex("[^A-Za-z0-9._-]"), "_")
}
