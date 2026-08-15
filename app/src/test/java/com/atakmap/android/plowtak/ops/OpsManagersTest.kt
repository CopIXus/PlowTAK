package com.atakmap.android.plowtak.ops

import com.atakmap.android.plowtak.model.AlertEvent
import com.atakmap.android.plowtak.model.AlertState
import com.atakmap.android.plowtak.model.Facility
import com.atakmap.android.plowtak.model.FacilityType
import com.atakmap.android.plowtak.model.StormSession
import com.atakmap.android.plowtak.model.VehicleStatus
import com.atakmap.android.plowtak.model.VehicleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusManagerTest {

    @Test
    fun `derived status follows shift and treating`() {
        val sm = StatusManager()
        assertEquals(VehicleStatus.OFF_DUTY, sm.current)

        sm.updateShift(true)
        assertEquals(VehicleStatus.DEADHEAD, sm.current)

        sm.updateTreating(true)
        assertEquals(VehicleStatus.TREATING, sm.current)

        sm.updateTreating(false)
        assertEquals(VehicleStatus.DEADHEAD, sm.current)

        sm.updateShift(false)
        assertEquals(VehicleStatus.OFF_DUTY, sm.current)
    }

    @Test
    fun `manual status is sticky until cleared`() {
        val sm = StatusManager()
        sm.updateShift(true)
        sm.setManual(VehicleStatus.LOADING)
        assertEquals(VehicleStatus.LOADING, sm.current)

        // Treating rule turning true must NOT override a manual status.
        sm.updateTreating(true)
        assertEquals(VehicleStatus.LOADING, sm.current)

        sm.clearManual()
        assertEquals(VehicleStatus.TREATING, sm.current)
    }

    @Test
    fun `ending shift clears manual status`() {
        val sm = StatusManager()
        sm.updateShift(true)
        sm.setManual(VehicleStatus.OUT_OF_SERVICE)
        sm.updateShift(false)
        assertEquals(VehicleStatus.OFF_DUTY, sm.current)
        assertNull(sm.manual)
    }

    @Test
    fun `suggestions are prompts not auto-flips`() {
        val sm = StatusManager()
        sm.updateShift(true)
        var suggested: VehicleStatus? = null
        sm.suggestionListener = StatusManager.SuggestionListener { s, _ -> suggested = s }

        sm.suggest(VehicleStatus.LOADING, "Salt dome")
        assertEquals(VehicleStatus.LOADING, suggested)
        // Status unchanged — driver stays authoritative.
        assertEquals(VehicleStatus.DEADHEAD, sm.current)
    }
}

class FacilityGeofencesTest {

    private val dome = Facility("f1", "Main dome", FacilityType.SALT_DOME, 36.0, -86.0, 100.0)
    private val fuel = Facility("f2", "Fuel", FacilityType.FUEL, 36.1, -86.1, 50.0)

    @Test
    fun `enter and exit transitions fire once`() {
        val geo = FacilityGeofences(InMemoryPersistence())
        geo.add(dome)

        // Approach from ~1 km away.
        assertTrue(geo.update(36.01, -86.0, 1000L).isEmpty())

        val enter = geo.update(36.0001, -86.0, 2000L)
        assertEquals(1, enter.size)
        assertTrue(enter[0].entered)

        // Still inside — no repeat transition.
        assertTrue(geo.update(36.0002, -86.0, 3000L).isEmpty())

        val exit = geo.update(36.01, -86.0, 4000L)
        assertEquals(1, exit.size)
        assertFalse(exit[0].entered)
    }

    @Test
    fun `salt dome entry logs a reload event`() {
        val geo = FacilityGeofences(InMemoryPersistence())
        geo.add(dome)
        geo.add(fuel)

        geo.update(36.0001, -86.0, 5000L, operatorId = "op-1") // dome: reload
        geo.update(36.1, -86.1, 6000L, operatorId = "op-1")    // fuel: no reload

        assertEquals(1, geo.reloads().size)
        assertEquals("f1", geo.reloads()[0].facilityId)
        assertEquals(1, geo.reloadCountSince(0L))
        assertEquals(0, geo.reloadCountSince(5001L))
    }

    @Test
    fun `facilities and reloads persist`() {
        val persistence = InMemoryPersistence()
        val geo = FacilityGeofences(persistence)
        geo.add(dome)
        geo.update(36.0, -86.0, 1000L)

        val reloaded = FacilityGeofences(persistence)
        assertEquals(1, reloaded.all().size)
        assertEquals("Main dome", reloaded.all()[0].name)
        assertEquals(1, reloaded.reloads().size)
    }
}

