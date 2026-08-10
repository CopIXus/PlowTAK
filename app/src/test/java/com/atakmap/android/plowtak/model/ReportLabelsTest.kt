package com.atakmap.android.plowtak.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class ReportLabelsTest {

    @Test
    fun hazardIncludesTypeAndCallsign() {
        assertEquals(
            "Ice patch (Unit 12)",
            ReportLabels.hazard("Ice patch", "Unit 12")
        )
    }

    @Test
    fun conditionAppends24hLocalTimeAfterCallsign() {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 14)
        cal.set(Calendar.MINUTE, 5)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val label = ReportLabels.condition("Ice", "DUPLE", cal.timeInMillis)
        assertTrue(label.startsWith("Ice (DUPLE "))
        assertTrue(label.endsWith(")"))
        // 24h clock, zero-padded minutes
        assertTrue(label.contains("14:05"))
    }

    @Test
    fun blankCallsignFallsBackToUnit() {
        assertEquals("Wet (Unit)", ReportLabels.hazard("Wet", "  "))
        val label = ReportLabels.condition("Wet", "", 0L)
        assertTrue(label.startsWith("Wet (Unit "))
    }
}
