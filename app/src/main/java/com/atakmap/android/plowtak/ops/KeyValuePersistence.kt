package com.atakmap.android.plowtak.ops

/**
 * Minimal persistence port so ops managers stay framework-free and testable.
 * Production implementation is `prefs/PlowTakPreferences` (SharedPreferences);
 * tests use [InMemoryPersistence].
 */
interface KeyValuePersistence {
    fun putString(key: String, value: String)
    fun getString(key: String): String?
    fun remove(key: String)
}

/** Test/fallback implementation. */
class InMemoryPersistence : KeyValuePersistence {
    private val map = mutableMapOf<String, String>()
    override fun putString(key: String, value: String) { map[key] = value }
    override fun getString(key: String): String? = map[key]
    override fun remove(key: String) { map.remove(key) }
}
