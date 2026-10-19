package com.atakmap.android.plowtak.ui

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import com.atakmap.android.plowtak.R
import com.atakmap.android.plowtak.prefs.PlowTakPreferences
import com.atakmap.android.preference.PluginPreferenceFragment

/**
 * ATAK Tool Preferences entry for PlowTAK. Keys mirror
 * [com.atakmap.android.plowtak.prefs.PlowTakPreferences] so the in-panel
 * Vehicle setup and this screen stay on the same SharedPreferences file
 * (`plowtak_prefs`). Vehicle setup owns the capability profile; this screen
 * owns the operational toggles.
 */
class PlowTakPreferenceFragment : PluginPreferenceFragment {

    @SuppressLint("ValidFragment")
    constructor(pluginContext: Context) : super(pluginContext, R.xml.plowtak_preferences) {
        staticPluginContext = pluginContext
    }

    /** Zero-arg required by the fragment manager after process recreation. */
    constructor() : super(staticPluginContext, R.xml.plowtak_preferences)

    override fun onCreate(savedInstanceState: Bundle?) {
        preferenceManager.sharedPreferencesName = PlowTakPreferences.PREFS_NAME
        super.onCreate(savedInstanceState)
    }

    override fun getSubTitle(): String =
        getSubTitle("Tool Preferences", "PlowTAK Preferences")

    companion object {
        private var staticPluginContext: Context? = null
        const val KEY = "plowTakPreference"
    }
}
