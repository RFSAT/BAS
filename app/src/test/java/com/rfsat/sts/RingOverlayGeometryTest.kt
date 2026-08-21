package com.rfsat.sts

import com.rfsat.bas.detect.Homography
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The ring ladder drawn over a registered photograph.
 *
 * Sampled in millimetres and mapped through the SAME homography the scoring
 * uses. That is the whole value of it: drawn as plain circles it would show a
 * perfect fit for a registration that is quietly wrong, which is precisely
 * the error the shooter is being asked to look for.
 */
class RingOverlayGeometryTest {

    /** The sampling the overlay does, mirrored. */
    private fun outline(h: Homography, radiusMm: Double, steps: Int = 96) =
        (0 until steps).map { i ->
            val a = 2.0 * Math.PI * i / steps
            h.mmToPx(radiusMm * cos(a), radiusMm * sin(a))
        }.filter { !it.first.isNaN() }

    /** Square-on, 2 px per mm, centred at (500, 400). */
    private fun squareOn(): Homography = Homography.fromCorrespondences(
        listOf(-100.0 to -100.0, 100.0 to -100.0, 100.0 to 100.0, -100.0 to 100.0),
        listOf(300.0 to 200.0, 700.0 to 200.0, 700.0 to 600.0, 300.0 to 600.0)
    )!!

    /** The same card, photographed through a 10% horizontal stretch. */
    private fun stretched(): Homography = Homography.fromCorrespondences(
        listOf(-100.0 to -100.0, 100.0 to -100.0, 100.0 to 100.0, -100.0 to 100.0),
        listOf(280.0 to 200.0, 720.0 to 200.0, 720.0 to 600.0, 280.0 to 600.0)
    )!!

    @Test
    fun `square on, a ring maps to a circle of the right size`() {
        val pts = outline(squareOn(), 50.0)
        assertEquals(96, pts.size)
        for ((x, y) in pts) {
            assertEquals("2 px per mm, so a 50 mm radius is 100 px",
                100.0, hypot(x - 500.0, y - 400.0), 1e-6)
        }
    }

    @Test
    fun `a stretched photograph makes the ring visibly an ellipse`() {
        // This is the error the drawing exists to reveal: a 10% aspect error
        // barely moves a corner and puts every ring off the printing.
        val pts = outline(stretched(), 50.0)
        val rx = pts.maxOf { abs(it.first - 500.0) }
        val ry = pts.maxOf { abs(it.second - 400.0) }
        assertEquals("stretched across", 110.0, rx, 1e-6)
        assertEquals("unchanged down", 100.0, ry, 1e-6)
        assertTrue("and the difference must be plain, not marginal", rx / ry > 1.05)
    }

    @Test
    fun `the ladder keeps its order from the centre outward`() {
        val h = squareOn()
        val radii = listOf(10.0, 25.0, 50.0, 80.0).map { r ->
            outline(h, r).maxOf { hypot(it.first - 500.0, it.second - 400.0) }
        }
        assertEquals(radii.sorted(), radii)
    }

    @Test
    fun `a degenerate correspondence is refused rather than drawn wrong`() {
        // Three corners on one line: no valid transform exists, and returning
        // one anyway would draw a confident ladder over nothing.
        val bad = Homography.fromCorrespondences(
            listOf(0.0 to 0.0, 1.0 to 0.0, 2.0 to 0.0, 3.0 to 0.0),
            listOf(0.0 to 0.0, 1.0 to 0.0, 2.0 to 0.0, 3.0 to 0.0)
        )
        assertTrue("a collinear fit must not pretend to work",
            bad == null || outline(bad, 50.0).size < 96)
    }

    @Test
    fun `the centre maps to the centre`() {
        val (x, y) = squareOn().mmToPx(0.0, 0.0)
        assertEquals(500.0, x, 1e-6)
        assertEquals(400.0, y, 1e-6)
        assertNotNull(squareOn())
    }
}
