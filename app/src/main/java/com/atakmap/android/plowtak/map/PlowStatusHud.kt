package com.atakmap.android.plowtak.map

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.atakmap.android.maps.MapView
import com.atakmap.android.plowtak.R
import com.atakmap.android.plowtak.equipment.ManualEquipmentProvider
import com.atakmap.android.plowtak.model.EquipmentState
import com.atakmap.android.plowtak.model.HazardType
import com.atakmap.android.plowtak.model.RoadCondition
import com.atakmap.android.plowtak.ui.PlowControlView

/**
 * Bottom-left map HUD: miniature plow control, quick HAZARD / ROAD report
 * triggers with a slide-out type palette, and optional Mayday.
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
    /** Returns true when the report was accepted (GPS present). */
    private val onHazard: (HazardType) -> Boolean = { false },
    private val onCondition: (RoadCondition) -> Boolean = { false },
    /** User setting: mini plow HUD on the map (persisted). */
    private val isEnabled: () -> Boolean = { true }
) {

    private enum class PaletteMode { NONE, HAZARD, ROAD }

    private var root: LinearLayout? = null
    private var plowColumn: LinearLayout? = null
    private var speedLabel: TextView? = null
    private var maydayBtn: Button? = null
    private var plow: PlowControlView? = null
    private var hazardBtn: LinearLayout? = null
    private var roadBtn: LinearLayout? = null
    private var palette: LinearLayout? = null
    private var paletteGrid: LinearLayout? = null
    private var flashAnim: ObjectAnimator? = null
    private var overspeedActive = false
    private var flashUntilMs = 0L
    private var paletteMode = PaletteMode.NONE

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
                dismissPalette(animate = false)
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
            clearRefs()
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
        val dp: (Int) -> Int = { v -> (v * density).toInt() }

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

        val hazardTrigger = makeTriggerButton(
            ctx, dp, R.drawable.ic_hud_hazard, "HAZARD"
        ) { togglePalette(PaletteMode.HAZARD) }
        val roadTrigger = makeTriggerButton(
            ctx, dp, R.drawable.ic_hud_road, "ROAD"
        ) { togglePalette(PaletteMode.ROAD) }

        val triggerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                dp(HUD_WIDTH_DP),
                dp(TRIGGER_H_DP)
            ).apply { topMargin = dp(4) }
            addView(
                hazardTrigger,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            )
            addView(
                View(ctx).apply { setBackgroundColor(BAR_COLOR) },
                LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT)
            )
            addView(
                roadTrigger,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            )
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
            addView(triggerRow)
        }

        val grid = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val closeBtn = FrameLayout(ctx).apply {
            setBackgroundColor(FILL_IDLE)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(CELL_DP)
            )
            isClickable = true
            setOnClickListener { dismissPalette(animate = true) }
            addView(
                ImageView(ctx).apply {
                    setImageResource(R.drawable.ic_hud_close)
                    setColorFilter(Color.WHITE)
                },
                FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER)
            )
            addView(
                View(ctx).apply { setBackgroundColor(BAR_COLOR) },
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1), Gravity.TOP
                )
            )
        }

        val palettePanel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            setBackgroundColor(0x99000000.toInt())
            elevation = dp(8).toFloat()
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                dp(PALETTE_WIDTH_DP),
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(4) }
            addView(grid)
            addView(closeBtn)
        }

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            addView(column)
            addView(palettePanel)
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
            host.addView(row, lp)
        } catch (t: Throwable) {
            host.addView(row)
            row.post {
                val parentH = host.height
                val h = row.height
                if (parentH > 0 && h > 0) {
                    row.translationX = dp(8).toFloat()
                    row.translationY = (parentH - h - dp(72)).toFloat()
                }
            }
        }
        row.bringToFront()
        root = row
        plowColumn = column
        speedLabel = label
        maydayBtn = mayday
        plow = plowView
        hazardBtn = hazardTrigger
        roadBtn = roadTrigger
        palette = palettePanel
        paletteGrid = grid
        refreshMayday()
        android.util.Log.i(
            "PlowStatusHud",
            "attached to ${host.javaClass.simpleName} " +
                "(onShift=${isOnShift()}, visible=${row.visibility == View.VISIBLE})"
        )
    }

    private fun makeTriggerButton(
        ctx: android.content.Context,
        dp: (Int) -> Int,
        iconRes: Int,
        label: String,
        onClick: () -> Unit
    ): LinearLayout {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(FILL_IDLE)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            addView(
                ImageView(ctx).apply {
                    setImageResource(iconRes)
                    setColorFilter(Color.WHITE)
                    layoutParams = LinearLayout.LayoutParams(dp(28), dp(18)).also {
                        it.gravity = Gravity.CENTER_HORIZONTAL
                    }
                }
            )
            addView(
                TextView(ctx).apply {
                    text = label
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                }
            )
        }
    }

    private fun togglePalette(mode: PaletteMode) {
        if (paletteMode == mode) {
            dismissPalette(animate = true)
        } else {
            showPalette(mode)
        }
    }

    private fun showPalette(mode: PaletteMode) {
        val panel = palette ?: return
        val grid = paletteGrid ?: return
        paletteMode = mode
        styleTriggers()
        populatePaletteGrid(grid, mode)
        panel.visibility = View.VISIBLE
        panel.post {
            val h = panel.height.toFloat().coerceAtLeast(1f)
            panel.translationY = h
            panel.animate()
                .translationY(0f)
                .setDuration(180L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    private fun dismissPalette(animate: Boolean) {
        val panel = palette ?: return
        paletteMode = PaletteMode.NONE
        styleTriggers()
        if (!animate || panel.visibility != View.VISIBLE) {
            panel.animate().cancel()
            panel.visibility = View.GONE
            panel.translationY = 0f
            return
        }
        val h = panel.height.toFloat().coerceAtLeast(1f)
        panel.animate()
            .translationY(h)
            .setDuration(150L)
            .setInterpolator(DecelerateInterpolator())
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    panel.visibility = View.GONE
                    panel.translationY = 0f
                    panel.animate().setListener(null)
                }
            })
            .start()
    }

    private fun styleTriggers() {
        hazardBtn?.setBackgroundColor(
            if (paletteMode == PaletteMode.HAZARD) FILL_ACTIVE else FILL_IDLE
        )
        roadBtn?.setBackgroundColor(
            if (paletteMode == PaletteMode.ROAD) FILL_ACTIVE else FILL_IDLE
        )
    }

    private fun populatePaletteGrid(grid: LinearLayout, mode: PaletteMode) {
        grid.removeAllViews()
        val ctx = pluginContext
        val density = ctx.resources.displayMetrics.density
        val dp: (Int) -> Int = { v -> (v * density).toInt() }

        data class Tile(val label: String, val icon: Int, val click: () -> Unit)

        val tiles: List<Tile> = when (mode) {
            PaletteMode.HAZARD -> HazardType.entries.map { hz ->
                Tile(hz.label, hazardIcon(hz)) {
                    if (onHazard(hz)) {
                        Toast.makeText(
                            mapView.context, "Hazard: ${hz.label}", Toast.LENGTH_SHORT
                        ).show()
                        dismissPalette(animate = true)
                    } else {
                        Toast.makeText(
                            mapView.context, "No GPS — cannot report", Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            PaletteMode.ROAD -> RoadCondition.entries.map { c ->
                Tile(c.label, conditionIcon(c)) {
                    if (onCondition(c)) {
                        Toast.makeText(
                            mapView.context, "Road: ${c.label}", Toast.LENGTH_SHORT
                        ).show()
                        dismissPalette(animate = true)
                    } else {
                        Toast.makeText(
                            mapView.context, "No GPS — cannot report", Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            PaletteMode.NONE -> emptyList()
        }

        var i = 0
        while (i < tiles.size) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(CELL_DP)
                )
            }
            for (col in 0 until 2) {
                val tile = tiles.getOrNull(i + col)
                if (tile == null) {
                    row.addView(
                        View(ctx),
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                    )
                } else {
                    row.addView(
                        makePaletteTile(ctx, dp, tile.label, tile.icon, tile.click),
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                    )
                }
                if (col == 0) {
                    row.addView(
                        View(ctx).apply { setBackgroundColor(BAR_COLOR) },
                        LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT)
                    )
                }
            }
            grid.addView(row)
            grid.addView(
                View(ctx).apply { setBackgroundColor(BAR_COLOR) },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
                )
            )
            i += 2
        }
    }

    private fun makePaletteTile(
        ctx: android.content.Context,
        dp: (Int) -> Int,
        label: String,
        iconRes: Int,
        onClick: () -> Unit
    ): FrameLayout {
        val cell = FrameLayout(ctx).apply {
            setBackgroundColor(FILL_IDLE)
            isClickable = true
            setOnClickListener { onClick() }
        }
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        content.addView(
            ImageView(ctx).apply {
                setImageResource(iconRes)
                setColorFilter(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).also {
                    it.gravity = Gravity.CENTER_HORIZONTAL
                }
            }
        )
        content.addView(
            TextView(ctx).apply {
                text = label
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
                gravity = Gravity.CENTER
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                includeFontPadding = false
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        cell.addView(
            content,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        return cell
    }

    private fun hazardIcon(hazard: HazardType): Int = when (hazard) {
        HazardType.STRANDED_VEHICLE -> R.drawable.ic_ops_stranded
        HazardType.TREE_WIRES_DOWN -> R.drawable.ic_ops_tree_wires
        HazardType.ABANDONED_CAR -> R.drawable.ic_ops_abandoned
        HazardType.DRIFT_ICE -> R.drawable.ic_ops_drift_ice
        HazardType.DAMAGE -> R.drawable.ic_ops_damage
    }

    private fun conditionIcon(condition: RoadCondition): Int = when (condition) {
        RoadCondition.BARE -> R.drawable.ic_ops_bare
        RoadCondition.WET -> R.drawable.ic_ops_wet
        RoadCondition.SLUSH -> R.drawable.ic_ops_slush
        RoadCondition.SNOW_COVERED -> R.drawable.ic_ops_snow
        RoadCondition.ICE -> R.drawable.ic_ops_ice
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
        clearRefs()
    }

    private fun clearRefs() {
        root = null
        plowColumn = null
        speedLabel = null
        maydayBtn = null
        plow = null
        hazardBtn = null
        roadBtn = null
        palette = null
        paletteGrid = null
        paletteMode = PaletteMode.NONE
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
        private const val PALETTE_WIDTH_DP = 160
        private const val CELL_DP = 64
        private const val TRIGGER_H_DP = 48
        private const val FILL_IDLE = 0xFF212121.toInt()
        private const val FILL_ACTIVE = 0xFF2ECC40.toInt()
        private const val BAR_COLOR = 0xFF888888.toInt()
    }
}