class ShiftLogTest {

    @Test
    fun `shift lifecycle with timestamps`() {
        val log = ShiftLog(InMemoryPersistence())
        assertFalse(log.isOnShift)

        val shift = log.startShift("Jane Doe", "op-1", 1000L)
        assertTrue(log.isOnShift)
        assertEquals("Jane Doe", shift.operatorName)
        assertEquals(1000L, shift.startTimeMs)

        log.endShift(9000L)
        assertFalse(log.isOnShift)
        assertEquals(1, log.shiftHistory().size)
        assertEquals(8000L, log.shiftHistory()[0].durationMs(99_999L))
    }

    @Test
    fun `crew swap ends previous shift automatically`() {
        val log = ShiftLog(InMemoryPersistence())
        log.startShift("Jane", "op-1", 1000L)
        log.startShift("Bob", "op-2", 5000L)

        assertEquals("Bob", log.currentShift?.operatorName)
        assertEquals(1, log.shiftHistory().size)
        assertEquals(5000L, log.shiftHistory()[0].endTimeMs)
    }

    @Test
    fun `active shift survives restart`() {
        val persistence = InMemoryPersistence()
        ShiftLog(persistence).startShift("Jane", "op-1", 1000L)

        val reloaded = ShiftLog(persistence)
        assertTrue(reloaded.isOnShift)
        assertEquals("op-1", reloaded.currentShift?.operatorId)
    }
}

class StormSessionManagerTest {

    @Test
    fun `start and end session`() {
        val mgr = StormSessionManager(InMemoryPersistence())
        assertEquals("", mgr.activeStormId)

        val s = mgr.startSession("Sup-1", 1_736_951_234_000L)
        assertTrue(s.id.isNotEmpty())
        assertEquals(s.id, mgr.activeStormId)

        val ended = mgr.endSession(1_736_999_999_000L)
        assertNotNull(ended)
        assertEquals("", mgr.activeStormId)
        assertFalse(mgr.current!!.isActive)
    }

    @Test
    fun `remote storms are catalogued but not auto joined`() {
        val mgr = StormSessionManager(InMemoryPersistence())
        mgr.startSession("Sup-1", 1000L, label = "Local", agency = "Us")
        val localId = mgr.activeStormId

        // Other agencies' storms are heard, never auto-joined.
        assertFalse(mgr.noteRemote(StormSession("old", 500L, 0L, "Sup-2", label = "A", agency = "Them")))
        assertFalse(mgr.noteRemote(StormSession("newer", 2000L, 0L, "Sup-2", label = "B", agency = "Them")))
        assertEquals(localId, mgr.activeStormId)
        assertTrue(mgr.knownStorms().any { it.id == "newer" })
        assertEquals("Them · B · newer", mgr.knownStorms().first { it.id == "newer" }.displayName())
    }

    @Test
    fun `join selects reporting storm`() {
        val mgr = StormSessionManager(InMemoryPersistence())
        val a = StormSession("a", 1L, startedBy = "A", agency = "AgencyA")
        val b = StormSession("b", 2L, startedBy = "B", agency = "AgencyB")
        mgr.noteRemote(a)
        mgr.noteRemote(b)
        assertTrue(mgr.join(b))
        assertEquals("b", mgr.activeStormId)
        assertTrue(mgr.join(a))
        assertEquals("a", mgr.activeStormId)
    }

    @Test
    fun `leave stops reporting without ending catalog storm`() {
        val mgr = StormSessionManager(InMemoryPersistence())
        val s = mgr.startSession("Sup-1", 1000L, label = "X")
        mgr.leave()
        assertNull(mgr.current)
        assertTrue(mgr.knownStorms().first { it.id == s.id }.isActive)
    }

    @Test
    fun `remote end broadcast ends local session`() {
        val mgr = StormSessionManager(InMemoryPersistence())
        val s = mgr.startSession("Sup-1", 1000L)

        assertTrue(mgr.noteRemote(StormSession(s.id, 1000L, 5000L, "Sup-1")))
        assertEquals("", mgr.activeStormId)
        assertEquals(5000L, mgr.current!!.endTimeMs)
    }

