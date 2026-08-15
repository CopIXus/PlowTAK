package com.atakmap.android.plowtak.map

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.atakmap.android.maps.MapView
import com.atakmap.android.plowtak.equipment.ManualEquipmentProvider
import com.atakmap.android.plowtak.model.EquipmentState
import com.atakmap.android.plowtak.ui.PlowControlView

/**
 * Bottom-left map HUD: miniature plow control mirroring driver toggles while
 * on shift. Shows a "Speed" caption and flashes during overspeed alerts
 * (TTS without a dialog).
 */
class PlowStatusHud(
    private val mapView: MapView,
    /** Plugin context — required so plugin drawables/resources resolve. */
    private val pluginContext: android.content.Context,
    private val equipment: ManualEquipmentProvider,
    private val isOnShift: () -> Boolean,
    private val hasSalt: () -> Boolean,
    private val hasTow: () -> Boolean = { false },
    /** User setting: mini plow HUD on the map (persisted). */
    private val isEnabled: () -> Boolean = { true }
) {

    private var root: LinearLayout? = null
    private var speedLabel: TextView? = null
    private var plow: PlowControlView? = null
    private var flashAnim: ObjectAnimator? = null
    private var overspeedActive = false
    private var flashUntilMs = 0L
    private var panelOpen = false

    private val eqListener = object : com.atakmap.android.plowtak.equipment.EquipmentProvider.Listener {
        override fun onEquipmentChanged(state: EquipmentState) {
            mapView.post { bindEquipment(state) }
        }
    }

    fun start() {
        if (root != null) return
        mapView.post { attach() }
        equipment.addListener(eqListener)
    }

    fun dispose() {
        equipment.removeListener(eqListener)
        stopFlash()
        mapView.post { detach() }
    }

    /** HUD shows only when the plugin panel is closed (mirrors the drop-down). */
    fun setPanelOpen(open: Boolean) {
        panelOpen = open
        refreshVisibility()
    }

    /** Show/hide based on setting + shift + panel state; refresh chrome. */
    fun refreshVisibility() {
        mapView.post {
            val show = isEnabled() && isOnShift() && !panelOpen
            root?.visibility = if (show) View.VISIBLE else View.GONE
            if (show) {
                plow?.spreaderEnabled = hasSalt()
                plow?.towAvailable = hasTow()
                bindEquipment(equipment.state)
            } else {
                setOverspeedVisual(false)
                stopFlash()
            }
        }
    }

    /**
     * Continuous overspeed condition (blade down above max plow speed).
     * Shows the Speed caption; [flashAlert] drives the pulse during TTS.
     */
    fun setOverspeedCondition(active: Boolean) {
        mapView.post {
            overspeedActive = active
            updateSpeedLabel()
            if (!active && System.currentTimeMillis() > flashUntilMs) stopFlash()
        }
    }

    /** Brief flash while overspeed TTS is speaking (no popup). */
    fun flashAlert(durationMs: Long = 4_000L) {
        mapView.post {
            flashUntilMs = System.currentTimeMillis() + durationMs
            updateSpeedLabel()
            startFlash()
            mapView.postDelayed({
                if (System.currentTimeMillis() >= flashUntilMs) stopFlash()
                updateSpeedLabel()
            }, durationMs + 50L)
        }
    }

    private fun attach() {
        if (root != null) return
        try {
            attachUnsafe()
        } catch (t: Throwable) {
            android.util.Log.e("PlowStatusHud", "attach failed; HUD disabled", t)
            root = null
            speedLabel = null
            plow = null
        }
    }

    /**
     * Host inside the map's own container hierarchy so ATAK drop-downs, the
     * Tools grid and other chrome naturally draw ABOVE the HUD (it must
     * never cover menus). The map's direct parent can be a LinearLayout,
     * which STACKS children instead of overlaying them — a HUD added there
     * lays out off-screen below the full-height map. So walk up to the
     * nearest overlay-capable ancestor (FrameLayout/RelativeLayout);
     * android.R.id.content is itself a FrameLayout and acts as the final
     * fallback.
     */
    private fun findHost(): ViewGroup? {
        var p: android.view.ViewParent? = mapView.parent
        while (p is ViewGroup) {
            if (p is FrameLayout || p is android.widget.RelativeLayout) return p
            p = p.parent
        }
        val activity = mapView.context as? android.app.Activity
        return activity?.findViewById(android.R.id.content)
    }

    private fun attachUnsafe() {
        val host = findHost() ?: run {
            android.util.Log.w("PlowStatusHud", "no host view found; HUD not attached")
            return
        }
        // Views MUST be created with the plugin context: PlowControlView loads
        // R.drawable.plow_control_art, and plugin resource IDs do not resolve
        // through the ATAK activity context (decode fails → blank/crash).
        val ctx = pluginContext
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val label = TextView(ctx).apply {
            text = "Speed"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER_HORIZONTAL
            setShadowLayer(3f, 0f, 1f, Color.BLACK)
            visibility = View.GONE
            setPadding(0, 0, 0, dp(2))
        }
        val plowView = PlowControlView(ctx).apply {
            spreaderEnabled = hasSalt()
            towAvailable = hasTow()
            layoutParams = LinearLayout.LayoutParams(
                dp(HUD_WIDTH_DP),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            bind(equipment.state)
            onBladeToggle = { equipment.setBladeDown(it) }
            onWingLeftToggle = { equipment.setWingLeft(it) }
            onWingRightToggle = { equipment.setWingRight(it) }
            onTowToggle = { equipment.setTowDeployed(it) }
            onSpreaderToggle = { equipment.setSpreading(it) }
        }

        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
            setBackgroundColor(0x99000000.toInt())
            elevation = dp(8).toFloat()
            contentDescription = "PlowTAK status HUD"
            addView(
                label,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(plowView)
            visibility = if (isEnabled() && isOnShift() && !panelOpen) {
                View.VISIBLE
            } else {
                View.GONE
            }
        }

        val lp = when (host) {
            is FrameLayout -> FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.START
            ).apply {
                leftMargin = dp(8)
                bottomMargin = dp(72)
            }
            is android.widget.RelativeLayout -> android.widget.RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                addRule(android.widget.RelativeLayout.ALIGN_PARENT_BOTTOM)
                addRule(android.widget.RelativeLayout.ALIGN_PARENT_START)
                leftMargin = dp(8)
                bottomMargin = dp(72)
            }
            else -> ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = dp(8)
                // Absolute-ish: place near bottom via translation after layout.
                bottomMargin = dp(72)
            }
        }
        try {
            host.addView(column, lp)
        } catch (t: Throwable) {
            // Fallback: default LayoutParams, pin with translationY after measure.
            host.addView(column)
            column.post {
                val parentH = host.height
                val h = column.height
                if (parentH > 0 && h > 0) {
                    column.translationX = dp(8).toFloat()
                    column.translationY = (parentH - h - dp(72)).toFloat()
                }
            }
        }
        column.bringToFront()
        root = column
        speedLabel = label
        plow = plowView
        android.util.Log.i(
            "PlowStatusHud",
            "attached to ${host.javaClass.simpleName} " +
                "(onShift=${isOnShift()}, visible=${column.visibility == View.VISIBLE})"
        )
    }

    private fun detach() {
        val column = root ?: return
        (column.parent as? ViewGroup)?.removeView(column)
        root = null
        speedLabel = null
        plow = null
    }

    private fun bindEquipment(state: EquipmentState) {
        plow?.bind(state)
        plow?.spreaderEnabled = hasSalt()
        plow?.towAvailable = hasTow()
    }

    private fun updateSpeedLabel() {
        val show = overspeedActive || System.currentTimeMillis() < flashUntilMs
        setOverspeedVisual(show)
    }

    private fun setOverspeedVisual(show: Boolean) {
        speedLabel?.visibility = if (show) View.VISIBLE else View.GONE
        speedLabel?.setTextColor(if (show) 0xFFFF5252.toInt() else Color.WHITE)
    }

    private fun startFlash() {
        val target = root ?: return
        if (flashAnim?.isRunning == true) return
        flashAnim = ObjectAnimator.ofFloat(target, View.ALPHA, 1f, 0.25f, 1f).apply {
            duration = 450L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    private fun stopFlash() {
        flashAnim?.cancel()
        flashAnim = null
        root?.alpha = 1f
    }

    companion object {
        private const val HUD_WIDTH_DP = 140
    }
}
