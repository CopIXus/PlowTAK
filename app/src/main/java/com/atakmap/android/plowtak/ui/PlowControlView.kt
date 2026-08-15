package com.atakmap.android.plowtak.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.atakmap.android.plowtak.R
import com.atakmap.android.plowtak.model.EquipmentState
import com.atakmap.android.plowtak.model.WidthPreset

/**
 * Front-view plow control: left wing | blade | right wing, optional tow plow
 * behind the main blade, plus a spreader bar across the bottom. Green fill
 * stays inside each part outline. Uninstalled wings are omitted entirely.
 *
 * [compact]=true (HUD): small stacked tow/spreader.
 * [compact]=false (main panel): larger controls; tow+spreader side-by-side when both fitted.
 */
class PlowControlView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    var compact: Boolean = true
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    var bladeDown: Boolean = false
        set(value) {
            field = value
            invalidate()
        }
    var wingLeft: Boolean = false
        set(value) {
            field = value
            invalidate()
        }
    var wingRight: Boolean = false
        set(value) {
            field = value
            invalidate()
        }
    var towDeployed: Boolean = false
        set(value) {
            field = value
            invalidate()
        }
    var spreadingOn: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    /** When false, spreader hit target and fill are hidden. */
    var spreaderEnabled: Boolean = true
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    /** When false, tow silhouette and hit target are hidden. */
    var towAvailable: Boolean = false
        set(value) {
            field = value
            if (!value) towDeployed = false
            requestLayout()
            invalidate()
        }

    /** When false, left wing is omitted (no outline, fill, or hit). */
    var wingLeftAvailable: Boolean = true
        set(value) {
            field = value
            if (!value) wingLeft = false
            invalidate()
        }

    /** When false, right wing is omitted (no outline, fill, or hit). */
    var wingRightAvailable: Boolean = true
        set(value) {
            field = value
            if (!value) wingRight = false
            invalidate()
        }

    var onBladeToggle: ((Boolean) -> Unit)? = null
    var onWingLeftToggle: ((Boolean) -> Unit)? = null
    var onWingRightToggle: ((Boolean) -> Unit)? = null
    var onTowToggle: ((Boolean) -> Unit)? = null
    var onSpreaderToggle: ((Boolean) -> Unit)? = null

    private val art: Bitmap = loadArt(context)
    private val artSrc = Rect(0, 0, art.width, art.height)
    private val artDst = RectF()

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLOR_ACTIVE
    }
    private val artPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val towOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2).toFloat()
        color = 0xFFFFFFFF.toInt()
    }
    private val towLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        textSize = sp(10)
        isFakeBoldText = true
    }
    private val spreaderOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2).toFloat()
        color = 0xFFFFFFFF.toInt()
    }
    private val spreaderLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        textSize = sp(11)
        isFakeBoldText = true
    }

    private val leftWingHit = RectF()
    private val bladeHit = RectF()
    private val rightWingHit = RectF()
    private val towHit = RectF()
    private val spreaderHit = RectF()

    private val leftWingPath = Path()
    private val bladePath = Path()
    private val rightWingPath = Path()
    private val towPath = Path()

    fun bind(state: EquipmentState) {
        bladeDown = state.bladeDown
        wingLeft = wingLeftAvailable && state.wingLeftExtended
        wingRight = wingRightAvailable && state.wingRightExtended
        towDeployed = towAvailable && state.widthPreset == WidthPreset.TOW && !state.wingsExtended
        spreadingOn = state.spreadingOn
    }

    private fun towH(): Int = when {
        !towAvailable -> 0
        compact -> dp(28)
        else -> dp(44)
    }

    private fun spreaderH(): Int = when {
        !spreaderEnabled -> 0
        compact -> dp(36)
        else -> dp(52)
    }

    /** When both fitted and not compact, tow+spreader share one row. */
    private fun sideBySideExtras(): Boolean =
        !compact && towAvailable && spreaderEnabled

    private fun extrasH(): Int = when {
        sideBySideExtras() -> maxOf(towH(), spreaderH())
        else -> towH() + spreaderH()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(dp(120))
        val aspect = art.height.toFloat() / art.width.toFloat()
        val artH = (w * aspect).toInt()
        setMeasuredDimension(w, artH + extrasH())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        artDst.set(0f, 0f, w.toFloat(), (h - extrasH()).toFloat().coerceAtLeast(1f))
        layoutRegions()
    }

    private fun layoutRegions() {
        leftWingHit.set(fx(0.04f), fy(0.32f), fx(0.30f), fy(0.72f))
        bladeHit.set(fx(0.30f), fy(0.28f), fx(0.70f), fy(0.72f))
        rightWingHit.set(fx(0.70f), fy(0.32f), fx(0.96f), fy(0.72f))
        if (!wingLeftAvailable) leftWingHit.setEmpty()
        if (!wingRightAvailable) rightWingHit.setEmpty()

        leftWingPath.reset()
        if (wingLeftAvailable) {
            leftWingPath.moveTo(fx(0.295f), fy(0.38f))
            leftWingPath.lineTo(fx(0.16f), fy(0.40f))
            leftWingPath.quadTo(fx(0.08f), fy(0.44f), fx(0.075f), fy(0.54f))
            leftWingPath.lineTo(fx(0.085f), fy(0.64f))
            leftWingPath.quadTo(fx(0.14f), fy(0.70f), fx(0.24f), fy(0.68f))
            leftWingPath.lineTo(fx(0.295f), fy(0.66f))
            leftWingPath.close()
        }

        bladePath.reset()
        bladePath.moveTo(fx(0.305f), fy(0.36f))
        bladePath.quadTo(fx(0.50f), fy(0.32f), fx(0.695f), fy(0.36f))
        bladePath.lineTo(fx(0.695f), fy(0.66f))
        bladePath.quadTo(fx(0.50f), fy(0.70f), fx(0.305f), fy(0.66f))
        bladePath.close()

        rightWingPath.reset()
        if (wingRightAvailable) {
            rightWingPath.moveTo(fx(0.705f), fy(0.38f))
            rightWingPath.lineTo(fx(0.84f), fy(0.40f))
            rightWingPath.quadTo(fx(0.92f), fy(0.44f), fx(0.925f), fy(0.54f))
            rightWingPath.lineTo(fx(0.915f), fy(0.64f))
            rightWingPath.quadTo(fx(0.86f), fy(0.70f), fx(0.76f), fy(0.68f))
            rightWingPath.lineTo(fx(0.705f), fy(0.66f))
            rightWingPath.close()
        }

        towPath.reset()
        towHit.setEmpty()
        spreaderHit.setEmpty()

        if (sideBySideExtras()) {
            val top = artDst.bottom + dp(4)
            val bottom = top + maxOf(towH(), spreaderH()) - dp(4)
            val mid = width / 2f
            val gap = dp(6).toFloat()
            towHit.set(dp(4).toFloat(), top, mid - gap / 2f, bottom)
            spreaderHit.set(mid + gap / 2f, top, width - dp(4).toFloat(), bottom)
            buildTowPath()
        } else {
            if (towAvailable) {
                val top = artDst.bottom + dp(2)
                val bottom = top + towH() - dp(4)
                towHit.set(dp(4).toFloat(), top, width - dp(4).toFloat(), bottom)
                buildTowPath()
            }
            if (spreaderEnabled) {
                val top = if (towAvailable) towHit.bottom + dp(4) else artDst.bottom + dp(4)
                val bottom = height.toFloat() - dp(2)
                spreaderHit.set(dp(8).toFloat(), top, width - dp(8).toFloat(), bottom)
            }
        }

        if (!compact) {
            towLabel.textSize = sp(13)
            spreaderLabel.textSize = sp(13)
        } else {
            towLabel.textSize = sp(10)
            spreaderLabel.textSize = sp(11)
        }
    }

    private fun buildTowPath() {
        towPath.reset()
        towPath.moveTo(towHit.left + dp(6), towHit.top + dp(6))
        towPath.quadTo(towHit.centerX(), towHit.top + dp(1), towHit.right - dp(6), towHit.top + dp(6))
        towPath.lineTo(towHit.right - dp(4), towHit.bottom - dp(4))
        towPath.quadTo(towHit.centerX(), towHit.bottom - dp(1), towHit.left + dp(4), towHit.bottom - dp(4))
        towPath.close()
    }

    private fun fx(frac: Float): Float = artDst.left + artDst.width() * frac
    private fun fy(frac: Float): Float = artDst.top + artDst.height() * frac

    override fun onDraw(canvas: Canvas) {
        val save = canvas.saveLayer(artDst, null)
        if (wingLeftAvailable && wingLeft) canvas.drawPath(leftWingPath, fillPaint)
        if (bladeDown) canvas.drawPath(bladePath, fillPaint)
        if (wingRightAvailable && wingRight) canvas.drawPath(rightWingPath, fillPaint)
        canvas.drawBitmap(art, artSrc, artDst, artPaint)
        // Erase uninstalled wing outlines from the stock art.
        if (!wingLeftAvailable) {
            canvas.drawRect(fx(0.0f), fy(0.28f), fx(0.30f), fy(0.75f), clearPaint)
        }
        if (!wingRightAvailable) {
            canvas.drawRect(fx(0.70f), fy(0.28f), fx(1.0f), fy(0.75f), clearPaint)
        }
        canvas.restoreToCount(save)

        if (towAvailable && !towHit.isEmpty) {
            if (towDeployed) canvas.drawPath(towPath, fillPaint)
            canvas.drawPath(towPath, towOutline)
            val label = if (towDeployed) "TOW ON" else "TOW"
            val cy = towHit.centerY() - (towLabel.descent() + towLabel.ascent()) / 2f
            canvas.drawText(label, towHit.centerX(), cy, towLabel)
        }

        if (spreaderEnabled && !spreaderHit.isEmpty) {
            val r = dp(8).toFloat()
            if (spreadingOn) {
                canvas.drawRoundRect(spreaderHit, r, r, fillPaint)
            }
            canvas.drawRoundRect(spreaderHit, r, r, spreaderOutline)
            val label = if (spreadingOn) "SPREADER ON" else "SPREADER"
            val cy = spreaderHit.centerY() - (spreaderLabel.descent() + spreaderLabel.ascent()) / 2f
            canvas.drawText(label, spreaderHit.centerX(), cy, spreaderLabel)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        val x = event.x
        val y = event.y
        when {
            spreaderEnabled && spreaderHit.contains(x, y) -> {
                spreadingOn = !spreadingOn
                onSpreaderToggle?.invoke(spreadingOn)
                invalidate()
            }
            towAvailable && towHit.contains(x, y) -> {
                towDeployed = !towDeployed
                if (towDeployed) {
                    wingLeft = false
                    wingRight = false
                }
                onTowToggle?.invoke(towDeployed)
                invalidate()
            }
            wingLeftAvailable && leftWingHit.contains(x, y) -> {
                wingLeft = !wingLeft
                if (wingLeft) towDeployed = false
                onWingLeftToggle?.invoke(wingLeft)
                invalidate()
            }
            wingRightAvailable && rightWingHit.contains(x, y) -> {
                wingRight = !wingRight
                if (wingRight) towDeployed = false
                onWingRightToggle?.invoke(wingRight)
                invalidate()
            }
            bladeHit.contains(x, y) -> {
                bladeDown = !bladeDown
                onBladeToggle?.invoke(bladeDown)
                invalidate()
            }
        }
        return true
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun sp(v: Int): Float = v * resources.displayMetrics.scaledDensity

    companion object {
        private const val COLOR_ACTIVE = 0xFF2ECC40.toInt()
        private const val BLACK_LUMA_CUTOFF = 48

        private fun loadArt(context: Context): Bitmap {
            val raw = BitmapFactory.decodeResource(context.resources, R.drawable.plow_control_art)
                ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            return makeBlackTransparent(raw)
        }

        private fun makeBlackTransparent(src: Bitmap): Bitmap {
            val out = src.copy(Bitmap.Config.ARGB_8888, true) ?: return src
            val w = out.width
            val h = out.height
            val pixels = IntArray(w * h)
            out.getPixels(pixels, 0, w, 0, 0, w, h)
            for (i in pixels.indices) {
                val c = pixels[i]
                val r = (c ushr 16) and 0xFF
                val g = (c ushr 8) and 0xFF
                val b = c and 0xFF
                val luma = (r * 30 + g * 59 + b * 11) / 100
                pixels[i] = if (luma < BLACK_LUMA_CUTOFF) {
                    0
                } else {
                    (luma shl 24) or 0x00FFFFFF
                }
            }
            out.setPixels(pixels, 0, w, 0, 0, w, h)
            if (out !== src) src.recycle()
            return cropToOpaqueContent(out)
        }

        private fun cropToOpaqueContent(src: Bitmap): Bitmap {
            val w = src.width
            val h = src.height
            val pixels = IntArray(w * h)
            src.getPixels(pixels, 0, w, 0, 0, w, h)
            var top = 0
            var bottom = h - 1
            var left = 0
            var right = w - 1
            fun rowHasInk(y: Int): Boolean {
                val row = y * w
                for (x in 0 until w) if ((pixels[row + x] ushr 24) > 8) return true
                return false
            }
            fun colHasInk(x: Int): Boolean {
                for (y in 0 until h) if ((pixels[y * w + x] ushr 24) > 8) return true
                return false
            }
            while (top < h && !rowHasInk(top)) top++
            if (top >= h) return src
            while (bottom > top && !rowHasInk(bottom)) bottom--
            while (left < w && !colHasInk(left)) left++
            if (left >= w) return src
            while (right > left && !colHasInk(right)) right--
            val cw = (right - left + 1).coerceAtLeast(1)
            val ch = (bottom - top + 1).coerceAtLeast(1)
            if (top == 0 && left == 0 && cw == w && ch == h) return src
            val cropped = Bitmap.createBitmap(src, left, top, cw, ch)
            if (cropped !== src) src.recycle()
            return cropped
        }
    }
}
