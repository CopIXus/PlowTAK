package com.atakmap.android.plowtak.report

import com.atakmap.android.plowtak.model.AlertEvent
import com.atakmap.android.plowtak.model.HazardEvent
import com.atakmap.android.plowtak.model.OperatorShift
import com.atakmap.android.plowtak.model.ReloadEvent
import com.atakmap.android.plowtak.model.RoadConditionReport
import com.atakmap.android.plowtak.model.TreatSegment

/**
 * Everything a records-grade storm export needs, snapshotted at export
 * time. Assembled by the ATAK-side ExportManager from the live stores;
 * consumed by the pure [GeoJsonExporter] and [CsvExporter].
 */
data class StormExportData(
    val stormId: String,
    val generatedAtMs: Long,
    /** UID + callsign of the exporting device. */
    val vehicleUid: String,
    val callsign: String,
    val segments: List<TreatSegment> = emptyList(),
    val alerts: List<AlertEvent> = emptyList(),
    val hazards: List<HazardEvent> = emptyList(),
    val conditions: List<RoadConditionReport> = emptyList(),
    val reloads: List<ReloadEvent> = emptyList(),
    val shifts: List<OperatorShift> = emptyList()
)
