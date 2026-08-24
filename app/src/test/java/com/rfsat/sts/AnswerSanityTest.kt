package com.rfsat.sts

import com.rfsat.bas.cloud.AnswerSanity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rejection has to be NARROW. A check that throws away real answers is
 * worse than no check at all, so most of this file is answers it must let
 * through.
 *
 * Every case is written out rather than generated from a seed: a threshold
 * test whose inputs depend on a random generator passes or fails for reasons
 * that have nothing to do with the thresholds.
 */
class AnswerSanityTest {

    // ---------------------------------------------------- must be rejected

    /** A fabricated sweep on its own: evenly spaced, corner to corner. */
    @Test fun pureRampIsRejected() {
        val pts = (0 until 10).map { i ->
            val f = 0.05 + i * 0.1
            f to (1.0 - f)                       // bottom left to top right on screen
        }
        val f = AnswerSanity.sweep(pts)
        assertNotNull(f)
        assertTrue(f!!.reject)
        assertEquals(10, f.count)
    }

    /**
     * THE CASE THAT PROMPTED THE SECOND LOOK. Mistral returned real-looking
     * holes in the black AND a dozen strung across the card. Taken together
     * these are not collinear at all, so a test asking whether the WHOLE
     * answer is a ramp passes them - which is exactly what happened.
     */
    @Test fun rampMixedWithARealGroupIsStillRejected() {
        val ramp = (0 until 12).map { i -> (0.09 + i * 0.067) to (0.87 - i * 0.067) }
        val group = listOf(
            0.47 to 0.44, 0.52 to 0.41, 0.49 to 0.50, 0.55 to 0.47,
            0.44 to 0.52, 0.58 to 0.45, 0.51 to 0.55, 0.46 to 0.39
        )
        val f = AnswerSanity.sweep(ramp + group)
        assertNotNull(f)
        assertTrue(f!!.reject)
        assertTrue("found only ${f.count} of the 12 planted", f.count >= 10)
    }

    /** A ramp with a little noise on it is still a ramp. */
    @Test fun slightlyJitteredRampIsRejected() {
        val pts = (0 until 10).map { i ->
            val f = (i + 0.5) / 10.0
            (f + if (i % 2 == 0) 0.008 else -0.008) to
                (1.0 - f + if (i % 3 == 0) 0.008 else -0.006)
        }
        assertTrue(AnswerSanity.sweep(pts)!!.reject)
    }

    // ------------------------------------------- warned about, but not lost

    /** Six or seven can be a coincidence. Said out loud, and kept. */
    @Test fun aShortRunIsWarnedAboutRatherThanDiscarded() {
        val ramp = (0 until 6).map { i -> (0.12 + i * 0.13) to (0.88 - i * 0.13) }
        val f = AnswerSanity.sweep(ramp)
        assertNotNull(f)
        assertEquals(false, f!!.reject)
        assertTrue(f.message.startsWith("CHECK THIS ANSWER"))
    }

    // -------------------------------------------------- must be left alone

    /** The card in the photograph: a cluster in the black and a string out to
     *  the 5 and 6 rings. A real answer, and a wide one. */
    @Test fun theRealCardIsKept() {
        val pts = listOf(
            0.50 to 0.36, 0.53 to 0.34, 0.54 to 0.40, 0.51 to 0.47, 0.52 to 0.48,
            0.60 to 0.46, 0.62 to 0.45, 0.63 to 0.48, 0.55 to 0.59, 0.70 to 0.53,
            0.69 to 0.61, 0.64 to 0.66, 0.53 to 0.71, 0.41 to 0.72, 0.53 to 0.78
        )
        assertNull(AnswerSanity.sweep(pts))
    }

    /** Everything inside the 9 and 10 rings. */
    @Test fun tightGroupIsKept() {
        val pts = listOf(
            0.492 to 0.505, 0.511 to 0.488, 0.503 to 0.517, 0.487 to 0.494,
            0.518 to 0.509, 0.499 to 0.481, 0.506 to 0.502, 0.481 to 0.512,
            0.514 to 0.497, 0.497 to 0.520
        )
        assertNull(AnswerSanity.sweep(pts))
    }

    /** A bad day, but a real one: a wide scattered group across the card. */
    @Test fun wideScatterIsKept() {
        val pts = listOf(
            0.31 to 0.62, 0.68 to 0.41, 0.52 to 0.77, 0.24 to 0.35,
            0.71 to 0.69, 0.44 to 0.28, 0.59 to 0.55, 0.36 to 0.71,
            0.63 to 0.33, 0.47 to 0.49, 0.28 to 0.53, 0.74 to 0.58
        )
        assertNull(AnswerSanity.sweep(pts))
    }

    /** A group strung out diagonally - recoil walking the point of aim - with
     *  the uneven spacing and the lateral scatter real shooting has. */
    @Test fun walkedGroupIsKept() {
        val pts = listOf(
            0.18 to 0.24, 0.27 to 0.31, 0.31 to 0.41, 0.44 to 0.44,
            0.48 to 0.58, 0.61 to 0.60, 0.66 to 0.74
        )
        assertNull(AnswerSanity.sweep(pts))
    }

    /** Evenly spaced and dead straight, but confined to the middle of the
     *  card: a sighting string, not a fabricated sweep. */
    @Test fun shortEvenLineIsKept() {
        val pts = (0 until 8).map { i -> (0.44 + i * 0.015) to (0.44 + i * 0.015) }
        assertNull(AnswerSanity.sweep(pts))
    }

    /** Too few to say anything about. */
    @Test fun fivePointsAreNeverFlagged() {
        val pts = listOf(0.1 to 0.9, 0.3 to 0.7, 0.5 to 0.5, 0.7 to 0.3, 0.9 to 0.1)
        assertNull(AnswerSanity.sweep(pts))
    }

    @Test fun emptyAnswerIsNotASweep() {
        assertNull(AnswerSanity.sweep(emptyList()))
    }

    @Test fun describeNamesTheCountAndTheCoordinates() {
        val d = AnswerSanity.describe(listOf(0.25 to 0.75, 0.5 to 0.5))
        assertTrue(d, d.startsWith("2 holes at"))
        assertTrue(d, d.contains("(0.25,0.75)"))
    }
}
