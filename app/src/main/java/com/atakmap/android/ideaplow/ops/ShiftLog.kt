package com.atakmap.android.ideaplow.ops

import com.atakmap.android.ideaplow.model.OperatorShift

/**
 * Operator shift login: name/ID entered at shift start, timestamps recorded
 * for hours summaries and records. The vehicle ID lives in the capability
 * profile; this tracks *who* is behind the wheel right now.
 */
class ShiftLog(
    private val persistence: KeyValuePersistence
) {

    fun interface Listener {
        fun onShiftChanged(current: OperatorShift?)
    }

    private val history = mutableListOf<OperatorShift>()
    private var current: OperatorShift? = null
    private val listeners = mutableListOf<Listener>()

    init {
        load()
    }

    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    val currentShift: OperatorShift? get() = current

    val isOnShift: Boolean get() = current != null

    fun shiftHistory(): List<OperatorShift> = history.toList()

    /** Start a shift; an already-active shift is ended first (crew swap). */
    fun startShift(operatorName: String, operatorId: String, nowMs: Long): OperatorShift {
        endShift(nowMs)
        val shift = OperatorShift(operatorName.trim(), operatorId.trim(), nowMs)
        current = shift
        save()
        notifyChanged()
        return shift
    }

    fun endShift(nowMs: Long) {
        val active = current ?: return
        history.add(active.copy(endTimeMs = nowMs))
        current = null
        save()
        notifyChanged()
    }

    private fun notifyChanged() {
        listeners.toList().forEach { it.onShiftChanged(current) }
    }

    // ------------------------------------------------------- persistence

    private fun save() {
        val all = history.takeLast(100) + listOfNotNull(current)
        val encoded = all.joinToString("\n") { s ->
            listOf(esc(s.operatorName), esc(s.operatorId), s.startTimeMs, s.endTimeMs)
                .joinToString("|")
        }
        persistence.putString(KEY_SHIFTS, encoded)
    }

    private fun load() {
        persistence.getString(KEY_SHIFTS)?.lineSequence()?.forEach { line ->
            val f = line.split("|")
            if (f.size == 4) {
                val start = f[2].toLongOrNull() ?: return@forEach
                val end = f[3].toLongOrNull() ?: return@forEach
                val shift = OperatorShift(unesc(f[0]), unesc(f[1]), start, end)
                // An active shift survives restart (device rebooted mid-shift).
                if (shift.isActive) current = shift else history.add(shift)
            }
        }
    }

    private fun esc(s: String) = s.replace("|", "&#124;").replace("\n", " ")
    private fun unesc(s: String) = s.replace("&#124;", "|")

    companion object {
        const val KEY_SHIFTS = "ideaplow.shifts"
    }
}
