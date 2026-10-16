package com.rfsat.sts

import com.rfsat.bas.capture.AudioShotDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * The selectors in capture/AudioShotDetector decide how many shots a video is
 * thought to contain, so they decide how many holes get scored. They are pure
 * functions of two arrays, which is the whole reason they were separated from
 * the decode: this is testable without a device, and the part that needs a
 * device is reduced to reading PCM.
 *
 * The synthetic tracks here are built from the properties that actually matter
 * at a range — shots of UNEQUAL loudness, an echo behind each one, a rising
 * noise floor, a neighbouring firing point — rather than from clean impulses,
 * because a clean impulse is the one case every threshold gets right.
 */
class AudioOnsetTest {

    private val windowS = 0.005
    private fun w(seconds: Double) = (seconds / windowS).toInt()

    /**
     * A track with [reportsAt] seconds, each a short burst of amplitude
     * [levels], an echo at a quarter the amplitude 60 ms behind, over ambient
     * noise around [ambient].
     */
    private fun track(
        durationS: Double,
        reportsAt: List<Double>,
        levels: List<Double>,
        ambient: Double = 300.0,
        ambientDriftTo: Double = -1.0,
        seed: Int = 7
    ): Pair<DoubleArray, DoubleArray> {
        val n = w(durationS)
        val rnd = Random(seed)
        val peaks = DoubleArray(n)
        val means = DoubleArray(n)
        val to = if (ambientDriftTo < 0) ambient else ambientDriftTo
        for (i in 0 until n) {
            val amb = ambient + (to - ambient) * i / n
            // A window's peak runs above its mean even in quiet air; roughly
            // 3x for broadband noise, which is what keeps this honest about
            // the two arrays being different.
            means[i] = amb * (0.8 + 0.4 * rnd.nextDouble())
            peaks[i] = means[i] * 3.0
        }
        for ((k, t) in reportsAt.withIndex()) {
            val level = levels[k % levels.size]
            // The report itself: ~15 ms of crack.
            for (d in 0..2) {
                val i = w(t) + d
                if (i in peaks.indices) {
                    peaks[i] = maxOf(peaks[i], level * (1.0 - 0.2 * d))
                    means[i] = maxOf(means[i], level * 0.4)
                }
            }
            // The echo, which must NOT be counted as a second shot.
            for (d in 0..1) {
                val i = w(t + 0.060) + d
                if (i in peaks.indices) {
                    peaks[i] = maxOf(peaks[i], level * 0.25)
                    means[i] = maxOf(means[i], level * 0.10)
                }
            }
        }
        return peaks to means
    }

    private fun times(peaks: DoubleArray, means: DoubleArray): List<Double> =
        AudioShotDetector.onsets(peaks, means).map { it * windowS }

    private fun assertNear(expected: List<Double>, actual: List<Double>, tolS: Double = 0.015) {
        assertEquals("count: expected $expected got $actual", expected.size, actual.size)
        for (i in expected.indices) {
            assertTrue(
                "shot ${i + 1}: expected ~${expected[i]} got ${actual[i]}",
                abs(expected[i] - actual[i]) <= tolS
            )
        }
    }

    @Test
    fun `a five shot string of equal loudness is timed`() {
        val (p, m) = track(12.0, listOf(1.0, 3.0, 5.0, 7.0, 9.0), listOf(12000.0))
        assertNear(listOf(1.0, 3.0, 5.0, 7.0, 9.0), times(p, m))
    }

