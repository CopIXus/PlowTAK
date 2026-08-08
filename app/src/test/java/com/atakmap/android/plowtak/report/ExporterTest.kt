package com.atakmap.android.plowtak.report

import com.atakmap.android.plowtak.model.AlertEvent
import com.atakmap.android.plowtak.model.AlertState
import com.atakmap.android.plowtak.model.HazardEvent
import com.atakmap.android.plowtak.model.HazardType
import com.atakmap.android.plowtak.model.Material
import com.atakmap.android.plowtak.model.MaterialMode
import com.atakmap.android.plowtak.model.OperatorShift
import com.atakmap.android.plowtak.model.ReloadEvent
import com.atakmap.android.plowtak.model.RoadCondition
import com.atakmap.android.plowtak.model.RoadConditionReport
import com.atakmap.android.plowtak.model.TrackPoint
import com.atakmap.android.plowtak.model.TreatSegment
import com.atakmap.android.plowtak.model.VehicleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExporterTest {

    private val segment = TreatSegment(
        id = "T-1-1000", vehicleUid = "PLOWTAK-T-1", callsign = "Plow, \"One\"",
        stormId = "storm-A", operatorId = "op-7", material = MaterialMode.PLOW_AND_SALT,
        widthM = 3.7,
        points = listOf(
            TrackPoint(36.0000, -86.0000, 1_000L, 0.0),
            TrackPoint(36.0090, -86.0000, 61_000L, 0.0) // ~1 km due north
        ),
        startTimeMs = 1_000L, endTimeMs = 61_000L, spreadMaterial = Material.BRINE
    )

    private val data = StormExportData(
        stormId = "storm-A",
        generatedAtMs = 1735689600000L, // 2025-01-01T00:00:00Z
        vehicleUid = "PLOWTAK-T-1", callsign = "Plow-1",
        segments = listOf(segment),
        alerts = listOf(
            AlertEvent(
                "a1", "PLOWTAK-T-2", "Plow-2", VehicleType.PLOW,
                36.1, -86.1, 2_000L, AlertState.CLEARED, handledBy = "Sup-1"
            )
        ),
        hazards = listOf(
            HazardEvent(
                "hz1", HazardType.STRANDED_VEHICLE, "PLOWTAK-T-1", "Plow-1",
                36.2, -86.2, 3_000L, "storm-A", photoFile = "hz1.jpg"
            )
        ),
        conditions = listOf(
            RoadConditionReport(
                "c1", RoadCondition.SLUSH, "PLOWTAK-T-1", "Plow-1",
                36.3, -86.3, 4_000L, "storm-A"
            )
        ),
        reloads = listOf(ReloadEvent("f1", "North Dome", 5_000L, "op-7")),
        shifts = listOf(
            OperatorShift("Jane Doe", "op-7", 0L, 3_600_000L),
            OperatorShift("Bob Roe", "op-8", 3_600_000L) // active
        )
    )

    // ------------------------------------------------------------ GeoJSON

    @Test
    fun `geojson has all feature types and storm header`() {
        val json = GeoJsonExporter.export(data)
        assertTrue(json.contains("\"type\":\"FeatureCollection\""))
        assertTrue(json.contains("\"stormId\":\"storm-A\""))
        assertTrue(json.contains("\"generatedAt\":\"2025-01-01T00:00:00Z\""))
        assertTrue(json.contains("\"type\":\"LineString\""))
        assertTrue(json.contains("\"type\":\"segment\""))
        assertTrue(json.contains("\"type\":\"alert\""))
        assertTrue(json.contains("\"type\":\"hazard\""))
        assertTrue(json.contains("\"type\":\"condition\""))
    }

    @Test
    fun `geojson segment carries records fields`() {
        val json = GeoJsonExporter.export(data)
        assertTrue(json.contains("\"operator\":\"op-7\""))
        assertTrue(json.contains("\"material\":\"plow+salt\""))
        assertTrue(json.contains("\"spreadMaterial\":\"brine\""))
        assertTrue(json.contains("\"widthM\":3.70"))
        assertTrue(json.contains("\"headingDeg\":0.0"))
        assertTrue(json.contains("\"side\":\"right\""))
        // Callsign with quote and comma survives escaping.
        assertTrue(json.contains("\"callsign\":\"Plow, \\\"One\\\"\""))
        // GeoJSON is lon,lat order.
        assertTrue(json.contains("[-86.0000000,36.0000000]"))
    }

    @Test
    fun `contractor tag and telemetry export when present`() {
        val ctr = data.copy(
            segments = listOf(
                segment.copy(
                    contractor = true,
                    applicationRateLbsPerMi = 250.0,
                    roadTempF = 28.4
                )
            )
        )
        val json = GeoJsonExporter.export(ctr)
        assertTrue(json.contains("\"contractor\":true"))
        assertTrue(json.contains("\"rateLbsPerMi\":250.0"))
        assertTrue(json.contains("\"roadTempF\":28.4"))

        val csv = CsvExporter.segmentsCsv(ctr.segments)
        val lines = csv.trimEnd().split("\r\n")
        assertTrue(lines[0].endsWith("contractor,rateLbsPerMi,roadTempF"))
        assertTrue(lines[1].endsWith("true,250.0,28.4"))

        // Municipal segments keep the columns but leave them blank / omit
        // the GeoJSON props.
        val plainJson = GeoJsonExporter.export(data)
        assertFalse(plainJson.contains("contractor"))
        assertTrue(CsvExporter.segmentsCsv(listOf(segment)).trimEnd().endsWith(",,"))
    }

    @Test
    fun `geojson is structurally balanced`() {
        val json = GeoJsonExporter.export(data)
        assertEquals(json.count { it == '{' }, json.count { it == '}' })
        assertEquals(json.count { it == '[' }, json.count { it == ']' })
    }

    @Test
    fun `empty export still produces a valid shell`() {
        val json = GeoJsonExporter.export(
            StormExportData("s", 0L, "uid", "cs")
        )
        assertTrue(json.contains("\"features\":[]"))
    }

    // ---------------------------------------------------------------- CSV

    @Test
    fun `segments csv quotes and rounds correctly`() {
        val csv = CsvExporter.segmentsCsv(listOf(segment))
        val lines = csv.trimEnd().split("\r\n")
        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("id,vehicleUid,callsign"))
        // Embedded comma + quote forces RFC-4180 quoting.
        assertTrue(lines[1].contains("\"Plow, \"\"One\"\"\""))
        assertTrue(lines[1].contains("plow+salt"))
        assertTrue(lines[1].contains("brine"))
        assertTrue(lines[1].contains("right"))
        // ~1001 m for 0.009 deg of latitude.
        val lengthField = lines[1].split(",").first { it.startsWith("100") }
        assertTrue(lengthField.toDouble() in 950.0..1050.0)
    }

    @Test
    fun `alerts hazards conditions reloads csv round out the record set`() {
        assertTrue(CsvExporter.alertsCsv(data.alerts).contains("cleared"))
        assertTrue(CsvExporter.alertsCsv(data.alerts).contains("Sup-1"))
        assertTrue(CsvExporter.hazardsCsv(data.hazards).contains("hz1.jpg"))
        assertTrue(CsvExporter.conditionsCsv(data.conditions).contains("slush"))
        assertTrue(CsvExporter.reloadsCsv(data.reloads).contains("North Dome"))
    }

    @Test
    fun `shift csv leaves active shift end empty`() {
        val csv = CsvExporter.shiftsCsv(data.shifts)
        val lines = csv.trimEnd().split("\r\n")
        assertEquals(3, lines.size)
        assertTrue(lines[1].contains("Jane Doe"))
        assertTrue(lines[1].endsWith("60")) // 60-minute completed shift
        // Active shift: endUtc/endMs/duration blank.
        assertTrue(lines[2].contains("Bob Roe"))
        assertTrue(lines[2].endsWith(",,"))
        assertFalse(lines[2].contains("1970-01-01T01:00:00Z,1970"))
    }
}
