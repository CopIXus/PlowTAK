package com.atakmap.android.ideaplow.model

/**
 * Route priority classes for per-priority cycle times (P1 interstates /
 * emergency routes down to residential). Until agency GIS import (Phase 3)
 * assigns priorities to road segments, locally recorded coverage defaults to
 * [DEFAULT]; the model and cycle resolution are priority-aware now so the
 * data path does not change later.
 */
enum class RoutePriority(val wireName: String, val label: String) {
    P1("p1", "Priority 1"),
    P2("p2", "Priority 2"),
    P3("p3", "Priority 3"),
    DEFAULT("default", "Default");

    companion object {
        fun fromWireName(name: String?): RoutePriority? =
            entries.firstOrNull { it.wireName.equals(name, ignoreCase = true) }
    }
}
