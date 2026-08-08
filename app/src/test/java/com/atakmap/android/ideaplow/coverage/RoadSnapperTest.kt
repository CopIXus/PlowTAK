package com.atakmap.android.ideaplow.coverage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes a tiny synthetic graph in the exact GraphHopper 1.0 DataAccess
 * binary layout (verified against the shipped Virginia pack) and snaps
 * against it. See RoadSnapperRealPackTest for the real-pack run.
 */
class RoadSnapperTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val degreeFactor = Int.MAX_VALUE / 400.0
    private fun scaled(deg: Double): Int = Math.round(deg * degreeFactor).toInt()

    /**
     * Two roads crossing near (36.00, -86.00):
     *  - e0: west–east, n0(36.00,-86.01) → pillar(36.0005,-86.00) → n1(36.00,-85.99)
     *  - e1: north–south, n2(36.01,-86.00) → n3(35.99,-86.00), no pillars
     */
    private fun writeFixture(dir: File) {
        val nodes = listOf(
            Triple(0, 36.00, -86.01),  // edgeRef, lat, lon
            Triple(0, 36.00, -85.99),
            Triple(1, 36.01, -86.00),
            Triple(1, 35.99, -86.00)
        )

        // nodes file: 12-byte entries (edgeRef, lat, lon).
        val nodeBody = ByteBuffer.allocate(nodes.size * 12).order(ByteOrder.LITTLE_ENDIAN)
        for ((edgeRef, lat, lon) in nodes) {
            nodeBody.putInt(edgeRef)
            nodeBody.putInt(scaled(lat))
            nodeBody.putInt(scaled(lon))
        }
        val nodeHeader = IntArray(20)
        nodeHeader[1] = 12                    // nodeEntryBytes
        nodeHeader[2] = nodes.size            // nodeCount
        nodeHeader[3] = scaled(-86.01)        // minLon
        nodeHeader[4] = scaled(-85.99)        // maxLon
        nodeHeader[5] = scaled(35.99)         // minLat
        nodeHeader[6] = scaled(36.01)         // maxLat
        writeDataAccess(File(dir, "nodes"), nodeHeader, nodeBody.array())

        // edges file: 32-byte entries (nodeA,nodeB,linkA,linkB,flags,dist,geoRef,name).
        val edgeBody = ByteBuffer.allocate(2 * 32).order(ByteOrder.LITTLE_ENDIAN)
        // e0: n0—n1 with one pillar at geoRef 1.
        edgeBody.putInt(0).putInt(1).putInt(-1).putInt(-1)
            .putInt(0).putInt(1_780_000).putInt(1).putInt(0)
        // e1: n2—n3, no pillars.
        edgeBody.putInt(2).putInt(3).putInt(-1).putInt(-1)
            .putInt(0).putInt(2_230_000).putInt(0).putInt(0)
        val edgeHeader = IntArray(20)
        edgeHeader[0] = 32                    // edgeEntryBytes
        edgeHeader[1] = 2                     // edgeCount
        writeDataAccess(File(dir, "edges"), edgeHeader, edgeBody.array())

        // geometry file: int slot 0 unused; geoRef 1 = count + lat/lon pair.
        val geomBody = ByteBuffer.allocate(4 * 4).order(ByteOrder.LITTLE_ENDIAN)
        geomBody.putInt(0)                    // slot 0 (geoRef 0 = none)
        geomBody.putInt(1)                    // pillar count
        geomBody.putInt(scaled(36.0005))
        geomBody.putInt(scaled(-86.00))
        writeDataAccess(File(dir, "geometry"), IntArray(20), geomBody.array())
    }

    /** GH DataAccess: writeUTF("GH"), body length, segment size, 20 BE ints. */
    private fun writeDataAccess(file: File, header: IntArray, body: ByteArray) {
        DataOutputStream(FileOutputStream(file)).use { out ->
            out.writeUTF("GH")
            out.writeLong(body.size.toLong())
            out.writeInt(1 shl 20)
            header.forEach { out.writeInt(it) }
            out.write(ByteArray(100 - 4 - 8 - 4 - 80)) // pad header to 100 bytes
            out.write(body)
        }
    }

    private fun openFixture(): RoadSnapper {
        val dir = tmp.newFolder("gh")
        writeFixture(dir)
        val snapper = RoadSnapper.openOrNull(dir)
        assertNotNull("fixture failed to open", snapper)
        return snapper!!
    }

    @Test
    fun `snaps onto the pillar polyline of the east-west road`() {
        val snapper = openFixture()
        // ~30 m north of e0 between its west tower and the pillar.
        val snap = snapper.snap(36.0006, -86.005, maxDistM = 60.0)
        assertNotNull(snap)
        assertEquals(0, snap!!.edgeId)
        assertTrue("dist was ${snap.distanceM}", snap.distanceM in 1.0..60.0)
        // Snapped point sits on the road (re-snapping it is a no-op).
        val re = snapper.snap(snap.lat, snap.lon, maxDistM = 60.0)!!
        assertTrue(re.distanceM < 1.0)
    }

    @Test
    fun `snaps onto the vertical road without pillars`() {
        val snapper = openFixture()
        // ~9 m east of the n2—n3 line.
        val snap = snapper.snap(36.005, -85.9999)
        assertNotNull(snap)
        assertEquals(1, snap!!.edgeId)
        assertTrue("dist was ${snap.distanceM}", snap.distanceM < 15.0)
        // Snapped longitude pulled onto the road's constant lon.
        assertEquals(-86.00, snap.lon, 1e-4)
        assertEquals(36.005, snap.lat, 1e-4)
    }

    @Test
    fun `far from any road returns null`() {
        val snapper = openFixture()
        // In-bounds corner ~1 km from both roads with default 40 m budget.
        assertNull(snapper.snap(36.0095, -85.9905))
    }

    @Test
    fun `outside pack bounds returns null`() {
        val snapper = openFixture()
        assertNull(snapper.snap(37.5, -86.0))
        assertNull(snapper.snap(36.0, -90.0))
    }

    @Test
    fun `missing or truncated pack fails open`() {
        assertNull(RoadSnapper.openOrNull(File(tmp.root, "nope")))

        // Corrupt: nodes file with bogus magic.
        val bad = tmp.newFolder("bad")
        writeFixture(bad)
        File(bad, "nodes").writeBytes(ByteArray(200))
        assertNull(RoadSnapper.openOrNull(bad))

        // Truncated edges file.
        val trunc = tmp.newFolder("trunc")
        writeFixture(trunc)
        val edges = File(trunc, "edges")
        edges.writeBytes(edges.readBytes().copyOf(110))
        assertNull(RoadSnapper.openOrNull(trunc))
    }
}

