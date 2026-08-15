package com.atakmap.android.plowtak.ui

import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.atakmap.android.plowtak.PlowTakController
import com.atakmap.android.plowtak.R
import com.atakmap.android.plowtak.ops.RouteAssignment
import com.atakmap.android.plowtak.ops.RouteAssignmentManager
import com.atakmap.android.plowtak.ops.SnoozeStore
import com.atakmap.android.plowtak.ops.TaskManager
import com.atakmap.android.plowtak.ops.TaskingItem
import com.atakmap.android.plowtak.ops.TaskingListBuilder
import com.atakmap.android.plowtak.plugin.PluginLayoutInflater

/**
 * Mine-first needs-treated list: assigned tasks/routes, then nearby overdue
 * gaps. Tap zooms the map; + defers due time by the snooze step.
 */
class TaskingPanel(
    private val controller: PlowTakController,
    private val onDone: () -> Unit
) {

    val view: View = PluginLayoutInflater.inflate(
        controller.pluginContext, R.layout.panel_tasking, null
    )

    private val hostContext = controller.mapView.context
    private val list = view.findViewById<LinearLayout>(R.id.tasking_list)
    private val empty = view.findViewById<TextView>(R.id.tasking_empty)
    private val hint = view.findViewById<TextView>(R.id.tasking_hint)
    private val handler = Handler(Looper.getMainLooper())
    private val refreshTick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    private val taskListener = object : TaskManager.Listener {
        override fun onTasksChanged(tasks: List<com.atakmap.android.plowtak.model.TaskEvent>) {
            view.post { refresh() }
        }
        override fun onLocalTransition(task: com.atakmap.android.plowtak.model.TaskEvent) {}
        override fun onEscalated(task: com.atakmap.android.plowtak.model.TaskEvent) {}
    }

    private val coverageListener = object : com.atakmap.android.plowtak.coverage.CoverageStore.Listener {
        override fun onSegmentAdded(
            segment: com.atakmap.android.plowtak.model.TreatSegment,
            local: Boolean
        ) {
            view.post { refresh() }
        }
        override fun onSegmentsRemoved(ids: Collection<String>) {
            view.post { refresh() }
        }
    }

    private val routeListener = object : RouteAssignmentManager.Listener {
        override fun onAssignmentsChanged(assignments: List<RouteAssignment>) {
            view.post { refresh() }
        }
        override fun onLocalAssignment(assignment: RouteAssignment) {}
    }

    private val snoozeListener = SnoozeStore.Listener {
        view.post { refresh() }
    }

    init {
        view.findViewById<Button>(R.id.tasking_done).setOnClickListener { onDone() }
        controller.taskManager.addListener(taskListener)
        controller.coverageStore.addListener(coverageListener)
        controller.routeAssignments.addListener(routeListener)
        controller.snoozeStore.addListener(snoozeListener)
        val snooze = controller.prefs.taskingSnoozeMinutes
        hint.text = hostContext.getString(R.string.tasking_hint, snooze)
        refresh()
        handler.postDelayed(refreshTick, REFRESH_MS)
    }

    fun dispose() {
        handler.removeCallbacks(refreshTick)
        controller.taskManager.removeListener(taskListener)
        controller.coverageStore.removeListener(coverageListener)
        controller.routeAssignments.removeListener(routeListener)
        controller.snoozeStore.removeListener(snoozeListener)
    }

    fun refresh() {
        val items = controller.buildTaskingList()
        list.removeAllViews()
        if (items.isEmpty()) {
            empty.visibility = View.VISIBLE
            return
        }
        empty.visibility = View.GONE
        for (item in items) {
            list.addView(rowFor(item))
        }
    }

    private fun rowFor(item: TaskingItem): View {
        val pad = dp(10)
        val row = LinearLayout(controller.pluginContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(if (item.mine) 0x335FA8D3 else 0x22FFFFFF)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                controller.zoomToTaskingItem(item)
                Toast.makeText(
                    hostContext,
                    hostContext.getString(R.string.tasking_zoomed, item.title),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        val textCol = LinearLayout(controller.pluginContext).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(TextView(controller.pluginContext).apply {
            text = item.title
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
        })
        val dist = if (item.distanceM >= Double.MAX_VALUE / 8) {
            "no GPS"
        } else {
            TaskingListBuilder.formatDistanceMiles(item.distanceM)
        }
        val kind = item.kind.label
        textCol.addView(TextView(controller.pluginContext).apply {
            text = "$kind · $dist" +
                if (item.detail.isNotEmpty()) " · ${item.detail}" else ""
            setTextColor(0xFF9E9E9E.toInt())
            textSize = 12f
        })
        row.addView(textCol)

        val plus = Button(controller.pluginContext).apply {
            text = "+"
            textSize = 20f
            minWidth = dp(48)
            minimumWidth = dp(48)
            setOnClickListener {
                val mins = controller.snoozeTaskingItem(item.id)
                Toast.makeText(
                    hostContext,
                    hostContext.getString(R.string.tasking_deferred, mins),
                    Toast.LENGTH_SHORT
                ).show()
                refresh()
            }
        }
        row.addView(plus)

        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) }
        row.layoutParams = lp
        return row
    }

    private fun dp(value: Int): Int =
        (value * controller.pluginContext.resources.displayMetrics.density).toInt()

    companion object {
        private const val REFRESH_MS = 30_000L
    }
}
