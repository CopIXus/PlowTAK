package com.atakmap.android.ideaplow.ops

import com.atakmap.android.ideaplow.cot.codec.RouteAssignmentCotCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteAssignmentTest {

    private fun assignment(
        uid: String = "PLOW-12",
        route: String = "RT-7",
        timeMs: Long = 1_700_000_000_000L
    ) = RouteAssignment(
        vehicleUid = uid,
        callsign = "Plow-12",
        routeId = route,
        source = RouteAssignment.Source.GIS,
        assignedBy = "Supervisor-1",
        timeMs = timeMs
    )

    // ------------------------------------------------------------ manager

    @Test
    fun `assign notifies and is queryable`() {
        val mgr = RouteAssignmentManager()
        var broadcasts = 0
        var changes = 0
        mgr.addListener(object : RouteAssignmentManager.Listener {
            override fun onAssignmentsChanged(assignments: List<RouteAssignment>) { changes++ }
            override fun onLocalAssignment(assignment: RouteAssignment) { broadcasts++ }
        })

        mgr.assign(assignment())
        assertEquals(1, broadcasts)
        assertEquals(1, changes)
        assertEquals("RT-7", mgr.assignmentFor("PLOW-12")!!.routeId)
        assertEquals(listOf("PLOW-12"), mgr.unitsOn("RT-7").map { it.vehicleUid })
    }

    @Test
    fun `unassign leaves a broadcastable tombstone`() {
        val mgr = RouteAssignmentManager()
        mgr.assign(assignment())
        var last: RouteAssignment? = null
        mgr.addListener(object : RouteAssignmentManager.Listener {
            override fun onAssignmentsChanged(assignments: List<RouteAssignment>) {}
            override fun onLocalAssignment(assignment: RouteAssignment) { last = assignment }
        })

        mgr.unassign("PLOW-12", "Supervisor-1", 1_700_000_100_000L)
        assertNull(mgr.assignmentFor("PLOW-12"))
        assertEquals("", last!!.routeId)
        assertEquals("PLOW-12", last!!.vehicleUid)
    }

    @Test
    fun `remote convergence is last-writer-wins`() {
        val mgr = RouteAssignmentManager()
        mgr.assign(assignment(route = "RT-7", timeMs = 2_000L))

        // Older remote decision loses.
        mgr.onRemote(assignment(route = "RT-OLD", timeMs = 1_000L))
        assertEquals("RT-7", mgr.assignmentFor("PLOW-12")!!.routeId)

        // Newer remote decision wins.
        mgr.onRemote(assignment(route = "RT-NEW", timeMs = 3_000L))
        assertEquals("RT-NEW", mgr.assignmentFor("PLOW-12")!!.routeId)
    }

    @Test
    fun `assignments survive a restart via persistence`() {
        val store = InMemoryPersistence()
        RouteAssignmentManager(store).apply {
            assign(assignment())
            assign(assignment(uid = "PLOW-13", route = "RT-8"))
        }
        val reloaded = RouteAssignmentManager(store)
        assertEquals("RT-7", reloaded.assignmentFor("PLOW-12")!!.routeId)
        assertEquals("RT-8", reloaded.assignmentFor("PLOW-13")!!.routeId)
    }

    // --------------------------------------------------------- line codec

    @Test
    fun `line codec round-trips with pipes in fields`() {
        val orig = assignment().copy(callsign = "Weird|Callsign")
        val back = RouteAssignmentManager.decodeLine(
            RouteAssignmentManager.encodeLine(orig)
        )!!
        assertEquals(orig, back)
    }

    @Test
    fun `garbage lines decode to null`() {
        assertNull(RouteAssignmentManager.decodeLine(""))
        assertNull(RouteAssignmentManager.decodeLine("a|b|c"))
        assertNull(RouteAssignmentManager.decodeLine("a|b|c|gis|e|notatime"))
    }

    // ---------------------------------------------------------- CoT codec

    @Test
    fun `cot detail round-trips`() {
        val orig = assignment()
        val back = RouteAssignmentCotCodec.decode(RouteAssignmentCotCodec.encode(orig))!!
        assertEquals(orig, back)
    }

    @Test
    fun `unassignment tombstone rides the wire`() {
        val tombstone = assignment(route = "")
        val back = RouteAssignmentCotCodec.decode(RouteAssignmentCotCodec.encode(tombstone))!!
        assertTrue(back.routeId.isEmpty())
    }

    @Test
    fun `event uid is stable per vehicle`() {
        assertEquals(
            RouteAssignmentCotCodec.eventUidFor("PLOW-12"),
            RouteAssignmentCotCodec.eventUidFor("PLOW-12")
        )
    }
}
