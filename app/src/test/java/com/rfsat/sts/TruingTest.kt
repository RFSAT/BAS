package com.rfsat.sts

import com.rfsat.bas.ballistics.Atmosphere
import com.rfsat.bas.ballistics.MuzzleVelocity
import com.rfsat.bas.ballistics.Truing
import com.rfsat.bas.profiles.BulletProfile
import com.rfsat.bas.profiles.RifleProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class TruingTest {

    private val load = BulletProfile(
        name = "175 gr .308", caliberDiameterIn = 0.308, weightGrains = 175.0,
        muzzleVelocityFps = 2600.0, ballisticCoefficientG1 = 0.505,
        isPellet = false, testBarrelIn = 24.0
    )
    private val rifle = RifleProfile(barrelLengthIn = 20.0, zeroDistanceM = 100.0)

    // ------------------------------------------------------ barrel length

    @Test
    fun `a short centrefire barrel loses about 25 fps an inch`() {
        val v = MuzzleVelocity.forBarrel(2600.0, 24.0, 20.0, rimfire = false, pellet = false)
        assertEquals(2500.0, v, 1.0)
    }

    @Test
    fun `a rimfire loses velocity in a LONGER barrel, not gains`() {
        // The whole point of separating the rules: a centrefire rule would
        // add velocity here, getting the sign wrong.
        val long22 = MuzzleVelocity.forBarrel(1070.0, 16.0, 24.0, rimfire = true, pellet = false)
        assertTrue("a 24 in .22 should be slower than a 16 in, got $long22", long22 < 1070.0)
        val short22 = MuzzleVelocity.forBarrel(1070.0, 16.0, 12.0, rimfire = true, pellet = false)
        assertTrue("a 12 in .22 should be slower too, got $short22", short22 < 1070.0)
    }

    @Test
    fun `pellets and unknown lengths are left alone`() {
        assertEquals(570.0, MuzzleVelocity.forBarrel(570.0, 16.0, 24.0, false, pellet = true), 0.0)
        assertEquals(2600.0, MuzzleVelocity.forBarrel(2600.0, 0.0, 20.0, false, false), 0.0)
    }

    @Test
    fun `the profile applies the barrel correction and reports it once`() {
        val fitted = load.adjustedForBarrel(20.0)
        assertEquals(2500.0, fitted.muzzleVelocityFps, 1.0)
        // Same barrel as the test barrel: nothing changes, same object back.
        assertTrue(load.adjustedForBarrel(24.0) === load)
    }

    // ------------------------------------------------------------ truing

    /** Drop this app would predict for a KNOWN profile — used to
     *  manufacture observations whose right answer is known. */
    private fun dropAt(bullet: BulletProfile, distanceM: Double): Double {
        val sightH = 0.05
        val pitch = com.rfsat.bas.ballistics.BallisticsEngine.solveZeroPitch(
            bullet, Atmosphere(), rifle.zeroDistanceM, sightH)
        val traj = com.rfsat.bas.ballistics.BallisticsEngine.simulate(
            bullet, Atmosphere(), pitch, 0.0, distanceM + 1.0)
        val at = traj.last { it.position.x <= distanceM }
        return -(at.position.y - sightH)
    }

    @Test
    fun `truing recovers a muzzle velocity that was deliberately wrong`() {
        val truth = load.copy(muzzleVelocityFps = 2450.0)
        val observations = listOf(200.0, 300.0, 400.0).map {
            Truing.DropObservation(it, dropAt(truth, it))
        }
        // Hand the fitter the WRONG starting velocity and see if the drops
        // lead it back.
        val r = Truing.trueProfile(load, rifle, 0.05, observations)
        assertTrue(r.summary, abs(r.trued.muzzleVelocityFps - 2450.0) < 60.0)
        assertTrue("residual ${r.residualM} m", r.residualM < 0.05)
    }

    @Test
    fun `a near-only fit says the drag curve was left alone`() {
        val r = Truing.trueProfile(load, rifle, 0.05,
            listOf(Truing.DropObservation(200.0, dropAt(load, 200.0))))
        assertTrue(r.warnings.any { it.contains("drag curve is unchanged") })
        assertEquals(1.0, r.trued.dragCalibrationFactor, 1e-9)
    }

    @Test
    fun `a far-only fit refuses to touch velocity and says so`() {
        val r = Truing.trueProfile(load, rifle, 0.05,
            listOf(Truing.DropObservation(800.0, dropAt(load, 800.0))))
        assertEquals("velocity must be untouched", 2600.0, r.trued.muzzleVelocityFps, 1e-9)
        assertTrue(r.warnings.any { it.contains("muzzle velocity was left alone") })
    }

    @Test
    fun `no observations is reported, not fitted`() {
        val r = Truing.trueProfile(load, rifle, 0.05, emptyList())
        assertTrue(!r.converged)
        assertTrue(r.trued === load)
    }

    @Test
    fun `an impossible observation is flagged rather than fitted away`() {
        // A group 3 m low at 200 m cannot be a velocity error.
        val r = Truing.trueProfile(load, rifle, 0.05,
            listOf(Truing.DropObservation(200.0, 3.0)))
        assertTrue(r.warnings.isNotEmpty())
    }
}
