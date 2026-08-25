package com.rfsat.sts

import com.rfsat.bas.cloud.AnswerSanity
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE FIRST SIX CASES ARE REAL. They were read out of the device log on
 * 25 August 2026 — three answers Mistral gave about one card, and the
 * answers Claude and Gemini gave about the same card. Nothing here is
 * invented, and nothing here is a simulation: these are the numbers that
 * actually arrived, and the thresholds were fitted to them rather than to a
 * model of what a bad answer might look like.
 *
 * The rejection must stay NARROW. A check that throws away real answers is
 * worse than no check, so the good answers matter more than the bad ones.
 */
class AnswerSanityTest {

    // ------------------------------------------------- real, and fabricated

    /** Mistral, 21 holes: a repeated centre, then two runs marching out. */
    @Test fun mistralTwentyOneHoleAnswerIsRejected() {
        val pts = listOf(
            0.5 to 0.5, 0.5 to 0.5, 0.55 to 0.45, 0.52 to 0.48, 0.58 to 0.42,
            0.6 to 0.4, 0.62 to 0.38, 0.65 to 0.35, 0.68 to 0.32, 0.7 to 0.3,
            0.72 to 0.28, 0.75 to 0.25, 0.78 to 0.22, 0.8 to 0.2, 0.82 to 0.18,
            0.5 to 0.6, 0.52 to 0.62, 0.55 to 0.65, 0.58 to 0.68, 0.6 to 0.7, 0.62 to 0.72
        )
        val f = AnswerSanity.sweep(pts)
        assertNotNull(f); assertTrue(f!!.reject)
        assertTrue("found only ${f.count}", f.count >= 10)
    }

    /** Mistral, 28 holes: four runs radiating from the exact centre. */
    @Test fun mistralTwentyEightHoleAnswerIsRejected() {
        val pts = listOf(
            0.5 to 0.5, 0.5 to 0.5, 0.52 to 0.48, 0.55 to 0.45, 0.58 to 0.42,
            0.6 to 0.4, 0.62 to 0.38, 0.65 to 0.35, 0.68 to 0.32, 0.7 to 0.3,
            0.72 to 0.28, 0.75 to 0.25, 0.45 to 0.55, 0.42 to 0.58, 0.4 to 0.6,
            0.38 to 0.62, 0.35 to 0.65, 0.32 to 0.68, 0.3 to 0.7, 0.28 to 0.72,
            0.55 to 0.55, 0.58 to 0.58, 0.6 to 0.6, 0.62 to 0.62, 0.65 to 0.65,
            0.45 to 0.45, 0.42 to 0.42, 0.4 to 0.4
        )
        assertTrue(AnswerSanity.sweep(pts)!!.reject)
    }

    /**
     * Mistral, 17 holes — THE ONE 1.49.4 LET THROUGH. Its longest run spans
     * about half the frame, not the whole of it, because the runs start at
     * the centre and go outwards. Seventeen invented shots replaced fifteen
     * measured ones in a session before this was caught.
     */
    @Test fun theSeventeenHoleAnswerThatGotThroughIsRejected() {
        val pts = listOf(
            0.5 to 0.5, 0.5 to 0.5, 0.52 to 0.48, 0.55 to 0.45, 0.6 to 0.4,
            0.65 to 0.35, 0.7 to 0.3, 0.75 to 0.25, 0.8 to 0.2, 0.85 to 0.15,
            0.5 to 0.55, 0.45 to 0.6, 0.4 to 0.65, 0.35 to 0.7, 0.3 to 0.75,
            0.25 to 0.8, 0.2 to 0.85
        )
        val f = AnswerSanity.sweep(pts)
        assertNotNull("this is the case the span threshold was lowered for", f)
        assertTrue(f!!.reject)
    }

    // ------------------------------------------------------- real, and good

    /** Claude, on the same card. Fifteen holes, and they were right. */
    @Test fun claudeAnswerIsKept() {
        val pts = listOf(
            0.463 to 0.404, 0.519 to 0.392, 0.526 to 0.432, 0.489 to 0.492,
            0.52 to 0.485, 0.59 to 0.48, 0.617 to 0.484, 0.613 to 0.525,
            0.542 to 0.597, 0.665 to 0.545, 0.658 to 0.617, 0.618 to 0.64,
            0.531 to 0.694, 0.443 to 0.694, 0.515 to 0.735
        )
        assertNull(AnswerSanity.sweep(pts))
    }