    @Test
    fun `shots of unequal loudness are all timed`() {
        // THE CASE THE OLD RULE GETS WRONG. A bar set at 60% of the global
        // peak drops anything under 7200 here, so the 4000 and 5000 shots
        // vanish — which on the scoring side means two holes unaccounted for.
        val levels = listOf(12000.0, 4000.0, 9000.0, 5000.0, 11000.0)
        val (p, m) = track(12.0, listOf(1.0, 3.0, 5.0, 7.0, 9.0), levels)

        assertNear(listOf(1.0, 3.0, 5.0, 7.0, 9.0), times(p, m))

        // Demonstrated rather than taken on trust: the original selector's
        // bar sits at 60% of the LOUDEST shot — 7200 here — so the 4000 and
        // 5000 shots fall under it. It would find three of the five, and on
        // the scoring side that is two holes nobody accounts for.
        val globalBar = p.max() * 0.60
        val crossings = p.indices.count { p[it] >= globalBar && (it == 0 || p[it - 1] < globalBar) }
        assertEquals("the old rule should see only the loud three", 3, crossings)
        assertTrue("4000 must fall under the global bar", 4000.0 < globalBar)
        assertTrue("5000 must fall under the global bar", 5000.0 < globalBar)
    }

    @Test
    fun `an echo behind each report is not a second shot`() {
        val (p, m) = track(6.0, listOf(1.0, 2.5, 4.0), listOf(12000.0))
        assertNear(listOf(1.0, 2.5, 4.0), times(p, m))
    }

    @Test
    fun `rapid fire cadence is resolved`() {
        // ISSF Rapid Fire: five shots in 4 s, so 800 ms apart — comfortably
        // outside the 250 ms refractory, and the reason it is 250 and not
        // something rounder.
        val at = listOf(1.0, 1.8, 2.6, 3.4, 4.2)
        val (p, m) = track(7.0, at, listOf(10000.0))
        assertNear(at, times(p, m))
    }

    @Test
    fun `two shots inside the refractory gap are reported once`() {
        // A deliberate limit, not a bug: 100 ms apart is faster than any
        // cadence the rules allow, so it is far more likely to be one report
        // and its own reflection. Recorded here so the behaviour is a
        // decision rather than a surprise.
        val (p, m) = track(4.0, listOf(1.0, 1.1), listOf(10000.0))
        assertEquals(1, times(p, m).size)
    }

    @Test
    fun `the first shot is not lost to a settling floor`() {
        // The live detector starts its floor high and cannot trust the first
        // second; offline the median of the whole track seeds it, so a report
        // at 120 ms is found.
        val (p, m) = track(6.0, listOf(0.12, 2.0), listOf(10000.0))
        assertNear(listOf(0.12, 2.0), times(p, m))
    }

    @Test
    fun `a rising noise floor does not invent shots`() {
        // Wind getting up over the string: ambient quadruples, and nothing in
        // it is a transient.
        val (p, m) = track(20.0, listOf(5.0, 12.0), listOf(14000.0),
            ambient = 200.0, ambientDriftTo = 800.0)
        assertNear(listOf(5.0, 12.0), times(p, m))
    }

    @Test
    fun `a track with no transient yields nothing`() {
        val (p, _) = track(10.0, emptyList(), listOf(0.0), ambient = 400.0)
        assertFalse(AudioShotDetector.hasDistinctTransient(p))
    }

    @Test
    fun `a track of pure silence yields nothing`() {
        val p = DoubleArray(2000); val m = DoubleArray(2000)
        assertFalse(AudioShotDetector.hasDistinctTransient(p))
        assertTrue(AudioShotDetector.onsets(p, m).isEmpty())
    }

    @Test
    fun `too short to judge yields nothing`() {
        val p = DoubleArray(5) { 1000.0 }; val m = DoubleArray(5) { 100.0 }
        assertFalse(AudioShotDetector.hasDistinctTransient(p))
        assertTrue(AudioShotDetector.onsets(p, m).isEmpty())
    }

    @Test
    fun `an air rifle string is timed as readily as a centrefire one`() {
        // A pellet report is quieter than a door closing. The point of an
        // adaptive floor is that the ABSOLUTE level never enters into it —
        // the same string at a fortieth of the amplitude must give the same
        // answer.
        val at = listOf(1.0, 3.0, 5.0)
        val (pLoud, mLoud) = track(7.0, at, listOf(16000.0), ambient = 400.0)
        val (pQuiet, mQuiet) = track(7.0, at, listOf(400.0), ambient = 10.0)
        assertNear(at, times(pLoud, mLoud))
        assertNear(at, times(pQuiet, mQuiet))
    }
}
