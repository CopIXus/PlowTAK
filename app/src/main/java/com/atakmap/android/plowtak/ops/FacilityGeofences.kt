package com.atakmap.android.plowtak.ops

import com.atakmap.android.plowtak.coverage.GeoMath
import com.atakmap.android.plowtak.model.Facility
import com.atakmap.android.plowtak.model.FacilityType
import com.atakmap.android.plowtak.model.ReloadEvent

/**
 * Supervisor-defined circular facility geofences (salt dome / garage / fuel),
 * stored locally. Feeding GPS fixes through [update] produces enter/exit
 * transitions; entering a salt dome logs a [ReloadEvent] (reload count ≈
 * material used per truck until spreader telemetry exists) and suggests
 * LOADING via the caller.
 */
class FacilityGeofences(
    private val persistence: KeyValuePersistence
) {

    data class Transition(val facility: Facility, val entered: Boolean)

    fun interface Listener {
        fun onTransition(transition: Transition)
    }

    private val facilities = mutableMapOf<String, Facility>()
    private val inside = mutableSetOf<String>()
    private val reloadEvents = mutableListOf<ReloadEvent>()
    private val listeners = mutableListOf<Listener>()

    init {
        load()
    }

    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    fun all(): List<Facility> = facilities.values.toList()

    fun reloads(): List<ReloadEvent> = reloadEvents.toList()

    fun reloadCountSince(sinceMs: Long): Int = reloadEvents.count { it.timeMs >= sinceMs }

    fun add(facility: Facility) {
        facilities[facility.id] = facility
        save()
    }

    fun remove(facilityId: String) {
        facilities.remove(facilityId)
        inside.remove(facilityId)
        save()
    }

    /**
     * Feed a GPS fix; returns transitions that occurred. Salt-dome entries
     * are logged as reload events automatically.
     */
    fun update(lat: Double, lon: Double, timeMs: Long, operatorId: String = ""): List<Transition> {
        val transitions = mutableListOf<Transition>()
        for (f in facilities.values) {
            val within = GeoMath.distanceMeters(lat, lon, f.lat, f.lon) <= f.radiusM
            val was = f.id in inside
            if (within && !was) {
                inside.add(f.id)
                transitions.add(Transition(f, entered = true))
                if (f.type == FacilityType.SALT_DOME) {
                    reloadEvents.add(ReloadEvent(f.id, f.name, timeMs, operatorId))
                    saveReloads()
                }
            } else if (!within && was) {
                inside.remove(f.id)
                transitions.add(Transition(f, entered = false))
            }
        }
        transitions.forEach { t -> listeners.toList().forEach { it.onTransition(t) } }
        return transitions
    }

    fun isInside(facilityId: String): Boolean = facilityId in inside

    /** True while the vehicle is inside any facility geofence (ToggleSanity). */
    fun isInsideAny(): Boolean = inside.isNotEmpty()

    // ------------------------------------------------------- persistence

    private fun save() {
        val encoded = facilities.values.joinToString("\n") { f ->
            listOf(esc(f.id), esc(f.name), f.type.wireName, f.lat, f.lon, f.radiusM)
                .joinToString("|")
        }
        persistence.putString(KEY_FACILITIES, encoded)
    }

    private fun saveReloads() {
        // Keep a bounded tail; full history export is a Phase 2 concern.
        val tail = reloadEvents.takeLast(500)
        val encoded = tail.joinToString("\n") { r ->
            listOf(esc(r.facilityId), esc(r.facilityName), r.timeMs, esc(r.operatorId))
                .joinToString("|")
        }
        persistence.putString(KEY_RELOADS, encoded)
    }

    private fun load() {
        persistence.getString(KEY_FACILITIES)?.lineSequence()?.forEach { line ->
            val f = line.split("|")
            if (f.size == 6) {
                val type = FacilityType.fromWireName(f[2]) ?: return@forEach
                val lat = f[3].toDoubleOrNull() ?: return@forEach
                val lon = f[4].toDoubleOrNull() ?: return@forEach
                val radius = f[5].toDoubleOrNull() ?: return@forEach
                val fac = Facility(unesc(f[0]), unesc(f[1]), type, lat, lon, radius)
                facilities[fac.id] = fac
            }
        }
        persistence.getString(KEY_RELOADS)?.lineSequence()?.forEach { line ->
            val f = line.split("|")
            if (f.size == 4) {
                val time = f[2].toLongOrNull() ?: return@forEach
                reloadEvents.add(ReloadEvent(unesc(f[0]), unesc(f[1]), time, unesc(f[3])))
            }
        }
    }

    private fun esc(s: String) = s.replace("|", "&#124;").replace("\n", " ")
    private fun unesc(s: String) = s.replace("&#124;", "|")

    companion object {
        const val KEY_FACILITIES = "plowtak.facilities"
        const val KEY_RELOADS = "plowtak.reload_events"
    }
}