    /** Claude again, a day later, on the same card. */
    @Test fun claudeSecondAnswerIsKept() {
        val pts = listOf(
            0.462 to 0.398, 0.516 to 0.388, 0.525 to 0.428, 0.487 to 0.485,
            0.518 to 0.479, 0.588 to 0.472, 0.614 to 0.478, 0.607 to 0.522,
            0.542 to 0.594, 0.663 to 0.539, 0.66 to 0.613, 0.618 to 0.637,
            0.443 to 0.688, 0.532 to 0.686, 0.516 to 0.729
        )
        assertNull(AnswerSanity.sweep(pts))
    }

    /** Gemini, on the same card. Two decimal places, and still correct —
     *  which is why coarse rounding must never be grounds for rejection. */
    @Test fun geminiAnswerIsKept() {
        val pts = listOf(
            0.48 to 0.49, 0.51 to 0.48, 0.52 to 0.43, 0.51 to 0.38, 0.46 to 0.4,
            0.54 to 0.59, 0.58 to 0.47, 0.61 to 0.48, 0.6 to 0.51, 0.66 to 0.54,
            0.65 to 0.6, 0.61 to 0.63, 0.53 to 0.68, 0.52 to 0.73, 0.44 to 0.68
        )
        assertNull(AnswerSanity.sweep(pts))
    }

    // ------------------------------------------- constructed edge cases

    @Test fun aShortRunIsWarnedAboutRatherThanDiscarded() {
        val ramp = (0 until 6).map { i -> (0.15 + i * 0.10) to (0.85 - i * 0.10) }
        val f = AnswerSanity.sweep(ramp)
        assertNotNull(f)
        assertTrue(!f!!.reject && f.message.startsWith("CHECK THIS ANSWER"))
    }

    /** Recoil walking the point of aim: diagonal, but unevenly spaced and
     *  scattered sideways the way real shooting is. */
    @Test fun walkedGroupIsKept() {
        val pts = listOf(
            0.18 to 0.24, 0.27 to 0.31, 0.31 to 0.41, 0.44 to 0.44,
            0.48 to 0.58, 0.61 to 0.60, 0.66 to 0.74
        )
        assertNull(AnswerSanity.sweep(pts))
    }

    /** Straight and evenly spaced, but confined to the middle of the card. */
    @Test fun shortEvenLineIsKept() {
        val pts = (0 until 8).map { i -> (0.44 + i * 0.015) to (0.44 + i * 0.015) }
        assertNull(AnswerSanity.sweep(pts))
    }

    @Test fun fivePointsAreNeverFlagged() {
        val pts = listOf(0.1 to 0.9, 0.3 to 0.7, 0.5 to 0.5, 0.7 to 0.3, 0.9 to 0.1)
        assertNull(AnswerSanity.sweep(pts))
    }

    @Test fun emptyAnswerIsNotASweep() {
        assertNull(AnswerSanity.sweep(emptyList()))
    }

    // ----------------------------------------------------------- the log

    @Test fun describeNamesTheCountAndTheCoordinates() {
        val d = AnswerSanity.describe(listOf(0.25 to 0.75, 0.5 to 0.5))
        assertTrue(d, d.startsWith("2 holes at"))
        assertTrue(d, d.contains("(0.25,0.75)"))
    }

    /** The supporting marks are reported, and are never grounds to reject. */
    @Test fun marksSeparateTheRealAnswersFromTheFabricatedOnes() {
        val fabricated = AnswerSanity.marks(listOf(
            0.5 to 0.5, 0.5 to 0.5, 0.55 to 0.45, 0.6 to 0.4, 0.65 to 0.35))
        assertTrue(fabricated, fabricated.contains("repeated coordinates 1"))
        val real = AnswerSanity.marks(listOf(
            0.463 to 0.404, 0.519 to 0.392, 0.526 to 0.432, 0.489 to 0.492))
        assertTrue(real, real.contains("repeated coordinates 0"))
        assertTrue(real, real.contains("0% of values"))
    }
}
