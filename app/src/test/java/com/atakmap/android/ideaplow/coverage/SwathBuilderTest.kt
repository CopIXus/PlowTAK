package com.atakmap.android.ideaplow.coverage

import com.atakmap.android.ideaplow.model.MaterialMode
import com.atakmap.android.ideaplow.model.TrackPoint
import com.atakmap.android.ideaplow.model.TreatSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SwathBuilderTest {

    private val segments = mutableListOf<TreatSegment>()

    private fun builder(config: SwathBuilder.Config = SwathBuilder.Config()) =
        SwathBuilder(config) { segments.add(it) }.apply {
            setContext(SwathBuilder.Context("PLOW-12", "Plow-12", "storm-1", "op-7"))
        }

    /** ~0.0001 deg latitude ≈ 11.1 m northward. */
    private fun latStep(steps: Int): Double = 36.0 + steps * 0.0001

    @Test
    fun `no segment while not treating`() {
        val b = builder()
        repeat(10) { i ->
            b.onSample(latStep(i), -86.0, 0.0, 1000L * i, false, MaterialMode.NONE, 3.0)
        }
        b.flush()
        assertTrue(segments.isEmpty())
    }

    @Test
    fun `treating run produces one segment with context stamped`() {
        val b = builder()
        repeat(5) { i ->
            b.onSample(latStep(i), -86.0, 0.0, 1000L * i, true, MaterialMode.PLOW_ONLY, 3.0)
        }
        b.onSample(latStep(5), -86.0, 0.0, 5000L, false, MaterialMode.NONE, 3.0)

        assertEquals(1, segments.size)
        val seg = segments[0]
        assertEquals("PLOW-12", seg.vehicleUid)
        assertEquals("Plow-12", seg.callsign)
        assertEquals("storm-1", seg.stormId)
        assertEquals("op-7", seg.operatorId)
        assertEquals(MaterialMode.PLOW_ONLY, seg.material)
        assertEquals(3.0, seg.widthM, 1e-9)
        assertEquals(0L, seg.startTimeMs)
        assertEquals(4000L, seg.endTimeMs)
    }

    @Test
    fun `jitter below min spacing is thinned`() {
        val b = builder(SwathBuilder.Config(minPointSpacingM = 5.0, simplifyToleranceM = 0.0))
        // Stopped at a light: 20 samples all within ~1 m, then a real move.
        repeat(20) { i ->
            b.onSample(36.0 + i * 0.000_001, -86.0, 0.0, 1000L * i, true, MaterialMode.SALT, 3.0)
        }
        b.onSample(36.001, -86.0, 0.0, 30_000L, true, MaterialMode.SALT, 3.0)
        b.flush()

        assertEquals(1, segments.size)
        // First point + the real move; jitter dropped.
        assertEquals(2, segments[0].points.size)
    }

    @Test
    fun `collinear points are simplified away`() {
        val b = builder(SwathBuilder.Config(minPointSpacingM = 1.0, simplifyToleranceM = 2.0))
        // Straight north run: 50 points, perfectly collinear.
        repeat(50) { i ->
            b.onSample(latStep(i), -86.0, 0.0, 1000L * i, true, MaterialMode.PLOW_ONLY, 3.0)
        }
        b.flush()

        assertEquals(1, segments.size)
        // Douglas-Peucker on a straight line keeps only the endpoints.
        assertEquals(2, segments[0].points.size)
        assertEquals(0L, segments[0].points.first().timeMs)
        assertEquals(49_000L, segments[0].points.last().timeMs)
    }

    @Test
    fun `corner survives simplification`() {
        val b = builder(SwathBuilder.Config(minPointSpacingM = 1.0, simplifyToleranceM = 2.0))
        // North 20 steps, then east 20 steps — the corner must be kept.
        repeat(20) { i ->
            b.onSample(latStep(i), -86.0, 0.0, 1000L * i, true, MaterialMode.PLOW_ONLY, 3.0)
        }
        repeat(20) { i ->
            b.onSample(latStep(19), -86.0 + (i + 1) * 0.0001, 90.0, 20_000L + 1000L * i,
                true, MaterialMode.PLOW_ONLY, 3.0)
        }
        b.flush()

        assertEquals(1, segments.size)
        val pts = segments[0].points
        assertEquals(3, pts.size) // start, corner, end
        assertEquals(latStep(19), pts[1].lat, 1e-9)
        assertEquals(-86.0, pts[1].lon, 1e-9)
    }

    @Test
    fun `time gap breaks the segment`() {
        val b = builder()
        repeat(3) { i ->
            b.onSample(latStep(i), -86.0, 0.0, 1000L * i, true, MaterialMode.PLOW_ONLY, 3.0)
        }
        // 60 s GPS outage (config maxGapMs = 30 s)
        repeat(3) { i ->
            b.onSample(latStep(10 + i), -86.0, 0.0, 62_000L + 1000L * i, true, MaterialMode.PLOW_ONLY, 3.0)
        }
        b.flush()
        assertEquals(2, segments.size)
    }

    @Test
    fun `material change breaks the segment`() {
        val b = builder()
        repeat(3) { i ->
            b.onSample(latStep(i), -86.0, 0.0, 1000L * i, true, MaterialMode.PLOW_ONLY, 3.0)
        }
        repeat(3) { i ->
            b.onSample(latStep(3 + i), -86.0, 0.0, 3000L + 1000L * i, true, MaterialMode.PLOW_AND_SALT, 3.0)
        }
        b.flush()
        assertEquals(2, segments.size)
        assertEquals(MaterialMode.PLOW_ONLY, segments[0].material)
        assertEquals(MaterialMode.PLOW_AND_SALT, segments[1].material)
    }

    @Test
    fun `max points emits and chains continuously`() {
        val b = builder(
            SwathBuilder.Config(
                minPointSpacingM = 1.0, simplifyToleranceM = 0.0, maxPointsPerSegment = 10
            )
        )
        repeat(25) { i ->
            b.onSample(latStep(i), -86.0, 0.0, 1000L * i, true, MaterialMode.SALT, 3.0)
        }
        b.flush()

        assertTrue(segments.size >= 2)
        // Chained: each segment starts where the previous ended.
        for (i in 1 until segments.size) {
            assertEquals(segments[i - 1].points.last().lat, segments[i].points.first().lat, 1e-12)
        }
    }

    @Test
    fun `single stray point emits nothing`() {
        val b = builder()
        b.onSample(36.0, -86.0, 0.0, 0L, true, MaterialMode.SALT, 3.0)
        b.flush()
        assertTrue(segments.isEmpty())
    }

    @Test
    fun `simplify keeps subset of original points`() {
        val original = (0 until 30).map {
            TrackPoint(latStep(it), -86.0 + (it % 3) * 0.000_001, it * 1000L, 0.0)
        }
        val simplified = SwathBuilder.simplify(original, 2.0)
        assertTrue(simplified.size < original.size)
        assertTrue(original.containsAll(simplified))
        assertEquals(original.first(), simplified.first())
        assertEquals(original.last(), simplified.last())
    }
}
