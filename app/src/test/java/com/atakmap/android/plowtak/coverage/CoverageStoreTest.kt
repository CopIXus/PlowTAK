package com.atakmap.android.plowtak.coverage

import com.atakmap.android.plowtak.model.MaterialMode
import com.atakmap.android.plowtak.model.TrackPoint
import com.atakmap.android.plowtak.model.TreatSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CoverageStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val now = 1_700_000_000_000L

    private fun segment(uid: String, start: Long, stormId: String = "storm-A"): TreatSegment {
        val points = listOf(
            TrackPoint(36.0, -86.0, start, 0.0),
            TrackPoint(36.001, -86.0, start + 5000, 0.0)
        )
        return TreatSegment(
            id = TreatSegment.makeId(uid, start),
            vehicleUid = uid, callsign = uid, stormId = stormId, operatorId = "op",
            material = MaterialMode.SALT, widthM = 3.0,
            points = points, startTimeMs = start, endTimeMs = start + 5000
        )
    }

    @Test
    fun `local segments persist across restart`() {
        val dir = tmp.newFolder()
        val store = CoverageStore(dir)
        store.setStorm("storm-A")
        store.addLocal(segment("PLOW-1", now))
        store.addLocal(segment("PLOW-1", now + 60_000))

        val reloaded = CoverageStore(dir)
        reloaded.setStorm("storm-A")
        assertEquals(2, reloaded.size())
    }

    @Test
    fun `storm change swaps scope`() {
        val dir = tmp.newFolder()
        val store = CoverageStore(dir)
        store.setStorm("storm-A")
        store.addLocal(segment("PLOW-1", now))

        store.setStorm("storm-B")
        assertEquals(0, store.size())

        store.setStorm("storm-A")
        assertEquals(1, store.size())
    }

    @Test
    fun `remote merge dedupes and honors storm scope`() {
        val store = CoverageStore(null)
        store.setStorm("storm-A")

        val seg = segment("SALT-9", now)
        assertTrue(store.mergeRemote(seg))
        assertFalse(store.mergeRemote(seg)) // dup
        assertFalse(store.mergeRemote(segment("SALT-9", now + 1000, stormId = "storm-OLD")))
        assertEquals(1, store.size())
    }

    @Test
    fun `pending share drains once and can requeue`() {
        val store = CoverageStore(null)
        store.setStorm("storm-A")
        store.addLocal(segment("PLOW-1", now))
        store.addLocal(segment("PLOW-1", now + 60_000))
        // Remote merges never enter the share queue.
        store.mergeRemote(segment("SALT-9", now))

        val batch = store.drainPendingShare(10)
        assertEquals(2, batch.size)
        assertTrue(store.drainPendingShare(10).isEmpty())

        store.requeueForShare(batch)
        assertEquals(2, store.drainPendingShare(10).size)
    }

    @Test
    fun `prune drops expired segments`() {
        val store = CoverageStore(null)
        store.setStorm("storm-A")
        val old = segment("PLOW-1", now - 24 * 3_600_000L) // 24 h old
        val fresh = segment("PLOW-1", now - 10 * 60_000L)  // 10 min old
        store.addLocal(old)
        store.addLocal(fresh)

        store.pruneExpired(FreshnessModel(45, 0.75, retentionHours = 12.0), now)
        assertEquals(1, store.size())
        assertEquals(fresh.id, store.all()[0].id)
    }

    @Test
    fun `nearby queries use the spatial index`() {
        val store = CoverageStore(null)
        store.setStorm("storm-A")
        store.addLocal(segment("PLOW-1", now))            // at 36.0, -86.0
        store.mergeRemote(
            segment("SALT-9", now + 1000).let { seg ->
                seg.copy(points = seg.points.map { it.copy(lat = it.lat + 0.5) })
            }
        ) // ~55 km north

        val near = store.nearby(36.0, -86.0, 500.0)
        assertEquals(1, near.size)
        assertEquals("PLOW-1", near[0].vehicleUid)

        val around = store.nearSegment(store.all().first { it.vehicleUid == "PLOW-1" }, 500.0)
        assertTrue(around.none { it.vehicleUid == "PLOW-1" })
    }

    @Test
    fun `index survives storm reload`() {
        val dir = tmp.newFolder()
        val store = CoverageStore(dir)
        store.setStorm("storm-A")
        store.addLocal(segment("PLOW-1", now))

        val reloaded = CoverageStore(dir)
        reloaded.setStorm("storm-A")
        assertEquals(1, reloaded.nearby(36.0, -86.0, 500.0).size)
    }

    @Test
    fun `count cap prunes oldest segments first`() {
        val store = CoverageStore(null)
        store.setStorm("storm-A")
        for (i in 0 until 10) {
            store.addLocal(segment("PLOW-1", now + i * 60_000L))
        }
        store.pruneOverCount(4)
        assertEquals(4, store.size())
        // The four newest survive.
        assertTrue(store.all().all { it.startTimeMs >= now + 6 * 60_000L })
        // No-op when under the cap.
        store.pruneOverCount(100)
        assertEquals(4, store.size())
    }

    @Test
    fun `listener sees adds and removals`() {
        val store = CoverageStore(null)
        store.setStorm("storm-A")
        val added = mutableListOf<Pair<String, Boolean>>()
        val removed = mutableListOf<String>()
        store.addListener(object : CoverageStore.Listener {
            override fun onSegmentAdded(segment: TreatSegment, local: Boolean) {
                added.add(segment.id to local)
            }
            override fun onSegmentsRemoved(ids: Collection<String>) {
                removed.addAll(ids)
            }
        })

        val localSeg = segment("PLOW-1", now)
        store.addLocal(localSeg)
        store.mergeRemote(segment("SALT-9", now))
        assertEquals(listOf(localSeg.id to true, segment("SALT-9", now).id to false), added)

        store.pruneExpired(FreshnessModel(45, 0.75, retentionHours = 0.0001), now + 3_600_000L)
        assertEquals(2, removed.size)
    }
}
