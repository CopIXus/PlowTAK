package com.atakmap.android.plowtak.sync

import com.atakmap.android.plowtak.model.RoadCondition
import com.atakmap.android.plowtak.model.RoadConditionReport
import com.atakmap.android.plowtak.model.StormSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConditionMissionCodecTest {

    @Test
    fun encodeDecodeRoundTripKeepsTimestamp() {
        val now = 1_700_000_000_000L
        val report = RoadConditionReport(
            uid = "plowtak-cond-1",
            condition = RoadCondition.ICE,
            reporterUid = "PLOW-1",
            reporterCallsign = "Plow-1",
            lat = 36.1,
            lon = -86.7,
            timeMs = now - 30_000L,
            stormId = "storm-A"
        )
        val bytes = ConditionMissionCodec.encode(
            "storm-A", listOf(report), now,
            StormSession.DEFAULT_ROAD_CONDITION_TTL_MINUTES
        )
        val decoded = ConditionMissionCodec.decode(bytes, now)
        assertEquals(1, decoded.size)
        assertEquals(report.uid, decoded[0].uid)
        assertEquals(report.timeMs, decoded[0].timeMs)
        assertEquals(RoadCondition.ICE, decoded[0].condition)
        val json = bytes.toString(Charsets.UTF_8)
        assertTrue(
            "GeoJSON must include properties.name for Data Sync peers",
            json.contains("\"name\":")
        )
        assertTrue(json.contains("Plow-1"))
    }

    @Test
    fun filterFreshDropsOlderThanTtl() {
        val now = 1_700_000_000_000L
        val ttlMin = 120
        val fresh = RoadConditionReport(
            uid = "c-fresh",
            condition = RoadCondition.WET,
            reporterUid = "PLOW-1",
            reporterCallsign = "Plow-1",
            lat = 36.0,
            lon = -86.0,
            timeMs = now - 60_000L
        )
        val stale = RoadConditionReport(
            uid = "c-stale",
            condition = RoadCondition.SNOW_COVERED,
            reporterUid = "PLOW-1",
            reporterCallsign = "Plow-1",
            lat = 36.0,
            lon = -86.0,
            timeMs = now - (ttlMin + 1) * 60_000L
        )
        val kept = ConditionMissionCodec.filterFresh(listOf(fresh, stale), now, ttlMin)
        assertEquals(listOf("c-fresh"), kept.map { it.uid })
    }

    @Test
    fun decodeDropsExpiredExpiresAt() {
        val now = 1_700_000_000_000L
        val old = RoadConditionReport(
            uid = "c-old",
            condition = RoadCondition.BARE,
            reporterUid = "PLOW-1",
            reporterCallsign = "Plow-1",
            lat = 36.0,
            lon = -86.0,
            timeMs = now - 3 * 60 * 60_000L
        )
        val bytes = ConditionMissionCodec.encode("storm-A", listOf(old), now - 3 * 60 * 60_000L, 120)
        val decoded = ConditionMissionCodec.decode(bytes, now)
        assertTrue(decoded.isEmpty())
    }
}
