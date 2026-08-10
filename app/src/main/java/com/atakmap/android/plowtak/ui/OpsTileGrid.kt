package com.atakmap.android.plowtak.ui

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max

/**
 * ATAK Loadout Tools–style grid: zero gutters, fixed 80dp row height, columns
 * from `width / 80dp`, and 1dp manatee right/bottom bars on each cell
 * (matches ATAK 5.8 `loadout_tool_grid_item`).
 *
 * On fold / panel resize, columns reflow and leftover pixels are distributed
 * across the row so tiles stay edge-aligned together (ATAK `columnWidth` stretch).
 */
class OpsTileGrid @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : ViewGroup(context, attrs, defStyle) {

    /** ATAK `nav_grid_item_size` — cell height and column divisor. */
    var cellDp: Int = 80
        set(value) {
            field = value.coerceIn(56, 120)
            requestLayout()
        }

    private var columns: Int = 1
    private var tileH: Int = 0
    /** Per-column widths (sum == measured width); handles leftover px. */
    private var colWidths: IntArray = intArrayOf(0)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && w != oldw) {
            // Fold / dropdown width changes must reflow columns.
            requestLayout()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val specW = MeasureSpec.getSize(widthMeasureSpec)
        val mode = MeasureSpec.getMode(widthMeasureSpec)
        val cell = dp(cellDp)
        // Prefer the actual laid-out width when parent already sized us
        // (ScrollView / fold often remasures with stale AT_MOST first).
        val width = when {
            mode == MeasureSpec.EXACTLY && specW > 0 -> specW
            measuredWidth > 0 && mode != MeasureSpec.UNSPECIFIED ->
                max(specW, measuredWidth)
            specW > 0 -> specW
            else -> cell
        }.coerceAtLeast(cell)

        columns = max(1, width / cell)
        tileH = cell
        colWidths = distributeColumns(width, columns)

        val count = childCount
        val rows = if (count == 0) 0 else (count + columns - 1) / columns
        val height = rows * tileH
        for (i in 0 until count) {
            val col = i % columns
            getChildAt(i).measure(
                MeasureSpec.makeMeasureSpec(colWidths[col], MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(tileH, MeasureSpec.EXACTLY)
            )
        }
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        if (colWidths.size != columns) {
            colWidths = distributeColumns(r - l, columns)
        }
        val xOffsets = IntArray(columns)
        var acc = 0
        for (c in 0 until columns) {
            xOffsets[c] = acc
            acc += colWidths.getOrElse(c) { 0 }
        }
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val col = i % columns
            val row = i / columns
            val left = xOffsets[col]
            val top = row * tileH
            val tw = colWidths.getOrElse(col) { tileH }
            child.layout(left, top, left + tw, top + tileH)
        }
    }

    override fun onViewAdded(child: View?) {
        super.onViewAdded(child)
        requestLayout()
    }

    override fun onViewRemoved(child: View?) {
        super.onViewRemoved(child)
        requestLayout()
    }

    private fun distributeColumns(width: Int, cols: Int): IntArray {
        val n = cols.coerceAtLeast(1)
        val base = width / n
        val extra = width % n
        return IntArray(n) { i -> base + if (i < extra) 1 else 0 }
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
        ).toInt()

    companion object {
        /** ATAK `manatee` — Tools cell edge bars. */
        const val BAR_COLOR = 0xFF979797.toInt()
        /** ATAK loadout default cell fill. */
        const val FILL_IDLE = 0xFF000000.toInt()
        /** ATAK legacy Tools selected (`led_green`). */
        const val FILL_SELECTED = 0xFF92A844.toInt()
        const val FILL_HAZARD = 0xFF3E2723.toInt()
        const val FILL_CONDITION = 0xFF212121.toInt()

        private const val TAG_OPS_TILE = "plowtak.ops_tile"

        /**
         * One Loadout-style cell: black fill, 36dp icon, 12sp / 2-line label,
         * 1dp manatee right + bottom bars.
         */
        fun createTile(
            context: Context,
            label: String,
            iconRes: Int,
            backgroundColor: Int,
            onClick: View.OnClickListener
        ): FrameLayout {
            val cell = FrameLayout(context)
            cell.setBackgroundColor(backgroundColor)
            cell.tag = TAG_OPS_TILE
            cell.isClickable = true
            cell.isFocusable = true
            cell.setOnClickListener(onClick)
            // Avoid Button-like min sizes forcing uneven cells after reflow.
            cell.minimumWidth = 0
            cell.minimumHeight = 0

            val content = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL or Gravity.CENTER_VERTICAL
                setPadding(dp(context, 4), dp(context, 8), dp(context, 4), dp(context, 4))
            }
            val icon = ImageView(context).apply {
                val size = dp(context, 36)
                layoutParams = LinearLayout.LayoutParams(size, size).also {
                    it.gravity = Gravity.CENTER_HORIZONTAL
                }
                setImageResource(iconRes)
                setColorFilter(Color.WHITE)
            }
            val text = TextView(context).apply {
                this.text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                includeFontPadding = false
                setPadding(dp(context, 2), dp(context, 2), dp(context, 2), 0)
            }
            content.addView(icon)
            content.addView(
                text,
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

            val bar = dp(context, 1).coerceAtLeast(1)
            cell.addView(
                View(context).apply { setBackgroundColor(BAR_COLOR) },
                FrameLayout.LayoutParams(bar, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                    gravity = Gravity.END
                }
            )
            cell.addView(
                View(context).apply { setBackgroundColor(BAR_COLOR) },
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, bar).apply {
                    gravity = Gravity.BOTTOM
                }
            )
            return cell
        }

        fun tintTile(cell: View, color: Int) {
            cell.setBackgroundColor(color)
            cell.tag = TAG_OPS_TILE
        }

        fun isOpsTile(view: View): Boolean = view.tag == TAG_OPS_TILE

        private fun dp(context: Context, v: Int): Int =
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics
            ).toInt()
    }
}
