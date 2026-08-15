package com.atakmap.android.plowtak.sync

import com.atakmap.android.plowtak.model.StormSession
import org.junit.Assert.assertEquals
import org.junit.Test

class StormConfigCodecTest {

    @Test
    fun roundTripIncludesCoverageTimers() {
        val session = StormSession(
            id = "2026-08-15-1",
            startTimeMs = 1_700_000_000_000L,
            startedBy = "Sup-1",
            label = "I-81",
            agency = "VDOT",
            missionName = "VDOT-I-81",
            channel = "__ANON__",
            greenUntilMinutes = 30,
            yellowUntilMinutes = 50,
            cycleMinutes = 60,
            cycleP1Minutes = 20,
            cycleP2Minutes = 0,
            cycleP3Minutes = 60,
            coverageRetentionHours = 0.0,
            roadConditionTtlMinutes = 90
        )
        val decoded = StormConfigCodec.decode(StormConfigCodec.encode(session))!!
        assertEquals(session.id, decoded.id)
        assertEquals(30, decoded.greenUntilMinutes)
        assertEquals(50, decoded.yellowUntilMinutes)
        assertEquals(60, decoded.cycleMinutes)
        assertEquals(20, decoded.cycleP1Minutes)
        assertEquals(0, decoded.cycleP2Minutes)
        assertEquals(60, decoded.cycleP3Minutes)
        assertEquals(0.0, decoded.coverageRetentionHours, 0.001)
        assertEquals(90, decoded.roadConditionTtlMinutes)
        assertEquals("VDOT", decoded.agency)
    }

    @Test
    fun legacyConfigWithoutNewKeysDefaults() {
        val json = """
            {"id":"legacy","agency":"","label":"","channel":"","mission":"",
             "cycleMinutes":45,"roadConditionTtlMinutes":120,
             "startTimeMs":1000,"startedBy":"Old"}
        """.trimIndent()
        val decoded = StormConfigCodec.decode(json.toByteArray())!!
        assertEquals(0, decoded.cycleP1Minutes)
        assertEquals(45, decoded.cycleMinutes)
        assertEquals(30, decoded.greenUntilMinutes)
        assertEquals(33, decoded.yellowUntilMinutes)
        assertEquals(StormSession.DEFAULT_COVERAGE_RETENTION_HOURS, decoded.coverageRetentionHours, 0.0)
        val session = decoded.toSession()
        assertEquals(45, session.cycleMinutes)
        assertEquals(30, session.greenUntilMinutes)
    }
}
