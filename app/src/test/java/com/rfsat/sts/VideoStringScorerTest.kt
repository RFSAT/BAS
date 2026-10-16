package com.rfsat.sts

import com.rfsat.bas.detect.DetectedHole
import com.rfsat.bas.detect.LumaFrame
import com.rfsat.bas.detect.TargetRegistration
import com.rfsat.bas.detect.VideoStringScorer
import com.rfsat.bas.targets.TargetCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot
import kotlin.random.Random

/**
 * The string scorer takes its frames through a function, which is what makes
 * this possible: a synthetic card is painted, holes are punched into it one
 * at a time, and the whole per-shot loop is exercised with no codec, no
 * camera and no device.
 *
 * What is being checked is ATTRIBUTION — that shot 3's hole is reported
 * against shot 3 and not against shot 2 or the pile — because that is what
 * the rolling reference exists for and what a cumulative difference against
 * the clean card would get wrong.
 */
class VideoStringScorerTest {

    private val face = TargetCatalog.builtIns.first { it.id == "issf_ap_10m" }
    private val gauge = 4.5

    private val w = 600
    private val h = 600

    /** Where the four card corners sit in this synthetic image. */
    private val corners = listOf(
        50.0 to 50.0, 550.0 to 50.0, 550.0 to 550.0, 50.0 to 550.0
    )

    private val reg: TargetRegistration =
        TargetRegistration.fromCardCorners(face, corners, gauge)
            ?: error("the synthetic card must register")

    /**
     * A photographed card: white paper, printed concentric rings, SENSOR
     * NOISE, and [holes] punched in it, in image pixels.
     *
     * THE NOISE IS NOT DECORATION. HoleDetector.detectByDifference sets its
     * threshold from the median of the NON-ZERO differences, which on a real
     * photograph is the sensor noise floor and is exactly the right scale to
     * measure against. Hand it a pair of noiseless synthetic frames and the
     * only non-zero differences are the hole itself, so the median becomes
     * the hole's own contrast, the threshold goes to roughly 200, and nothing
     * is ever found. That is correct behaviour on an impossible input, and a
     * fixture that leaves the noise out tests nothing.
     *
     * The rings are here for the same kind of reason: they give the card
     * texture, so a frame that SHIFTS actually looks different, and they also
     * check that printing common to both frames cancels instead of being
     * reported as shots.
     */
    private fun card(holes: List<Triple<Double, Double, Double>>, seed: Int = 1): LumaFrame {
        val rnd = Random(seed)
        val d = ByteArray(w * h)
        val cx = 300.0
        val cy = 300.0
        for (y in 0 until h) for (x in 0 until w) {
            val r = hypot(x - cx, y - cy)
            // A ring line every 24 px, two px wide.
            val onRing = (r % 24.0) < 2.0 && r > 10.0
            val base = if (onRing) 70 else 215
            d[y * w + x] = (base + rnd.nextInt(-3, 4)).coerceIn(0, 255).toByte()
        }
        for ((hx, hy, rad) in holes) {
            val x0 = (hx - rad).toInt().coerceAtLeast(0)
            val x1 = (hx + rad).toInt().coerceAtMost(w - 1)
            val y0 = (hy - rad).toInt().coerceAtLeast(0)
            val y1 = (hy + rad).toInt().coerceAtMost(h - 1)
            for (y in y0..y1) for (x in x0..x1) {
                if (hypot(x - hx, y - hy) <= rad) {
                    d[y * w + x] = (15 + rnd.nextInt(-3, 4)).coerceIn(0, 255).toByte()
                }
            }
        }
        return LumaFrame(w, h, d)
    }

    /** Hole positions in image pixels, one per shot, well apart. */
    private val shotPixels = listOf(
        Triple(280.0, 280.0, 6.0),
        Triple(320.0, 300.0, 6.0),
        Triple(300.0, 330.0, 6.0),
        Triple(260.0, 320.0, 6.0),
        Triple(330.0, 260.0, 6.0)
    )

    /**
     * A clip whose frames are the card with the first k holes present, where
     * k is however many reports have gone off by that time.
     */
    private fun clip(reportsAt: List<Double>, settleS: Double = VideoStringScorer.DEFAULT_SETTLE_S):
        (Double) -> LumaFrame? = { t ->
        if (t < 0.0) null else {
            val fired = reportsAt.count { it + settleS / 2 <= t }
            card(shotPixels.take(fired), seed = 100 + fired)
        }
    }

    /** Distance in mm from the nearest found hole to where a punched hole
     *  really is, measured through the scorer's own registration so the
     *  comparison cannot be right for the wrong reason. */
    private fun nearest(holes: List<DetectedHole>, px: Triple<Double, Double, Double>): Double {
        val (u, v) = reg.homography.pxToMm(px.first, px.second)
        return holes.minOfOrNull { hypot(it.xMm - u, it.yMm - v) } ?: Double.MAX_VALUE
    }

