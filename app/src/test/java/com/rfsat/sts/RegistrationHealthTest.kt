package com.rfsat.sts

import com.rfsat.bas.detect.RegistrationHealth
import com.rfsat.bas.detect.RegistrationHealth.Trust
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wrong-face verdict is what stands between a shooter and a confident,
 * entirely wrong score sheet, so its branches are pinned here rather than
 * discovered at a range. Pure function, no device needed.
 */
class RegistrationHealthTest {

    @Test
    fun `geometry that agrees with the selection is OK and silent`() {
        val v = RegistrationHealth.assess("ISSF 25/50 m Precision Pistol", geometryWrong = false, proportionMatch = null)
        assertEquals(Trust.OK, v.state)
        assertFalse(v.looksWrong)
        assertEquals("", v.note)
    }

    @Test
    fun `a proportion match different from the selection is WRONG_FACE and names both`() {
        // The range card: ISSF 300 m Rifle selected, rings match NRA/CMP SR.
        val v = RegistrationHealth.assess(
            "ISSF 300 m Rifle", geometryWrong = true, proportionMatch = "NRA/CMP SR — 200 yd High Power"
        )
        assertEquals(Trust.WRONG_FACE, v.state)
        assertTrue(v.looksWrong)
        assertTrue(v.note.contains("NRA/CMP SR — 200 yd High Power"))
        assertTrue(v.note.contains("ISSF 300 m Rifle"))
    }

    @Test
    fun `geometry wrong but nothing matches is UNCONFIRMED, not a false accusation`() {
        val v = RegistrationHealth.assess("ISSF 300 m Rifle", geometryWrong = true, proportionMatch = null)
        assertEquals(Trust.UNCONFIRMED, v.state)
        assertTrue(v.looksWrong)
        assertTrue(v.note.contains("no catalogue face fits"))
    }

    @Test
    fun `a proportion match equal to the selection does not accuse the right face`() {
        val v = RegistrationHealth.assess("NRA/CMP SR — 200 yd High Power", geometryWrong = true,
            proportionMatch = "NRA/CMP SR — 200 yd High Power")
        assertEquals(Trust.UNCONFIRMED, v.state)
    }

    @Test
    fun `the identify-route unmatched note names the fallback face`() {
        assertTrue(RegistrationHealth.unmatchedNote("ISSF 300 m Rifle").contains("ISSF 300 m Rifle"))
    }
}