    @Test
    fun `session persists across restart`() {
        val persistence = InMemoryPersistence()
        val started = StormSessionManager(persistence).startSession(
            "Sup-1", 1000L, label = "South", agency = "VDOT", missionName = "vdot-south",
            cycleMinutes = 30, cycleP1Minutes = 15, coverageRetentionHours = 0.0,
            roadConditionTtlMinutes = 90
        )

        val reloaded = StormSessionManager(persistence)
        assertEquals(started.id, reloaded.activeStormId)
        assertEquals("VDOT", reloaded.activeSession()?.agency)
        assertEquals("South", reloaded.activeSession()?.label)
        assertEquals("vdot-south", reloaded.activeSession()?.missionName)
        assertEquals(30, reloaded.activeSession()?.cycleMinutes)
        assertEquals(15, reloaded.activeSession()?.cycleP1Minutes)
        assertEquals(0.0, reloaded.activeSession()?.coverageRetentionHours ?: -1.0, 0.0)
        assertEquals(90, reloaded.activeSession()?.roadConditionTtlMinutes)
    }

    @Test
    fun `updateCoverageSettings patches timers`() {
        val mgr = StormSessionManager(InMemoryPersistence())
        mgr.startSession("Sup-1", 1000L)
        val next = mgr.updateCoverageSettings(
            cycleMinutes = 20,
            cycleP1Minutes = 10,
            coverageRetentionHours = 8.0,
            roadConditionTtlMinutes = 60
        )!!
        assertEquals(20, next.cycleMinutes)
        assertEquals(10, next.cycleP1Minutes)
        assertEquals(8.0, next.coverageRetentionHours, 0.0)
        assertEquals(60, next.roadConditionTtlMinutes)
    }

    @Test
    fun `legacy pipe line without new fields still decodes`() {
        val legacy = "id1|1000|0|Sup|Lab|Ag|miss|ch|45|120"
        val s = StormSessionManager.decodeSession(legacy)!!
        assertEquals(45, s.cycleMinutes)
        assertEquals(120, s.roadConditionTtlMinutes)
        assertEquals(0, s.cycleP1Minutes)
        assertEquals(0.0, s.coverageRetentionHours, 0.0)
    }
}

class AlertManagerTest {

    private fun alert(uid: String = "PLOW-1-distress") = AlertEvent(
        uid = uid, vehicleUid = "PLOW-1", callsign = "Plow-1",
        vehicleType = VehicleType.PLOW, lat = 36.0, lon = -86.0, timeMs = 1000L
    )

    @Test
    fun `raise ack clear workflow`() {
        val mgr = AlertManager()
        val broadcasts = mutableListOf<AlertEvent>()
        mgr.addListener(object : AlertManager.Listener {
            override fun onAlertsChanged(alerts: List<AlertEvent>) {}
            override fun onLocalTransition(alert: AlertEvent) { broadcasts.add(alert) }
        })

        mgr.raiseLocal(alert())
        assertEquals(1, mgr.activeAlerts().size)
        assertEquals(AlertState.ACTIVE, broadcasts.last().state)

        mgr.acknowledge("PLOW-1-distress", "Sup-1")
        assertEquals(AlertState.ACKNOWLEDGED, mgr.get("PLOW-1-distress")?.state)
        assertEquals("Sup-1", broadcasts.last().handledBy)
        // Acked alerts still show in the list.
        assertEquals(1, mgr.activeAlerts().size)

        mgr.clear("PLOW-1-distress", "Sup-1")
        assertTrue(mgr.activeAlerts().isEmpty())
        assertEquals(AlertState.CLEARED, broadcasts.last().state)
    }

    @Test
    fun `remote updates apply without re-broadcast`() {
        val mgr = AlertManager()
        val broadcasts = mutableListOf<AlertEvent>()
        mgr.addListener(object : AlertManager.Listener {
            override fun onAlertsChanged(alerts: List<AlertEvent>) {}
            override fun onLocalTransition(alert: AlertEvent) { broadcasts.add(alert) }
        })

        mgr.onRemote(alert())
        assertEquals(1, mgr.activeAlerts().size)
        assertTrue(broadcasts.isEmpty())
    }

    @Test
    fun `stale active update cannot resurrect cleared alert`() {
        val mgr = AlertManager()
        mgr.onRemote(alert().copy(state = AlertState.CLEARED, timeMs = 5000L))
        mgr.onRemote(alert().copy(state = AlertState.ACTIVE, timeMs = 1000L))
        assertTrue(mgr.activeAlerts().isEmpty())
    }
}
