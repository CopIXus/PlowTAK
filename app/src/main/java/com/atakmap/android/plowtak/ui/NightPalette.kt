package com.atakmap.android.plowtak.ui

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

    private const val DAY_BG = 0xFF000000.toInt()   // plowtak_bg (ATAK black)
    private const val DAY_TEXT = 0xFFF5F5F5.toInt() // plowtak_text

    fun apply(root: View, night: Boolean) {
        root.setBackgroundColor(if (night) NIGHT_BG else DAY_BG)
        walk(root, night)
    }

    private fun walk(view: View, night: Boolean) {
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) walk(view.getChildAt(i), night)
        }
        // OpsTileGrid cells own fill + manatee bars; never override them.
        if (OpsTileGrid.isOpsTile(view) || isInsideOpsTile(view)) {
            if (view is TextView && view !is EditText && view !is Button) {
                view.setTextColor(if (night) NIGHT_TEXT else DAY_TEXT)
            }
            return
        }
        when (view) {
            is Button -> {
                view.setTextColor(if (night) NIGHT_TEXT else DAY_TEXT)
                if (night) {
                    view.backgroundTintList =
                        android.content.res.ColorStateList.valueOf(NIGHT_BUTTON)
                }
            }
            is EditText -> view.setTextColor(if (night) NIGHT_TEXT else DAY_TEXT)
            is TextView -> view.setTextColor(if (night) NIGHT_TEXT else DAY_TEXT)
        }
    }

    private fun isInsideOpsTile(view: View): Boolean {
        var p = view.parent
        while (p is View) {
            if (OpsTileGrid.isOpsTile(p)) return true
            p = p.parent
        }
        return false
    }
}
