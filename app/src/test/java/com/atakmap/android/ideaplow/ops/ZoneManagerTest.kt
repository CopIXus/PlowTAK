package com.atakmap.android.ideaplow.ops

import com.atakmap.android.ideaplow.model.SpecialZone
import com.atakmap.android.ideaplow.model.ZoneType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoneManagerTest {

    private val bridge = SpecialZone(
        id = "z1", name = "River bridge", type = ZoneType.BRIDGE,
        cycleMultiplier = 0.5, centerLat = 36.0, centerLon = -86.0, radiusM = 200.0
    )

    private val school = SpecialZone(
        id = "z2", name = "Elm school", type = ZoneType.SCHOOL,
        cycleMultiplier = 0.5, centerLat = 36.1, centerLon = -86.1, radiusM = 0.0,
        polygon = listOf(36.09 to -86.11, 36.11 to -86.11, 36.11 to -86.09, 36.09 to -86.09)
    )

    @Test
    fun `zones persist across restart including polygons`() {
        val persistence = InMemoryPersistence()
        val mgr = ZoneManager(persistence)
        mgr.put(bridge)
        mgr.put(school)

        val reloaded = ZoneManager(persistence)
        assertEquals(2, reloaded.all().size)
        val poly = reloaded.get("z2")!!
        assertTrue(poly.isPolygon)
        assertEquals(4, poly.polygon.size)
        assertEquals(ZoneType.SCHOOL, poly.type)
        assertTrue(poly.contains(36.10, -86.10))
    }

    @Test
    fun `zonesContaining finds only covering zones`() {
        val mgr = ZoneManager(InMemoryPersistence())
        mgr.put(bridge)
        mgr.put(school)

        assertEquals(listOf("z1"), mgr.zonesContaining(36.0, -86.0).map { it.id })
        assertEquals(listOf("z2"), mgr.zonesContaining(36.10, -86.10).map { it.id })
        assertTrue(mgr.zonesContaining(37.0, -87.0).isEmpty())
    }

    @Test
    fun `remove deletes and reports change`() {
        val mgr = ZoneManager(InMemoryPersistence())
        mgr.put(bridge)
        assertTrue(mgr.remove("z1"))
        assertFalse(mgr.remove("z1"))
        assertTrue(mgr.all().isEmpty())
    }

    @Test
    fun `remote add edit and remove converge`() {
        val mgr = ZoneManager(InMemoryPersistence())

        assertTrue(mgr.onRemote(bridge, removed = false))
        // Same payload again — no change, no listener churn.
        assertFalse(mgr.onRemote(bridge, removed = false))
        // Edit converges.
        assertTrue(mgr.onRemote(bridge.copy(radiusM = 300.0), removed = false))
        assertEquals(300.0, mgr.get("z1")!!.radiusM, 1e-9)
        // Remove converges; removing an unknown zone is a no-op.
        assertTrue(mgr.onRemote(bridge, removed = true))
        assertFalse(mgr.onRemote(bridge, removed = true))
    }

    @Test
    fun `listeners fire on change`() {
        val mgr = ZoneManager(InMemoryPersistence())
        var count = 0
        mgr.addListener { count++ }
        mgr.put(bridge)
        mgr.remove("z1")
        assertEquals(2, count)
    }
}
