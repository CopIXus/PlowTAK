package com.atakmap.android.plowtak.sync

import com.atakmap.android.plowtak.model.MaterialMode
import com.atakmap.android.plowtak.model.TrackPoint
import com.atakmap.android.plowtak.model.TreatSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.GZIPInputStream
import java.io.ByteArrayInputStream

class MissionCoverageCodecTest {

    private fun seg(
        id: String,
        startMs: Long,
        endMs: Long,
        stormId: String = "storm-A"
    ) = TreatSegment(
        id = id,
        vehicleUid = "PLOWTAK-T-1",
        callsign = "Plow-1",
        stormId = stormId,
        operatorId = "op-1",
        material = MaterialMode.PLOW_ONLY,
        widthM = 3.0,
        points = listOf(
            TrackPoint(36.0, -86.0, startMs, 0.0),
            TrackPoint(36.001, -86.0, endMs, 0.0)
        ),
        startTimeMs = startMs,
        endTimeMs = endMs
    )

    @Test
    fun missionNameSanitizesStormId() {
        assertEquals(
            "plowtak-coverage-2026-01-15-abc",
            MissionCoverageCodec.missionName("2026-01-15-abc")
        )
        assertEquals(
            "plowtak-coverage-storm-A-1",
            MissionCoverageCodec.missionName("storm/A 1!")
        )
        assertEquals("plowtak-coverage-unknown", MissionCoverageCodec.missionName("@@@"))
    }

    @Test
    fun effectiveMissionNamePrefersOverride() {
        assertEquals(
            "plowtak-coverage-storm-A",
            MissionCoverageCodec.effectiveMissionName("storm-A", "")
        )
        assertEquals(
            "VDOT-I-81",
            MissionCoverageCodec.effectiveMissionName("storm-A", "VDOT I-81")
        )
    }

    @Test
    fun liveFilenameUsesUtcHour() {
        // 2025-01-01T00:30:00Z → hour 2025010100
        val name = MissionCoverageCodec.liveFilename("PLOWTAK-T-1", 1_735_689_780_000L, gzip = true)
        assertEquals("PLOWTAK-T-1-2025010100-live.geojson.gz", name)
        val plain = MissionCoverageCodec.liveFilename("PLOW/TAK", 1_735_689_780_000L, gzip = false)
        assertEquals("PLOW_TAK-2025010100-live.geojson", plain)
    }

    @Test
    fun hourWindowBoundsAreUtcAligned() {
        val t = 1_735_689_780_000L // 00:30 UTC
        assertEquals(1_735_689_600_000L, MissionCoverageCodec.hourStartMs(t))
        assertEquals(1_735_693_200_000L, MissionCoverageCodec.hourEndMs(t))
        assertEquals("2025010100", MissionCoverageCodec.hourLabelUtc(t))
    }

    @Test
    fun segmentsInCurrentHourFiltersOverlap() {
        val now = 1_735_689_780_000L // mid-hour
        val hourStart = MissionCoverageCodec.hourStartMs(now)
        val inside = seg("in", hourStart + 1_000L, hourStart + 2_000L)
        val before = seg("before", hourStart - 10_000L, hourStart - 1_000L)
        val after = seg("after", hourStart + 3_700_000L, hourStart + 3_800_000L)
        val straddling = seg("straddle", hourStart - 5_000L, hourStart + 5_000L)
        val kept = MissionCoverageCodec.segmentsInCurrentHour(
            listOf(inside, before, after, straddling), now
        ).map { it.id }
        assertEquals(listOf("in", "straddle"), kept)
    }

    @Test
    fun encodeGeoJsonIsFeatureCollection() {
        val now = 1_735_689_600_000L
        val json = MissionCoverageCodec.encodeGeoJson(
            "storm-A", "PLOWTAK-T-1", now,
            listOf(seg("s1", now + 1_000L, now + 2_000L)),
            styleNowMs = now + 2_000L,
            cycleMinutes = 45,
            retentionHours = 0.0
        )
        assertTrue(json.contains("\"type\":\"FeatureCollection\""))
        assertTrue(json.contains("\"kind\":\"mission-coverage-live\""))
        assertTrue(json.contains("\"stormId\":\"storm-A\""))
        assertTrue(json.contains("\"type\":\"LineString\""))
        assertTrue(json.contains("\"id\":\"s1\""))
        assertTrue(json.contains("\"name\":"))
        assertTrue(json.contains("\"title\":"))
        assertTrue(json.contains("\"stroke\":\"#2ECC40\""))
        assertTrue(json.contains("\"strokeColor\":"))
        assertTrue(json.contains("\"ogr_style\":"))
        assertFalse(json.contains("\n"))
    }

