package com.atakmap.android.plowtak.ops

/**
 * A supervisor's route assignment for one unit. [routeId] names either a
 * route from imported agency GIS (`route_id` attribute) or a drawn ATAK
 * route (by name); [source] disambiguates so the coverage calculator knows
 * where to find the geometry.
 */
data class RouteAssignment(
    val vehicleUid: String,
    val callsign: String,
    val routeId: String,
    val source: Source,
    val assignedBy: String,
    val timeMs: Long
) {
    enum class Source(val wireName: String) {
        /** Named route from imported agency GIS (route_id). */
        GIS("gis"),

        /** Route drawn in ATAK; geometry resolved by the map layer. */
        DRAWN("drawn");

        companion object {
            fun fromWireName(name: String?): Source? =
                entries.firstOrNull { it.wireName.equals(name, ignoreCase = true) }
        }
    }
}

/**
 * Route assignment book-keeping: one active assignment per unit,
 * supervisor-driven, replicated over CoT (see RouteAssignmentCotCodec) and
 * persisted locally so a restart mid-storm keeps the fleet board populated.
 * Convergence is last-writer-wins on [RouteAssignment.timeMs] — the newest
 * supervisor decision sticks, and an empty routeId means "unassigned".
 */
class RouteAssignmentManager(
    private val persistence: KeyValuePersistence? = null
) {

    interface Listener {
        fun onAssignmentsChanged(assignments: List<RouteAssignment>)

        /** A local assign/unassign to broadcast over CoT. */
        fun onLocalAssignment(assignment: RouteAssignment)
    }

    private val byVehicle = LinkedHashMap<String, RouteAssignment>()
    private val listeners = mutableListOf<Listener>()

    init {
        persistence?.getString(PERSIST_KEY)?.let { blob ->
            for (line in blob.lineSequence()) {
                decodeLine(line)?.let { byVehicle[it.vehicleUid] = it }
            }
        }
    }

    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    fun all(): List<RouteAssignment> = byVehicle.values.toList()

    /** Active assignment for a unit; null when unassigned. */
    fun assignmentFor(vehicleUid: String): RouteAssignment? =
        byVehicle[vehicleUid]?.takeIf { it.routeId.isNotEmpty() }

    /** Units currently assigned to the given route. */
    fun unitsOn(routeId: String): List<RouteAssignment> =
        byVehicle.values.filter { it.routeId == routeId }

    /** Supervisor assigns (or reassigns) a unit; broadcasts. */
    fun assign(assignment: RouteAssignment) {
        byVehicle[assignment.vehicleUid] = assignment
        persist()
        notifyChanged()
        listeners.toList().forEach { it.onLocalAssignment(assignment) }
    }

    /** Supervisor clears a unit's assignment; broadcasts the tombstone. */
    fun unassign(vehicleUid: String, by: String, nowMs: Long) {
        val prev = byVehicle[vehicleUid] ?: return
        val tombstone = prev.copy(routeId = "", assignedBy = by, timeMs = nowMs)
        byVehicle[vehicleUid] = tombstone
        persist()
        notifyChanged()
        listeners.toList().forEach { it.onLocalAssignment(tombstone) }
    }

    /** Apply an assignment received over CoT (no re-broadcast). */
    fun onRemote(assignment: RouteAssignment) {
        val existing = byVehicle[assignment.vehicleUid]
        if (existing != null && existing.timeMs > assignment.timeMs) return
        byVehicle[assignment.vehicleUid] = assignment
        persist()
        notifyChanged()
    }

    fun clear() {
        if (byVehicle.isEmpty()) return
        byVehicle.clear()
        persistence?.remove(PERSIST_KEY)
        notifyChanged()
    }

    private fun notifyChanged() {
        val snapshot = all()
        listeners.toList().forEach { it.onAssignmentsChanged(snapshot) }
    }

    private fun persist() {
        persistence?.putString(
            PERSIST_KEY,
            byVehicle.values.joinToString("\n") { encodeLine(it) }
        )
    }

    companion object {
        const val PERSIST_KEY = "plowtak.route.assignments"
        private const val SEP = "|"
        private const val ESCAPED_PIPE = "&#124;"

        fun encodeLine(a: RouteAssignment): String = listOf(
            esc(a.vehicleUid), esc(a.callsign), esc(a.routeId),
            a.source.wireName, esc(a.assignedBy), a.timeMs.toString()
        ).joinToString(SEP)

        fun decodeLine(line: String): RouteAssignment? {
            val f = line.trim().split(SEP)
            if (f.size != 6) return null
            val time = f[5].toLongOrNull() ?: return null
            return RouteAssignment(
                vehicleUid = unesc(f[0]),
                callsign = unesc(f[1]),
                routeId = unesc(f[2]),
                source = RouteAssignment.Source.fromWireName(f[3])
                    ?: RouteAssignment.Source.GIS,
                assignedBy = unesc(f[4]),
                timeMs = time
            )
        }

        private fun esc(s: String) = s.replace(SEP, ESCAPED_PIPE)
        private fun unesc(s: String) = s.replace(ESCAPED_PIPE, SEP)
    }
}
