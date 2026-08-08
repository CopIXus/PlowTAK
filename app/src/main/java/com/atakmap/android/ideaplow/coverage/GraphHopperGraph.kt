package com.atakmap.android.ideaplow.coverage

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Minimal read-only reader for GraphHopper 1.0 graph storage — just enough
 * of `nodes` (v5), `edges` (v15) and `geometry` (v4) to snap a GPS point to
 * the nearest road edge. No GraphHopper dependency; the format was verified
 * byte-by-byte against the shipped Virginia / Tennessee / North Carolina
 * packs (see docs/roadsnap.md):
 *
 *  - Every DataAccess file: `writeUTF("GH")` (4 bytes), file body length as
 *    big-endian long, segment size int, then twenty big-endian header ints;
 *    the data body starts at byte 100.
 *  - Body values are little-endian (`graph.byte_order=LITTLE_ENDIAN`).
 *  - nodes: 12-byte entries — edgeRef, lat, lon (coords scaled by
 *    Integer.MAX_VALUE / 400).
 *  - edges: 32-byte entries — nodeA, nodeB, linkA, linkB, flags, distance
 *    (mm), geoRef, nameRef. linkA/linkB chain the per-node edge lists.
 *  - geometry: at byte 100 + geoRef*4 an int pillar count, then that many
 *    scaled lat/lon pairs (base→adj order). geoRef 0 = no pillars.
 *
 * Files are memory-mapped read-only, so a state pack costs address space,
 * not heap. Use [open]; it returns null on any structural problem so the
 * caller can fail open to raw GPS.
 */
