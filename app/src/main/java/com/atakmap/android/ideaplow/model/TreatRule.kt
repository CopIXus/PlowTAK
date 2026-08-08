package com.atakmap.android.ideaplow.model

/**
 * Configurable rule for when a capable unit is considered "treating" (and
 * therefore painting coverage). Evaluated by [CapabilityRules.isTreating].
 */
enum class TreatRule(val wireName: String, val label: String) {
    BLADE_DOWN_ONLY("blade", "Blade down only"),
    SALT_ON_ONLY("salt", "Salt on only"),
    EITHER("either", "Blade down OR salt on"),
    BOTH("both", "Blade down AND salt on");

    companion object {
        fun fromWireName(name: String?): TreatRule? =
            entries.firstOrNull { it.wireName.equals(name, ignoreCase = true) }
    }
}