    @Test
    fun `each report is credited with its own hole`() {
        val at = listOf(1.0, 2.0, 3.0, 4.0, 5.0)
        val out = VideoStringScorer.score(reg, at, gauge, frameAt = clip(at))

        assertEquals("one find per report", 5, out.shots.size)
        assertTrue("every report should account for exactly one hole: " +
            out.shots.map { it.holes.size }, out.agrees)
        for ((i, s) in out.shots.withIndex()) {
            assertEquals("shot ${i + 1} report time", at[i], s.reportTimeS, 1e-9)
            assertTrue("shot ${i + 1} should find its OWN hole, not an earlier one",
                nearest(s.holes, shotPixels[i]) < 3.0)
        }
    }

    @Test
    fun `a single shot is scored`() {
        val at = listOf(1.0)
        val out = VideoStringScorer.score(reg, at, gauge, frameAt = clip(at))
        assertEquals(1, out.shots.size)
        assertEquals(1, out.holes.size)
        assertTrue(out.agrees)
    }

    @Test
    fun `a shot whose frame shows nothing is carried into the next difference`() {
        // The clip keeps shot 3 invisible until shot 4's frame. Shot 3 must
        // report nothing and shot 4 must then report TWO — the mismatch being
        // visible is the point; a cumulative difference would hide it.
        val at = listOf(1.0, 2.0, 3.0, 4.0)
        val settle = VideoStringScorer.DEFAULT_SETTLE_S
        val frames: (Double) -> LumaFrame? = { t ->
            if (t < 0.0) null else {
                var fired = at.count { it + settle / 2 <= t }
                if (fired == 3) fired = 2          // shot 3 did not show
                card(shotPixels.take(fired), seed = 200 + fired)
            }
        }
        val out = VideoStringScorer.score(reg, at, gauge, frameAt = frames)

        assertEquals(4, out.shots.size)
        assertEquals("shot 3 shows nothing", 0, out.shots[2].holes.size)
        assertEquals("shot 4 then accounts for two", 2, out.shots[3].holes.size)
        assertFalse("the counts must not be claimed to agree", out.agrees)
        assertTrue("the mismatch must be said out loud",
            out.notes.any { it.contains("accounts for 2 holes") })
        assertEquals("but all four holes are still found", 4, out.holes.size)
    }

    @Test
    fun `no reports is refused with a reason rather than scored empty`() {
        val out = VideoStringScorer.score(reg, emptyList(), gauge, frameAt = clip(emptyList()))
        assertTrue(out.shots.isEmpty())
        assertFalse(out.agrees)
        assertTrue(out.notes.any { it.contains("No muzzle report") })
    }

    @Test
    fun `a clip with no frame before the first shot is refused`() {
        val out = VideoStringScorer.score(reg, listOf(0.01), gauge) { t ->
            if (t < 0.0) null else card(emptyList(), seed = 300)
        }
        assertTrue(out.shots.isEmpty())
        assertTrue(out.notes.any { it.contains("no frame before the first shot") })
    }

    @Test
    fun `a clip that ends before the last shot settles says so`() {
        val at = listOf(1.0, 2.0)
        val endS = 2.1
        val out = VideoStringScorer.score(reg, at, gauge) { t ->
            if (t < 0.0 || t > endS) null
            else at.count { it + 0.11 <= t }.let { card(shotPixels.take(it), seed = 400 + it) }
        }
        assertEquals(2, out.shots.size)
        assertTrue("the truncated shot carries a note",
            out.shots[1].note?.contains("clip may end too soon") == true)
    }

    @Test
    fun `a camera that moved is warned about, not silently scored`() {
        // Every pixel shifts by 8 px between shot 1 and shot 2: the drift
        // measure must notice, and the shooter must be told the positions
        // after it are not trustworthy.
        val at = listOf(1.0, 2.0)
        val shifted = { base: LumaFrame, dx: Int ->
            val d = ByteArray(w * h) { 215.toByte() }
            for (y in 0 until h) for (x in 0 until w) {
                val sx = x - dx
                if (sx in 0 until w) d[y * w + x] = base.data[y * w + sx]
            }
            LumaFrame(w, h, d)
        }
        val out = VideoStringScorer.score(reg, at, gauge) { t ->
            when {
                t < 0.0 -> null
                t < 1.1 -> card(emptyList(), seed = 501)
                t < 2.1 -> card(shotPixels.take(1), seed = 502)
                else -> shifted(card(shotPixels.take(2), seed = 503), 8)
            }
        }
        assertTrue("the drift must be reported",
            out.notes.any { it.contains("the camera moved") })
    }

    @Test
    fun `changedFraction is zero for identical frames and one for a size change`() {
        val a = card(emptyList())
        assertEquals(0.0, VideoStringScorer.changedFraction(a, a), 1e-9)
        // And a card with no shot on it must not read as a change either,
        // noise and printed rings notwithstanding.
        assertTrue(VideoStringScorer.changedFraction(a, card(emptyList(), seed = 9)) < 0.01)
        assertEquals(1.0, VideoStringScorer.changedFraction(a, LumaFrame(10, 10, ByteArray(100))), 1e-9)
    }
}
