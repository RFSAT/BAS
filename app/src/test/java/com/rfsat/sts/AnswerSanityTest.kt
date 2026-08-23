package com.rfsat.sts

import com.rfsat.bas.cloud.AnswerSanity
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    /** What Mistral actually returned: evenly spaced, corner to corner. */
    @Test fun reportedDiagonalIsRejected() {
        val pts = (0 until 10).map { i ->
            val f = 0.05 + i * 0.1
            f to (1.0 - f)                       // bottom left to top right on screen
        }
        assertNotNull(AnswerSanity.sweep(pts))
    }

    @Test fun theOtherDiagonalIsRejectedToo() {
        val pts = (0 until 8).map { i -> (0.08 + i * 0.12) to (0.08 + i * 0.12) }
        assertNotNull(AnswerSanity.sweep(pts))
    }

    /** A ramp with a little noise on it is still a ramp. */
    @Test fun slightlyJitteredRampIsRejected() {
        val pts = (0 until 10).map { i ->
            val f = (i + 0.5) / 10.0
            (f + if (i % 2 == 0) 0.008 else -0.008) to
                (1.0 - f + if (i % 3 == 0) 0.008 else -0.006)
        }
        assertNotNull(AnswerSanity.sweep(pts))
    }

    /** The case that prompted all this: everything inside the 9 and 10 rings. */
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
     *  the uneven spacing and the lateral scatter real shooting has. This is
     *  the case the thresholds were tightened for. */
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
        val pts = (0 until 6).map { i -> (0.45 + i * 0.02) to (0.45 + i * 0.02) }
        assertNull(AnswerSanity.sweep(pts))
    }

    /** Too few to tell. Four points fall on a line often enough by chance. */
    @Test fun fourPointsAreNeverRejected() {
        val pts = listOf(0.1 to 0.9, 0.35 to 0.65, 0.6 to 0.4, 0.85 to 0.15)
        assertNull(AnswerSanity.sweep(pts))
    }

    @Test fun emptyAnswerIsNotASweep() {
        assertNull(AnswerSanity.sweep(emptyList()))
    }

    @Test fun describeNamesTheCountAndTheCoordinates() {
        val d = AnswerSanity.describe(listOf(0.25 to 0.75, 0.5 to 0.5))
        assert(d.startsWith("2 holes at")) { d }
        assert(d.contains("(0.25,0.75)")) { d }
    }
}