class GraphHopperGraph private constructor(
    val nodeCount: Int,
    val edgeCount: Int,
    private val nodeEntryBytes: Int,
    private val edgeEntryBytes: Int,
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double,
    private val nodes: ByteBuffer,
    private val edges: ByteBuffer,
    private val geometry: ByteBuffer
) {

    fun nodeLat(node: Int): Double = toDegree(nodes.getInt(node * nodeEntryBytes + 4))
    fun nodeLon(node: Int): Double = toDegree(nodes.getInt(node * nodeEntryBytes + 8))
    private fun nodeEdgeRef(node: Int): Int = nodes.getInt(node * nodeEntryBytes)

    fun edgeNodeA(edge: Int): Int = edges.getInt(edge * edgeEntryBytes)
    fun edgeNodeB(edge: Int): Int = edges.getInt(edge * edgeEntryBytes + 4)
    private fun edgeLinkA(edge: Int): Int = edges.getInt(edge * edgeEntryBytes + 8)
    private fun edgeLinkB(edge: Int): Int = edges.getInt(edge * edgeEntryBytes + 12)
    private fun edgeGeoRef(edge: Int): Int = edges.getInt(edge * edgeEntryBytes + 24)

    /**
     * Edge ids attached to a tower node, walking the linkA/linkB chain.
     * Capped defensively so corrupt data cannot loop forever.
     */
    fun edgesOf(node: Int, out: MutableCollection<Int>) {
        var edge = nodeEdgeRef(node)
        var hops = 0
        while (edge in 0 until edgeCount && hops++ < MAX_NODE_DEGREE) {
            out.add(edge)
            edge = when (node) {
                edgeNodeA(edge) -> edgeLinkA(edge)
                edgeNodeB(edge) -> edgeLinkB(edge)
                else -> return // chain corrupt — stop rather than wander
            }
        }
    }

    /**
     * Full polyline of an edge as [lat0, lon0, lat1, lon1, ...]: tower A,
     * pillar points (stored base→adj), tower B.
     */
    fun edgeGeometry(edge: Int): DoubleArray {
        val geoRef = edgeGeoRef(edge)
        val pillarCount = if (geoRef > 0) {
            val c = geometry.getInt(geoRef * 4)
            if (c in 0..MAX_PILLARS) c else 0
        } else 0

        val out = DoubleArray((pillarCount + 2) * 2)
        val a = edgeNodeA(edge)
        val b = edgeNodeB(edge)
        out[0] = nodeLat(a)
        out[1] = nodeLon(a)
        var pos = geoRef * 4 + 4
        for (i in 0 until pillarCount) {
            out[2 + i * 2] = toDegree(geometry.getInt(pos))
            out[2 + i * 2 + 1] = toDegree(geometry.getInt(pos + 4))
            pos += 8
        }
        out[out.size - 2] = nodeLat(b)
        out[out.size - 1] = nodeLon(b)
        return out
    }

    companion object {

        /** GraphHopper Helper.DEGREE_FACTOR: Integer.MAX_VALUE / 400. */
        private const val DEGREE_FACTOR = Int.MAX_VALUE / 400.0
        private const val HEADER_BYTES = 100L
        private const val MAX_NODE_DEGREE = 1000
        private const val MAX_PILLARS = 100_000

        private fun toDegree(scaled: Int): Double = scaled / DEGREE_FACTOR

        /**
         * Open a GH 1.0 pack directory (must contain `nodes`, `edges`,
         * `geometry`). Returns null — never throws — when files are missing
         * or fail structural sanity checks.
         */
        fun open(dir: File): GraphHopperGraph? {
            return try {
                val nodesBuf = mapBody(File(dir, "nodes")) ?: return null
                val edgesBuf = mapBody(File(dir, "edges")) ?: return null
                val geomBuf = mapBody(File(dir, "geometry")) ?: return null

                val nodeHeader = readHeaderInts(File(dir, "nodes"))
                val edgeHeader = readHeaderInts(File(dir, "edges"))

                // nodes header: [1]=entryBytes, [2]=count, [3..6]=bounds.
                val nodeEntryBytes = nodeHeader[1]
                val nodeCount = nodeHeader[2]
                // edges header: [0]=entryBytes, [1]=count.
                val edgeEntryBytes = edgeHeader[0]
                val edgeCount = edgeHeader[1]

                if (nodeEntryBytes < 12 || nodeCount <= 0) return null
                if (edgeEntryBytes < 32 || edgeCount <= 0) return null
                if (nodesBuf.capacity() < nodeCount.toLong() * nodeEntryBytes) return null
                if (edgesBuf.capacity() < edgeCount.toLong() * edgeEntryBytes) return null

                val graph = GraphHopperGraph(
                    nodeCount = nodeCount,
                    edgeCount = edgeCount,
                    nodeEntryBytes = nodeEntryBytes,
                    edgeEntryBytes = edgeEntryBytes,
                    minLon = toDegree(nodeHeader[3]),
                    maxLon = toDegree(nodeHeader[4]),
                    minLat = toDegree(nodeHeader[5]),
                    maxLat = toDegree(nodeHeader[6]),
                    nodes = nodesBuf,
                    edges = edgesBuf,
                    geometry = geomBuf
                )
                // Spot-check: the first node must sit inside the bounds.
                val lat0 = graph.nodeLat(0)
                val lon0 = graph.nodeLon(0)
                if (lat0 !in graph.minLat - 0.1..graph.maxLat + 0.1) return null
                if (lon0 !in graph.minLon - 0.1..graph.maxLon + 0.1) return null
                graph
            } catch (e: Exception) {
                null
            }
        }

        /** Map the little-endian data body (everything after the header). */
        private fun mapBody(file: File): ByteBuffer? {
            if (!file.isFile || file.length() <= HEADER_BYTES) return null
            RandomAccessFile(file, "r").use { raf ->
                val buf = raf.channel.map(
                    FileChannel.MapMode.READ_ONLY, HEADER_BYTES, file.length() - HEADER_BYTES
                )
                buf.order(ByteOrder.LITTLE_ENDIAN)
                return buf
            }
        }

        /** The 20 big-endian header ints that follow magic + length + segment. */
        private fun readHeaderInts(file: File): IntArray {
            RandomAccessFile(file, "r").use { raf ->
                val magic = ByteArray(4)
                raf.readFully(magic)
                require(
                    magic[0] == 0.toByte() && magic[1] == 2.toByte() &&
                            magic[2] == 'G'.code.toByte() && magic[3] == 'H'.code.toByte()
                ) { "not a GraphHopper DataAccess file: $file" }
                raf.readLong() // body length
                raf.readInt()  // segment size
                return IntArray(20) { raf.readInt() }
            }
        }
    }
}
