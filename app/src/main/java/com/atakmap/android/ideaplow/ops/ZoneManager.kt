package com.atakmap.android.ideaplow.ops

import com.atakmap.android.ideaplow.model.SpecialZone
import com.atakmap.android.ideaplow.model.ZoneType

/**
 * Supervisor-defined special zones (bridge / ramp / hill / school) with
 * shorter cycle-time multipliers. Stored locally like facility geofences
 * and shared fleet-wide over CoT (`cot/codec/ZoneCotCodec`): supervisors
 * broadcast adds/edits/removes, every client applies them so freshness
 * coloring converges.
 */
class ZoneManager(
    private val persistence: KeyValuePersistence
) {

    fun interface Listener {
        fun onZonesChanged(zones: List<SpecialZone>)
    }

    private val zones = LinkedHashMap<String, SpecialZone>()
    private val listeners = mutableListOf<Listener>()

    init {
        load()
    }

    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    fun all(): List<SpecialZone> = zones.values.toList()

    fun get(id: String): SpecialZone? = zones[id]

    /** Add or replace a zone (local supervisor edit). */
    fun put(zone: SpecialZone) {
        zones[zone.id] = zone
        save()
        notifyChanged()
    }

    fun remove(zoneId: String): Boolean {
        if (zones.remove(zoneId) == null) return false
        save()
        notifyChanged()
        return true
    }

    /** Apply a zone received over CoT. Returns true if state changed. */
    fun onRemote(zone: SpecialZone, removed: Boolean): Boolean {
        val changed = if (removed) zones.remove(zone.id) != null
        else zones.put(zone.id, zone) != zone
        if (changed) {
            save()
            notifyChanged()
        }
        return changed
    }

    fun zonesContaining(lat: Double, lon: Double): List<SpecialZone> =
        zones.values.filter { it.contains(lat, lon) }

    private fun notifyChanged() {
        val snapshot = all()
        listeners.toList().forEach { it.onZonesChanged(snapshot) }
    }

    // ------------------------------------------------------- persistence

    private fun save() {
        val encoded = zones.values.joinToString("\n") { z ->
            listOf(
                esc(z.id), esc(z.name), z.type.wireName, z.cycleMultiplier,
                z.centerLat, z.centerLon, z.radiusM, encodePolygon(z.polygon)
            ).joinToString("|")
        }
        persistence.putString(KEY_ZONES, encoded)
    }

    private fun load() {
        persistence.getString(KEY_ZONES)?.lineSequence()?.forEach { line ->
            val f = line.split("|")
            if (f.size == 8) {
                val type = ZoneType.fromWireName(f[2]) ?: return@forEach
                val mult = f[3].toDoubleOrNull() ?: return@forEach
                val lat = f[4].toDoubleOrNull() ?: return@forEach
                val lon = f[5].toDoubleOrNull() ?: return@forEach
                val radius = f[6].toDoubleOrNull() ?: return@forEach
                val zone = SpecialZone(
                    unesc(f[0]), unesc(f[1]), type, mult, lat, lon, radius,
                    decodePolygon(f[7])
                )
                zones[zone.id] = zone
            }
        }
    }

    private fun esc(s: String) = s.replace("|", "&#124;").replace("\n", " ")
    private fun unesc(s: String) = s.replace("&#124;", "|")

    companion object {
        const val KEY_ZONES = "ideaplow.special_zones"

        fun encodePolygon(polygon: List<Pair<Double, Double>>): String =
            polygon.joinToString(";") { (lat, lon) -> "$lat,$lon" }

        fun decodePolygon(encoded: String): List<Pair<Double, Double>> =
            encoded.split(";").mapNotNull { pair ->
                val c = pair.split(",")
                if (c.size != 2) return@mapNotNull null
                val lat = c[0].toDoubleOrNull() ?: return@mapNotNull null
                val lon = c[1].toDoubleOrNull() ?: return@mapNotNull null
                lat to lon
            }
    }
}