/**
 * Opportunistic read-only test against the real Virginia GraphHopper pack.
 * Skips (does not fail) when Maps/virginia is not present.
 */
class RoadSnapperRealPackTest {

    private fun packDir(): File? =
        sequenceOf("../Maps/virginia", "Maps/virginia", "../../Maps/virginia")
            .map { File(it) }
            .firstOrNull { File(it, "nodes").isFile }

    @Test
    fun `virginia pack opens and snaps a known road point`() {
        val dir = packDir()
        org.junit.Assume.assumeTrue("Virginia pack not on disk — skipping", dir != null)

        val graph = GraphHopperGraph.open(dir!!)
        assertNotNull(graph)
        // Verified constants for the shipped 2021 Virginia pack.
        assertEquals(1_260_910, graph!!.nodeCount)
        assertEquals(1_608_060, graph.edgeCount)
        assertTrue(graph.minLat in 36.0..37.0 && graph.maxLat in 39.0..40.0)
        assertTrue(graph.minLon in -84.0..-83.0 && graph.maxLon in -76.0..-75.0)

        val snapper = RoadSnapper.openOrNull(dir)
        assertNotNull(snapper)

        // A tower node is by definition on a road: offset ~11 m and snap back.
        val lat = graph.nodeLat(0)
        val lon = graph.nodeLon(0)
        val snap = snapper!!.snap(lat + 0.0001, lon)
        assertNotNull("expected snap near node 0 at $lat,$lon", snap)
        assertTrue("dist was ${snap!!.distanceM}", snap.distanceM < 40.0)
        val snappedBack = GeoMath.distanceMeters(snap.lat, snap.lon, lat, lon)
        assertTrue("snapped point strayed ${snappedBack} m", snappedBack < 30.0)

        // Ten more towers spread across the graph all snap onto themselves.
        val step = graph.nodeCount / 10
        for (i in 0 until 10) {
            val n = i * step
            val s = snapper.snap(graph.nodeLat(n), graph.nodeLon(n))
            assertNotNull("node $n did not snap", s)
            assertTrue(s!!.distanceM < 1.0)
        }
    }
}
