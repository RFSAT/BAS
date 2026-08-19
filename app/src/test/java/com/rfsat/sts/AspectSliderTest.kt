package com.rfsat.sts

import com.rfsat.bas.detect.AspectCorrection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The slider-to-scale mapping.
 *
 * The one property that matters is that 100% — no stretch at all — is exactly
 * reachable. A mapping that can only get to 99.9% or 100.1% would leave every
 * photograph very slightly resampled for no reason, and the shooter with no
 * way to say "leave it alone" other than the reset button.
 */
class AspectSliderTest {

    private val minPct = 100.0 / AspectCorrection.MAX_STRETCH
    private fun scale(progress: Int) = (minPct * 10.0 + progress) / 1000.0
    private fun slider(scale: Double) = ((scale * 1000.0) - minPct * 10.0).toInt().coerceIn(0, 975)

    @Test
    fun `no stretch is exactly reachable`() {
        val p = slider(1.0)
        assertEquals("100% must round-trip exactly", 1.0, scale(p), 1e-9)
    }

    @Test
    fun `the ends of the range are the limits the correction allows`() {
        assertEquals(minPct / 100.0, scale(0), 1e-9)
        assertTrue("the top must not exceed the allowed stretch",
            scale(975) <= AspectCorrection.MAX_STRETCH + 1e-9)
    }

    @Test
    fun `a step is a tenth of a percent`() {
        assertEquals(0.001, scale(101) - scale(100), 1e-12)
    }

    @Test
    fun `values outside the range are clamped, not wrapped`() {
        assertEquals(0, slider(0.1))
        assertEquals(975, slider(9.0))
    }

    @Test
    fun `a stretch within the noise floor is not worth applying`() {
        // The slider can express changes far smaller than the ring fit can
        // measure; worthApplying is what stops those being acted on.
        assertTrue(!AspectCorrection.worthApplying(1.001, 1.0))
        assertTrue(AspectCorrection.worthApplying(1.08, 1.0))
    }
}
