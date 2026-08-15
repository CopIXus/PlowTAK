package com.atakmap.android.plowtak.map

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.atakmap.android.maps.MapView
import com.atakmap.android.plowtak.equipment.ManualEquipmentProvider
import com.atakmap.android.plowtak.model.EquipmentState
import com.atakmap.android.plowtak.ui.PlowControlView

/**
 * Bottom-left map HUD: miniature plow control mirroring driver toggles while
 * on shift. Stays visible while the plugin drop-down is open. Optional Mayday
 * button above the plow (confirm before send/clear).
 */
class PlowStatusHud(
    private val mapView: MapView,
    /** Plugin context — required so plugin drawables/resources resolve. */
    private val pluginContext: android.content.Context,
    private val equipment: ManualEquipmentProvider,
    private val isOnShift: () -> Boolean,
    private val hasSalt: () -> Boolean,
    private val hasTow: () -> Boolean = { false },
    private val hasWingLeft: () -> Boolean = { true },
    private val hasWingRight: () -> Boolean = { true },
    private val canSendDistress: () -> Boolean = { false },
    private val hasOwnDistress: () -> Boolean = { false },
    private val onMayday: (send: Boolean) -> Unit = {},
    /** User setting: mini plow HUD on the map (persisted). */
    private val isEnabled: () -> Boolean = { true }
) {

    private var root: LinearLayout? = null
    private var speedLabel: TextView? = null
    private var maydayBtn: Button? = null
    private var plow: PlowControlView? = null
    private var flashAnim: ObjectAnimator? = null
    private var overspeedActive = false
    private var flashUntilMs = 0L

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

    /** Kept for call-site compatibility; panel open no longer hides the HUD. */
    @Suppress("UNUSED_PARAMETER")
    fun setPanelOpen(open: Boolean) {
        refreshVisibility()
    }

    /** Show/hide based on setting + shift; refresh chrome. */
    fun refreshVisibility() {
        mapView.post {
            val show = isEnabled() && isOnShift()
            root?.visibility = if (show) View.VISIBLE else View.GONE
            if (show) {
                bindEquipment(equipment.state)
                refreshMayday()
            } else {
                setOverspeedVisual(false)
                stopFlash()
            }
        }
    }

    fun setOverspeedCondition(active: Boolean) {
        mapView.post {
            overspeedActive = active
            updateSpeedLabel()
            if (!active && System.currentTimeMillis() > flashUntilMs) stopFlash()
        }
    }

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
            maydayBtn = null
            plow = null
        }
    }

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

        val mayday = Button(ctx).apply {
            text = "MAYDAY"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            setBackgroundColor(0xFFB71C1C.toInt())
            setPadding(dp(6), dp(4), dp(6), dp(4))
            minHeight = 0
            minimumHeight = 0
            visibility = if (canSendDistress()) View.VISIBLE else View.GONE
            setOnClickListener { confirmMayday() }
        }

        val plowView = PlowControlView(ctx).apply {
            compact = true
            spreaderEnabled = hasSalt()
            towAvailable = hasTow()
            wingLeftAvailable = hasWingLeft()
            wingRightAvailable = hasWingRight()
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
            addView(
                mayday,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(4) }
            )
            addView(plowView)
            visibility = if (isEnabled() && isOnShift()) View.VISIBLE else View.GONE
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
                bottomMargin = dp(72)
            }
        }
        try {
            host.addView(column, lp)
        } catch (t: Throwable) {
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
        maydayBtn = mayday
        plow = plowView
        refreshMayday()
        android.util.Log.i(
            "PlowStatusHud",
            "attached to ${host.javaClass.simpleName} " +
                "(onShift=${isOnShift()}, visible=${column.visibility == View.VISIBLE})"
        )
    }

    private fun confirmMayday() {
        val clearing = hasOwnDistress()
        val title = if (clearing) "Clear MAYDAY" else "Send MAYDAY"
        val msg = if (clearing) {
            "Clear your MAYDAY?"
        } else {
            "Send MAYDAY / need assist?"
        }
        AlertDialog.Builder(mapView.context)
            .setTitle(title)
            .setMessage(msg)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                onMayday(!clearing)
                refreshMayday()
            }
            .show()
    }

    private fun refreshMayday() {
        val btn = maydayBtn ?: return
        val allow = canSendDistress()
        btn.visibility = if (allow) View.VISIBLE else View.GONE
        if (!allow) return
        btn.text = if (hasOwnDistress()) "CLEAR MY MAYDAY" else "MAYDAY"
    }

    private fun detach() {
        val column = root ?: return
        (column.parent as? ViewGroup)?.removeView(column)
        root = null
        speedLabel = null
        maydayBtn = null
        plow = null
    }

    private fun bindEquipment(state: EquipmentState) {
        plow?.wingLeftAvailable = hasWingLeft()
        plow?.wingRightAvailable = hasWingRight()
        plow?.spreaderEnabled = hasSalt()
        plow?.towAvailable = hasTow()
        plow?.bind(state)
        refreshMayday()
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
