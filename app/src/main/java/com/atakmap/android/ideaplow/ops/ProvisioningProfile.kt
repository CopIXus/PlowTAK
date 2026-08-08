package com.atakmap.android.ideaplow.ops

import com.atakmap.android.ideaplow.coverage.CycleTimes
import com.atakmap.android.ideaplow.model.Facility
import com.atakmap.android.ideaplow.model.SpecialZone
import com.atakmap.android.ideaplow.model.VehicleCapability

/**
 * Everything an agency pushes onto a device in one file: an optional
 * capability profile (the truck's role/equipment — omitted for generic
 * fleet-wide config), cycle times, facility geofences, and special zones.
 *
 * The on-disk format is plain JSON (see [ProvisioningCodec]) so it is
 * TAK-agnostic: it can ride inside an ATAK data package, be opened from a
 * file picker, or arrive via a QR deep-link. Contractor onboarding is the
 * same file with `capability.contractor = true`.
 */
data class ProvisioningProfile(
    /** Free-text label shown at import ("City of X — Contractor loadout"). */
    val name: String = "",
    /** Profile author/agency, informational. */
    val agency: String = "",
    /** Creation time (epoch ms), informational. */
    val createdMs: Long = 0L,
    /** Device capability to apply; null = leave device capability alone. */
    val capability: VehicleCapability? = null,
    /** Cycle times to apply; null = leave alone. */
    val cycleTimes: CycleTimes? = null,
    val facilities: List<Facility> = emptyList(),
    val zones: List<SpecialZone> = emptyList()
) {
    val isEmpty: Boolean
        get() = capability == null && cycleTimes == null &&
                facilities.isEmpty() && zones.isEmpty()
}
