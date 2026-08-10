package com.atakmap.android.plowtak.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.atakmap.android.plowtak.R
import com.atakmap.android.plowtak.model.EquipmentState

/**
 * Front-view plow control: left wing | blade | right wing, plus a spreader bar
 * across the bottom. Green fill stays inside each part outline.
 */
class PlowControlView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

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

    var onBladeToggle: ((Boolean) -> Unit)? = null
    var onWingLeftToggle: ((Boolean) -> Unit)? = null
    var onWingRightToggle: ((Boolean) -> Unit)? = null
    var onSpreaderToggle: ((Boolean) -> Unit)? = null

    private val art: Bitmap = loadArt(context)
    private val artSrc = Rect(0, 0, art.width, art.height)
    private val artDst = RectF()

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLOR_ACTIVE
    }
    private val artPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
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
    private val spreaderHit = RectF()

    private val leftWingPath = Path()
    private val bladePath = Path()
    private val rightWingPath = Path()

    fun bind(state: EquipmentState) {
        bladeDown = state.bladeDown
        wingLeft = state.wingLeftExtended
        wingRight = state.wingRightExtended
        spreadingOn = state.spreadingOn
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(dp(120))
        val aspect = art.height.toFloat() / art.width.toFloat()
        val artH = (w * aspect).toInt()
        val spreaderH = if (spreaderEnabled) dp(36) else 0
        setMeasuredDimension(w, artH + spreaderH)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val spreaderH = if (spreaderEnabled) dp(36).toFloat() else 0f
        artDst.set(0f, 0f, w.toFloat(), (h - spreaderH).coerceAtLeast(1f))
        layoutRegions()
    }

    private fun layoutRegions() {
        // Tighter fractions so green stays inside the white plow outlines.
        leftWingHit.set(fx(0.04f), fy(0.32f), fx(0.30f), fy(0.72f))
        bladeHit.set(fx(0.30f), fy(0.28f), fx(0.70f), fy(0.72f))
        rightWingHit.set(fx(0.70f), fy(0.32f), fx(0.96f), fy(0.72f))

        leftWingPath.reset()
        leftWingPath.moveTo(fx(0.295f), fy(0.38f))
        leftWingPath.lineTo(fx(0.16f), fy(0.40f))
        leftWingPath.quadTo(fx(0.08f), fy(0.44f), fx(0.075f), fy(0.54f))
        leftWingPath.lineTo(fx(0.085f), fy(0.64f))
        leftWingPath.quadTo(fx(0.14f), fy(0.70f), fx(0.24f), fy(0.68f))
        leftWingPath.lineTo(fx(0.295f), fy(0.66f))
        leftWingPath.close()

        bladePath.reset()
        bladePath.moveTo(fx(0.305f), fy(0.36f))
        bladePath.quadTo(fx(0.50f), fy(0.32f), fx(0.695f), fy(0.36f))
        bladePath.lineTo(fx(0.695f), fy(0.66f))
        bladePath.quadTo(fx(0.50f), fy(0.70f), fx(0.305f), fy(0.66f))
        bladePath.close()

        rightWingPath.reset()
        rightWingPath.moveTo(fx(0.705f), fy(0.38f))
        rightWingPath.lineTo(fx(0.84f), fy(0.40f))
        rightWingPath.quadTo(fx(0.92f), fy(0.44f), fx(0.925f), fy(0.54f))
        rightWingPath.lineTo(fx(0.915f), fy(0.64f))
        rightWingPath.quadTo(fx(0.86f), fy(0.70f), fx(0.76f), fy(0.68f))
        rightWingPath.lineTo(fx(0.705f), fy(0.66f))
        rightWingPath.close()

        if (spreaderEnabled) {
            val top = artDst.bottom + dp(4)
            val bottom = height.toFloat() - dp(2)
            spreaderHit.set(dp(8).toFloat(), top, width - dp(8).toFloat(), bottom)
        } else {
            spreaderHit.setEmpty()
        }
    }

    private fun fx(frac: Float): Float = artDst.left + artDst.width() * frac
    private fun fy(frac: Float): Float = artDst.top + artDst.height() * frac

    override fun onDraw(canvas: Canvas) {
        if (wingLeft) canvas.drawPath(leftWingPath, fillPaint)
        if (bladeDown) canvas.drawPath(bladePath, fillPaint)
        if (wingRight) canvas.drawPath(rightWingPath, fillPaint)
        canvas.drawBitmap(art, artSrc, artDst, artPaint)

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
            leftWingHit.contains(x, y) -> {
                wingLeft = !wingLeft
                onWingLeftToggle?.invoke(wingLeft)
                invalidate()
            }
            rightWingHit.contains(x, y) -> {
                wingRight = !wingRight
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

        /** Keep white line art; turn near-black background transparent so green fills show through. */
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
            return out
        }
    }
}
