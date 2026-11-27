package io.github.thibaultbee.streampack.core.elements.processing.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultSurfaceProcessorCfrTest {

    @Test
    fun `CfrPtsClock emits exact rational frame intervals despite wakeup jitter`() {
        val clock = CfrPtsClock()
        val baseNs = 10_000_000_000L

        // Simulate 60 fps ticks with realistic +/- 3ms wakeup jitter
        val jitteredWakeupsNs = listOf(
            baseNs,
            baseNs + 18_900_000L, // +2.23ms late
            baseNs + 31_500_000L, // -1.83ms early
            baseNs + 52_400_000L, // +2.40ms late
            baseNs + 66_666_666L, // exact
            baseNs + 80_100_000L  // -3.23ms early
        )

        val emitted = jitteredWakeupsNs.map { wakeup ->
            clock.nextPtsNs(60, wakeup)!!
        }

        assertEquals(baseNs, emitted[0])
        assertEquals(baseNs + 16_666_666L, emitted[1])
        assertEquals(baseNs + 33_333_333L, emitted[2])
        assertEquals(baseNs + 50_000_000L, emitted[3])
        assertEquals(baseNs + 66_666_666L, emitted[4])
        assertEquals(baseNs + 83_333_333L, emitted[5])

        // After exactly 60 frames (counter = 60), cumulative drift is 0 ns
        assertEquals(
            baseNs + 1_000_000_000L,
            CfrPtsClock.computeGridPtsNs(baseNs, 60L, 60)
        )
    }

    @Test
    fun `CfrPtsClock skips premature tick when grid runs ahead of clock beyond drift limit`() {
        val clock = CfrPtsClock(driftLimitNs = 30_000_000L)
        val baseNs = 5_000_000_000L

        val first = clock.nextPtsNs(60, baseNs)
        assertEquals(baseNs, first)

        // Feed a clock reading where calculated PTS (baseNs + 33.33ms) is > 30ms ahead of nowNs
        val second = clock.nextPtsNs(60, baseNs + 16_666_666L)
        assertEquals(baseNs + 16_666_666L, second)

        val premature = clock.nextPtsNs(60, baseNs + 2_000_000L)
        assertNull(premature)

        // Subsequent normal tick resumes cleanly at counter = 2 without duplicating or going backward
        val resumed = clock.nextPtsNs(60, baseNs + 33_333_333L)
        assertEquals(baseNs + 33_333_333L, resumed)
    }

    @Test
    fun `CfrPtsClock skips missed slots after a stall and preserves monotonicity`() {
        val clock = CfrPtsClock(driftLimitNs = 30_000_000L)
        val baseNs = 2_000_000_000L

        val first = clock.nextPtsNs(60, baseNs)!!
        val second = clock.nextPtsNs(60, baseNs + 16_666_666L)!!

        // Simulate a 200ms GL stall
        val afterStallNowNs = baseNs + 216_666_666L
        val afterStallPts = clock.nextPtsNs(60, afterStallNowNs)
        assertNotNull(afterStallPts)
        assertTrue(afterStallPts!! > second)
        assertTrue(kotlin.math.abs(afterStallPts - afterStallNowNs) <= 30_000_000L)
    }

    @Test
    fun `CfrPtsClock stays strictly monotonic across live targetFps changes`() {
        val clock = CfrPtsClock()
        val baseNs = 1_000_000_000L

        val pts60_0 = clock.nextPtsNs(60, baseNs)!!
        // Wake up slightly early so pts60_1 is ahead of nowNs
        val pts60_1 = clock.nextPtsNs(60, baseNs + 10_000_000L)!!
        assertEquals(baseNs + 16_666_666L, pts60_1)

        // Immediately switch to 30 fps before wall clock reaches pts60_1
        val pts30_0 = clock.nextPtsNs(30, baseNs + 12_000_000L)!!
        assertTrue(pts30_0 > pts60_1)
        assertTrue(pts30_0 - pts60_1 >= 33_333_333L)
        assertTrue(pts60_1 > pts60_0)
    }
}