    @Test
    fun encodeGeoJsonColorsOverdueSegmentRed() {
        val end = 1_735_689_600_000L
        val now = end + 60 * 60_000L // 60 min later, cycle 45 → RED
        val json = MissionCoverageCodec.encodeGeoJson(
            "storm-A", "PLOWTAK-T-1", end,
            listOf(seg("s1", end - 1_000L, end)),
            styleNowMs = now,
            cycleMinutes = 45,
            retentionHours = 0.0
        )
        assertTrue(json.contains("\"stroke\":\"#FF4136\""))
        assertTrue(json.contains("\"id\":\"s1\""))
    }

    @Test
    fun encodeGeoJsonDropsExpiredWhenRetentionSet() {
        val end = 1_735_689_600_000L
        val now = end + 13 * 3_600_000L
        val json = MissionCoverageCodec.encodeGeoJson(
            "storm-A", "PLOWTAK-T-1", end,
            listOf(seg("s1", end - 1_000L, end)),
            styleNowMs = now,
            cycleMinutes = 45,
            retentionHours = 12.0
        )
        assertFalse(json.contains("\"id\":\"s1\""))
        assertTrue(json.contains("\"features\":[]"))
    }

    @Test
    fun encodeBytesGzipRoundTrip() {
        val now = 1_735_689_600_000L
        val gz = MissionCoverageCodec.encodeBytes(
            "storm-A", "PLOWTAK-T-1", now,
            listOf(seg("s1", now + 1_000L, now + 2_000L)),
            gzip = true
        )
        assertTrue(gz.size >= 2)
        assertEquals(0x1f.toByte(), gz[0])
        assertEquals(0x8b.toByte(), gz[1])
        val plain = GZIPInputStream(ByteArrayInputStream(gz)).readBytes().toString(Charsets.UTF_8)
        assertTrue(plain.contains("FeatureCollection"))
        val hash = MissionCoverageCodec.sha256Hex(gz)
        assertEquals(64, hash.length)
        assertEquals(hash, MissionCoverageCodec.sha256Hex(gz))
    }

    @Test
    fun gzipHashIsStableAcrossCalls() {
        val now = 1_735_689_600_000L
        val segs = listOf(seg("s1", now + 1_000L, now + 2_000L))
        val a = MissionCoverageCodec.encodeBytes("storm-A", "PLOWTAK-T-1", now, segs, gzip = true)
        Thread.sleep(5)
        val b = MissionCoverageCodec.encodeBytes("storm-A", "PLOWTAK-T-1", now, segs, gzip = true)
        assertEquals(MissionCoverageCodec.sha256Hex(a), MissionCoverageCodec.sha256Hex(b))
    }

    @Test
    fun plainGeoJsonHashIsStable() {
        val now = 1_735_689_600_000L
        val segs = listOf(seg("s1", now + 1_000L, now + 2_000L))
        val a = MissionCoverageCodec.encodeBytes("storm-A", "PLOWTAK-T-1", now, segs, gzip = false)
        val b = MissionCoverageCodec.encodeBytes("storm-A", "PLOWTAK-T-1", now, segs, gzip = false)
        assertEquals(MissionCoverageCodec.sha256Hex(a), MissionCoverageCodec.sha256Hex(b))
        assertTrue(a.toString(Charsets.UTF_8).startsWith("{"))
    }

    @Test
    fun encodeGeoJsonUsesPerSegmentCycleForStroke() {
        val now = 1_735_689_780_000L
        // 20 minutes old — green at 45 min cycle, red at 15 min cycle
        val s = seg("s1", now - 25 * 60_000L, now - 20 * 60_000L)
        val flat = MissionCoverageCodec.encodeGeoJson(
            "storm-A", "PLOWTAK-T-1", now, listOf(s),
            styleNowMs = now, cycleMinutes = 45, retentionHours = 0.0
        )
        assertTrue(flat.contains("\"stroke\":\"#2ECC40\""))
        val tight = MissionCoverageCodec.encodeGeoJson(
            "storm-A", "PLOWTAK-T-1", now, listOf(s),
            styleNowMs = now, cycleMinutes = 45, retentionHours = 0.0,
            cycleMinutesFor = { 15 }
        )
        assertTrue(tight.contains("\"stroke\":\"#FF4136\""))
    }
}
