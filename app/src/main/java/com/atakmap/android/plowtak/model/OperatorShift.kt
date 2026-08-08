package com.atakmap.android.plowtak.model

/**
 * One operator's stint in this vehicle. Vehicle ID is persistent per truck;
 * the operator changes at crew swaps — records and liability need both.
 */
data class OperatorShift(
    val operatorName: String,
    val operatorId: String,
    val startTimeMs: Long,
    /** 0 while the shift is active. */
    val endTimeMs: Long = 0L
) {
    val isActive: Boolean get() = endTimeMs == 0L

    fun durationMs(nowMs: Long): Long =
        (if (isActive) nowMs else endTimeMs) - startTimeMs
}
