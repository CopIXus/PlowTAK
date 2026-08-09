package com.atakmap.android.plowtak.ui

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import com.atakmap.android.plowtak.R
import com.atakmap.android.preference.PluginPreferenceFragment

/**
 * ATAK Tool Preferences entry for PlowTAK. Keys mirror
 * [com.atakmap.android.plowtak.prefs.PlowTakPreferences] so the in-panel
 * Vehicle setup and this screen stay on the same SharedPreferences file
 * (`plowtak_prefs` is not used here — ATAK host prefs; see note below).
 *
 * Note: PluginPreferenceFragment persists into the ATAK host preference
 * store. The Vehicle setup panel continues to own capability profile fields;
 * this screen exposes operational toggles documented for supervisors.
 */
class PlowTakPreferenceFragment : PluginPreferenceFragment {

    @SuppressLint("ValidFragment")
    constructor(pluginContext: Context) : super(pluginContext, R.xml.plowtak_preferences) {
        staticPluginContext = pluginContext
    }

    /** Zero-arg required by the fragment manager after process recreation. */
    constructor() : super(staticPluginContext, R.xml.plowtak_preferences)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun getSubTitle(): String =
        getSubTitle("Tool Preferences", "PlowTAK Preferences")

    companion object {
        private var staticPluginContext: Context? = null
        const val KEY = "plowTakPreference"
    }
}
