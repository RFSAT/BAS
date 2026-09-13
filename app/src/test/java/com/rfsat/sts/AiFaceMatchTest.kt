package com.rfsat.sts

import com.rfsat.bas.cloud.AiFaceMatch
import com.rfsat.bas.targets.TargetCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Naming the face is the one job in this app a vision model is properly suited
 * to, and this is the piece that turns its words into a catalogue entry — so it
 * is tested against the REAL catalogue rather than a handful of mock faces.
 * Two thresholds in AiFaceMatch were set by this test rather than by taste:
 * the governing body had to outweigh a shared distance, and the winning margin
 * had to be absolute rather than a ratio.
 */
class AiFaceMatchTest {

    private val faces = TargetCatalog.builtIns

    private fun match(s: String) = AiFaceMatch.match(s, faces)?.name

    @Test
    fun `every catalogue face identifies itself from its own name`() {
        // The weakest possible claim, and it caught two real bugs: ISSF 50 m
        // Rifle losing to DSB/BDS 50 m Kleinkaliber, and the air rifle/pistol
        // pair being rejected as too close.
        for (f in faces) {
            assertEquals("${f.name} does not identify itself", f.name, match(f.name))
        }
    }

    @Test
    fun `the range card is recognised however the model phrases it`() {
        val precision = "ISSF 25/50 m Precision Pistol"
        assertEquals(precision, match("ISSF 50 m Pistol"))
        assertEquals(precision, match("50m pistol"))
        assertEquals(precision, match("ISSF 25/50 m Precision Pistol"))
    }

    @Test
    fun `distance separates faces that share every other word`() {
        assertEquals("ISSF 10 m Air Pistol", match("10 m air pistol"))
        assertEquals("ISSF 10 m Air Rifle", match("ISSF 10m Air Rifle target"))
        assertEquals("ISSF 300 m Rifle", match("ISSF 300 m rifle target"))
        assertEquals("DSB/BDS 100 m Rifle", match("DSB 100m rifle"))
    }

    @Test
    fun `yards, feet and hyphenated names are understood`() {
        assertEquals("F-Class MR-FC — 600 yd", match("F-Class MR-FC 600 yards"))
        assertEquals("NRA/CMP SR — 200 yd High Power", match("NRA SR 200 yard high power"))
    }

    @Test
    fun `the B-series faces at one distance are told apart by cadence`() {
        assertEquals("NRA B-8 — 25 yd Timed/Rapid Pistol", match("NRA B-8 25 yd timed fire pistol"))
        assertEquals("NRA B-16 — 25 yd Slow Fire Pistol", match("NRA B-16 25 yd slow fire pistol"))
    }

    @Test
    fun `the practical and steel faces are matched by their rare words`() {
        // These four defeated the weighted-word scheme entirely: their names
        // are short and made of words no hand-picked list anticipates, so all
        // four scored 1.5 and named nothing. They are the reason a word found
        // in one face alone is now weighted like a distance.
        assertEquals("IPSC Classic target", match("IPSC Classic target"))
        assertEquals("IPSC Mini target (1/3 scale)", match("IPSC mini target"))
        assertEquals("IDPA target", match("an IDPA target"))
        assertEquals("300 mm round steel", match("300 mm round steel plate"))
    }

    @Test
    fun `an answer that names no face is declined rather than guessed`() {
        // Declining is a RESULT: the shooter is asked instead of being handed a
        // coin toss, which is what put the wrong face on a card in 1.52.5.
        assertNull(match("unknown"))
        assertNull(match(""))
        assertNull(match("a paper target with concentric rings"))
        assertNull(match("some kind of pistol target"))
        // Ambiguous: ISSF pistol with no distance fits three faces alike.
        assertNull(match("ISSF pistol target"))
    }
}
