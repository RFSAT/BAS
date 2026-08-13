package com.rfsat.sts

import com.rfsat.bas.ballistics.Atmosphere
import com.rfsat.bas.ballistics.DriftCorrections
import com.rfsat.bas.profiles.BulletProfile
import com.rfsat.bas.profiles.RifleProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The corrections are checked against magnitudes published in the exterior
 * ballistics literature rather than against themselves. A test that merely
 * re-states the formula passes just as happily when the formula is wrong.
 */
class DriftCorrectionsTest {

    private val sierra175 = BulletProfile(
        name = "175 gr .308", caliberDiameterIn = 0.308, weightGrains = 175.0,
        muzzleVelocityFps = 2600.0, ballisticCoefficientG1 = 0.505, isPellet = false
    )
    private val rifle308 = RifleProfile(twistRateInPerTurn = 11.25, zeroDistanceM = 100.0)
    private val icao = Atmosphere()

    @Test
    fun `the length estimate matches bullets that have been measured`() {
        // Sierra 175 MatchKing, 1.240 in measured.
        val l = DriftCorrections.estimatedBulletLengthIn(175.0, 0.308)
        assertTrue("estimated $l in for a 1.24 in bullet", abs(l - 1.24) < 0.10)
        // 300 gr .338, about 1.79 in.
        val l338 = DriftCorrections.estimatedBulletLengthIn(300.0, 0.338)
        assertTrue("estimated $l338 in for a 1.79 in bullet", abs(l338 - 1.79) < 0.12)
    }

    @Test
    fun `a match load in a standard barrel is comfortably stable`() {
        val sg = DriftCorrections.gyroscopicStability(sierra175, rifle308, icao)
        // Anything from about 1.4 up is stable; a 175 gr in an 11.25 twist is
        // a deliberately ordinary combination and should sit near 1.7-2.2.
        assertTrue("Sg was $sg", sg in 1.4..2.6)
    }

    @Test
    fun `a twist too slow for the bullet reports it as unstable`() {
        val slow = rifle308.copy(twistRateInPerTurn = 16.0)
        val sg = DriftCorrections.gyroscopicStability(sierra175, slow, icao)
        assertTrue("a 16 twist should not stabilise a 175 gr; Sg was $sg", sg < 1.4)
    }

    @Test
    fun `spin drift is a few centimetres at mid range and a quarter metre at 1000`() {
        val sg = DriftCorrections.gyroscopicStability(sierra175, rifle308, icao)
        val at500 = DriftCorrections.spinDriftM(sg, 0.7, true)
        val at1000 = DriftCorrections.spinDriftM(sg, 1.55, true)
        assertTrue("500 m drift was ${at500 * 100} cm", at500 * 100 in 3.0..12.0)
        assertTrue("1000 m drift was ${at1000 * 100} cm", at1000 * 100 in 15.0..35.0)
    }

    @Test
    fun `spin drift follows the twist and vanishes at the muzzle`() {
        val sg = 2.0
        val right = DriftCorrections.spinDriftM(sg, 1.5, true)
        val left = DriftCorrections.spinDriftM(sg, 1.5, false)
        assertTrue("right-hand twist must drift right", right > 0.0)
        assertEquals("left-hand twist must mirror it", -right, left, 1e-12)
        assertEquals(0.0, DriftCorrections.spinDriftM(sg, 0.0, true), 0.0)
    }

    @Test
    fun `horizontal Coriolis is right in the north, left in the south, nil at the equator`() {
        val north = DriftCorrections.coriolisM(50.0, null, 1000.0, 1.5).lateralM
        val south = DriftCorrections.coriolisM(-50.0, null, 1000.0, 1.5).lateralM
        val equator = DriftCorrections.coriolisM(0.0, null, 1000.0, 1.5).lateralM
        assertTrue("northern deflection was ${north * 100} cm", north * 100 in 5.0..12.0)
        assertEquals(-north, south, 1e-12)
        assertEquals(0.0, equator, 1e-12)
    }

    @Test
    fun `the vertical Coriolis reverses between east and west and cancels north-south`() {
        fun v(az: Double) = DriftCorrections.coriolisM(50.0, az, 1000.0, 1.5).verticalM
        assertTrue("shooting east must raise the impact", v(90.0) > 0.0)
        assertEquals("west must mirror east", -v(90.0), v(270.0), 1e-12)
        assertEquals("due north cancels", 0.0, v(0.0), 1e-9)
        assertEquals("due south cancels", 0.0, v(180.0), 1e-9)
    }

    @Test
    fun `an unknown firing direction omits the vertical term rather than guessing`() {
        val c = DriftCorrections.coriolisM(50.0, null, 1000.0, 1.5)
        assertEquals(0.0, c.verticalM, 0.0)
        assertTrue("the horizontal term needs no azimuth", c.lateralM > 0.0)
    }

    @Test
    fun `cant throws the shot toward the tilt, and low`() {
        // 3 mrad of come-up at 600 m, canted 5 degrees right.
        val e = DriftCorrections.cantErrorM(5.0, 0.003, 600.0)
        assertTrue("lateral was ${e.lateralM * 100} cm", e.lateralM * 100 in 10.0..20.0)
        assertTrue("cant must also drop the shot", e.verticalM < 0.0)
        assertTrue("the vertical loss is second order", abs(e.verticalM) < abs(e.lateralM))
    }

    @Test
    fun `cant with nothing dialled costs nothing`() {
        assertEquals(0.0, DriftCorrections.cantErrorM(10.0, 0.0, 600.0).lateralM, 0.0)
        assertEquals(0.0, DriftCorrections.cantErrorM(0.0, 0.01, 600.0).lateralM, 0.0)
    }

    @Test
    fun `none of these corrections matter on a short range`() {
        // 100 m, 0.13 s flight, a level rifle: everything below a millimetre.
        val sg = DriftCorrections.gyroscopicStability(sierra175, rifle308, icao)
        val spin = abs(DriftCorrections.spinDriftM(sg, 0.13, true))
        val cor = abs(DriftCorrections.coriolisM(50.0, 90.0, 100.0, 0.13).lateralM)
        assertTrue("spin drift at 100 m was ${spin * 1000} mm", spin < 0.005)
        assertTrue("Coriolis at 100 m was ${cor * 1000} mm", cor < 0.005)
    }
}
