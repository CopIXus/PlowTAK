package com.atakmap.android.ideaplow.ui

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

/**
 * Night high-contrast palette for the driver panel: pure black background
 * with dim amber text to protect dark adaptation in the cab. Applied by
 * walking the view tree; day mode restores the standard palette colors.
 */
object NightPalette {

    private const val NIGHT_BG = 0xFF000000.toInt()
    private const val NIGHT_TEXT = 0xFFFFB000.toInt()
    private const val NIGHT_BUTTON = 0xFF1A1200.toInt()

    private const val DAY_BG = 0xFF1B262E.toInt()   // ideaplow_bg
    private const val DAY_TEXT = 0xFFECEFF1.toInt() // ideaplow_text

    fun apply(root: View, night: Boolean) {
        root.setBackgroundColor(if (night) NIGHT_BG else DAY_BG)
        walk(root, night)
    }

    private fun walk(view: View, night: Boolean) {
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) walk(view.getChildAt(i), night)
        }
        when (view) {
            is Button -> {
                view.setTextColor(if (night) NIGHT_TEXT else DAY_TEXT)
                if (night) {
                    view.backgroundTintList =
                        android.content.res.ColorStateList.valueOf(NIGHT_BUTTON)
                }
                // Day-mode button tints are restored by the panel's own
                // refresh(), which re-tints every button from live state.
            }
            is EditText -> view.setTextColor(if (night) NIGHT_TEXT else DAY_TEXT)
            is TextView -> view.setTextColor(if (night) NIGHT_TEXT else DAY_TEXT)
        }
    }
}
