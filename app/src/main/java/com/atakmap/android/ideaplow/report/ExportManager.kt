package com.atakmap.android.ideaplow.report

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes a records-grade storm export to the device: one GeoJSON plus one
 * CSV per record type, in a timestamped folder. The heavy lifting is the
 * pure exporters; this class only picks the folder and writes files.
 */
class ExportManager(
    private val context: Context
) {

    data class Result(val folder: File, val files: List<File>)

    /**
     * Export [data] and return the folder written, or null on failure.
     * Run on a background thread — a marathon storm can be megabytes.
     */
    fun export(data: StormExportData): Result? {
        return try {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                .format(Date(data.generatedAtMs))
            val name = "ideaplow-${data.stormId.ifEmpty { "session" }}-$stamp"
            val folder = File(exportRoot(), name)
            if (!folder.mkdirs() && !folder.isDirectory) return null

            val files = mutableListOf<File>()
            fun write(fileName: String, content: String) {
                val f = File(folder, fileName)
                f.writeText(content)
                files.add(f)
            }

            write("storm.geojson", GeoJsonExporter.export(data))
            write("segments.csv", CsvExporter.segmentsCsv(data.segments))
            write("alerts.csv", CsvExporter.alertsCsv(data.alerts))
            write("hazards.csv", CsvExporter.hazardsCsv(data.hazards))
            write("conditions.csv", CsvExporter.conditionsCsv(data.conditions))
            write("reloads.csv", CsvExporter.reloadsCsv(data.reloads))
            write("shifts.csv", CsvExporter.shiftsCsv(data.shifts))

            Log.i(TAG, "exported storm ${data.stormId} to $folder (${files.size} files)")
            Result(folder, files)
        } catch (e: Exception) {
            Log.e(TAG, "storm export failed", e)
            null
        }
    }

    /**
     * ATAK's export folder when reachable, else app-private files. SDK-fixup:
     * FileSystemUtils.getItem("export") is the canonical ATAK path helper —
     * verify against the real 5.8 main.jar and switch to it; the raw
     * external-storage path below matches where ATAK keeps atak/export on
     * devices with legacy storage access.
     */
    private fun exportRoot(): File {
        try {
            @Suppress("DEPRECATION")
            val atakExport = File(Environment.getExternalStorageDirectory(), "atak/export")
            if (atakExport.isDirectory || atakExport.mkdirs()) return atakExport
        } catch (e: Exception) {
            Log.w(TAG, "external export dir unavailable", e)
        }
        return File(context.filesDir, "export").apply { mkdirs() }
    }

    companion object {
        private const val TAG = "IdeaPlowExport"
    }
}
