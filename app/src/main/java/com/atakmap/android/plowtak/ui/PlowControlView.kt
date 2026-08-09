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
import com.atakmap.android.plowtak.model.EquipmentState
import com.atakmap.android.plowtak.R

/**
 * Front-view plow control using plow art: left wing | blade | right wing.
 * Green fill inside a part = blade down / wing extended.
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

    var onBladeToggle: ((Boolean) -> Unit)? = null
    var onWingLeftToggle: ((Boolean) -> Unit)? = null
    var onWingRightToggle: ((Boolean) -> Unit)? = null

    private val art: Bitmap = loadArt(context)
    private val artSrc = Rect(0, 0, art.width, art.height)
    private val artDst = RectF()

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLOR_ACTIVE
    }
    private val artPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private val leftWingHit = RectF()
    private val bladeHit = RectF()
    private val rightWingHit = RectF()

    private val leftWingPath = Path()
    private val bladePath = Path()
    private val rightWingPath = Path()

    fun bind(state: EquipmentState) {
        bladeDown = state.bladeDown
        wingLeft = state.wingLeftExtended
        wingRight = state.wingRightExtended
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(dp(120))
        val aspect = art.height.toFloat() / art.width.toFloat()
        val h = (w * aspect).toInt().coerceAtLeast(dp(100))
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        artDst.set(0f, 0f, w.toFloat(), h.toFloat())
        layoutRegions()
    }

    private fun layoutRegions() {
        // Fractions of the art canvas that map to the three plow parts.
        leftWingHit.set(fx(0.02f), fy(0.28f), fx(0.30f), fy(0.78f))
        bladeHit.set(fx(0.28f), fy(0.22f), fx(0.72f), fy(0.80f))
        rightWingHit.set(fx(0.70f), fy(0.28f), fx(0.98f), fy(0.78f))

        leftWingPath.reset()
        leftWingPath.moveTo(fx(0.28f), fy(0.34f))
        leftWingPath.lineTo(fx(0.12f), fy(0.36f))
        leftWingPath.quadTo(fx(0.04f), fy(0.42f), fx(0.05f), fy(0.55f))
        leftWingPath.lineTo(fx(0.06f), fy(0.66f))
        leftWingPath.quadTo(fx(0.10f), fy(0.72f), fx(0.22f), fy(0.70f))
        leftWingPath.lineTo(fx(0.29f), fy(0.68f))
        leftWingPath.close()

        bladePath.reset()
        bladePath.moveTo(fx(0.30f), fy(0.30f))
        bladePath.quadTo(fx(0.50f), fy(0.26f), fx(0.70f), fy(0.30f))
        bladePath.lineTo(fx(0.71f), fy(0.68f))
        bladePath.quadTo(fx(0.50f), fy(0.74f), fx(0.29f), fy(0.68f))
        bladePath.close()

        rightWingPath.reset()
        rightWingPath.moveTo(fx(0.72f), fy(0.34f))
        rightWingPath.lineTo(fx(0.88f), fy(0.36f))
        rightWingPath.quadTo(fx(0.96f), fy(0.42f), fx(0.95f), fy(0.55f))
        rightWingPath.lineTo(fx(0.94f), fy(0.66f))
        rightWingPath.quadTo(fx(0.90f), fy(0.72f), fx(0.78f), fy(0.70f))
        rightWingPath.lineTo(fx(0.71f), fy(0.68f))
        rightWingPath.close()
    }

    private fun fx(frac: Float): Float = artDst.left + artDst.width() * frac
    private fun fy(frac: Float): Float = artDst.top + artDst.height() * frac

    override fun onDraw(canvas: Canvas) {
        if (wingLeft) canvas.drawPath(leftWingPath, fillPaint)
        if (bladeDown) canvas.drawPath(bladePath, fillPaint)
        if (wingRight) canvas.drawPath(rightWingPath, fillPaint)
        canvas.drawBitmap(art, artSrc, artDst, artPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        val x = event.x
        val y = event.y
        when {
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
                    // Soften anti-aliased edges: luminance drives alpha, RGB stays white.
                    (luma shl 24) or 0x00FFFFFF
                }
            }
            out.setPixels(pixels, 0, w, 0, 0, w, h)
            if (out !== src) src.recycle()
            return out
        }
    }
}
