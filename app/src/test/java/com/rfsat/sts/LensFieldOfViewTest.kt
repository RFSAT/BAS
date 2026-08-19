package com.rfsat.sts

import com.rfsat.bas.detect.LensDistortion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The field of view a lens correction leaves behind.
 *
 * Undistortion moves content outward, so the output corner takes its colour
 * from somewhere inside the source and everything beyond that is lost. On a
 * target card the first thing lost is the edge of the face — which is the
 * part registration needs most. These tests pin the gain that puts it back.
 */
class LensFieldOfViewTest {

    private val norm = 1000.0

    /** Where the output corner samples from, as a fraction of the frame. */
    private fun cornerSample(k: Double): Double {
        val g = LensDistortion.fovGain(k)
        return LensDistortion.distort(norm * g, norm, k) / norm
    }

    @Test
    fun `no correction leaves the picture alone`() {
        assertEquals(1.0, LensDistortion.fovGain(0.0), 1e-12)
    }

    @Test
    fun `a barrel correction keeps the whole frame`() {
        // The values a wide lens actually produces, all above the -4/27 limit.
        for (k in listOf(-0.02, -0.05, -0.10, -0.14)) {
            assertEquals("k=$k lost part of the frame", 1.0, cornerSample(k), 1e-6)
            assertTrue("k=$k should need to stretch outward", LensDistortion.fovGain(k) > 1.0)
        }
    }

    @Test
    fun `below minus four twentysevenths the corner cannot be reached at all`() {
        // The peak of r(1 + k f^2) on its monotonic branch is two thirds of
        // the fold radius; set that equal to the corner and the limit falls
        // out exactly. Past it the gain recovers as much as the model allows
        // and no more — 99% of the frame at k = -0.15, so this is a statement
        // about the mathematics rather than a practical loss.
        assertTrue("just inside the limit", cornerSample(-0.147) > 0.999)
        assertTrue("past it, short of the corner", cornerSample(-0.20) < 1.0)
        assertTrue("but still far better than no gain at all",
            cornerSample(-0.20) > LensDistortion.distort(1000.0, 1000.0, -0.20) / 1000.0)
    }

    @Test
    fun `without the gain the corner would have been cropped`() {
        // What the old code did: gain fixed at 1.
        assertEquals(0.95, LensDistortion.distort(norm, norm, -0.05) / norm, 1e-9)
        assertEquals(0.90, LensDistortion.distort(norm, norm, -0.10) / norm, 1e-9)
    }

    @Test
    fun `beyond the fold the model is refused rather than fudged`() {
        // Past k = -1/3 the radial mapping folds within the frame: it has
        // stopped describing a lens, so it is not offered.
        assertTrue(!LensDistortion.worthApplying(-0.4))
        assertTrue(LensDistortion.worthApplying(-0.10))
    }

    @Test
    fun `a pincushion correction needs no gain`() {
        // Positive k moves content inward; the corners are already covered.
        assertEquals(1.0, LensDistortion.fovGain(0.08), 1e-12)
    }
}
