package com.rfsat.sts

import com.rfsat.bas.detect.Homography
import org.junit.Assert.assertEquals
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
 * uses. Drawn as plain circles instead, it would show a perfect fit for a
 * registration that is quietly wrong — which is the error it exists to
 * reveal.
 */
class RingOverlayGeometryTest {

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

    /** The same card through a 10% horizontal stretch. */
    private fun stretched(): Homography = Homography.fromCorrespondences(
        listOf(-100.0 to -100.0, 100.0 to -100.0, 100.0 to 100.0, -100.0 to 100.0),
        listOf(280.0 to 200.0, 720.0 to 200.0, 720.0 to 600.0, 280.0 to 600.0)
    )!!

    @Test
    fun `square on, a ring draws as a circle of the right size`() {
        val pts = outline(squareOn(), 50.0)
        assertEquals(96, pts.size)
        for ((x, y) in pts) assertEquals(100.0, hypot(x - 500.0, y - 400.0), 1e-6)
    }

    @Test
    fun `a stretched photograph draws the ring as the ellipse it is`() {
        val pts = outline(stretched(), 50.0)
        val rx = pts.maxOf { abs(it.first - 500.0) }
        val ry = pts.maxOf { abs(it.second - 400.0) }
        assertEquals(110.0, rx, 1e-6)
        assertEquals(100.0, ry, 1e-6)
        assertTrue("the difference must be plain, not marginal", rx / ry > 1.05)
    }

    @Test
    fun `the ladder keeps its order from the centre outward`() {
        val h = squareOn()
        val radii = listOf(10.0, 25.0, 50.0, 80.0)
            .map { r -> outline(h, r).maxOf { hypot(it.first - 500.0, it.second - 400.0) } }
        assertEquals(radii.sorted(), radii)
    }

    @Test
    fun `the centre maps to the centre`() {
        val (x, y) = squareOn().mmToPx(0.0, 0.0)
        assertEquals(500.0, x, 1e-6)
        assertEquals(400.0, y, 1e-6)
    }
}
