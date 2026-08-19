package com.rfsat.sts

import com.rfsat.bas.rules.RuleCatalog
import com.rfsat.bas.targets.FaceLinks
import com.rfsat.bas.targets.TargetCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceLinksTest {

    @Test
    fun `every rule set names a face that exists`() {
        // A rule pointing at a missing face is a dead link in both
        // directions, and the screen can only say so after the shooter has
        // already tapped it.
        val missing = RuleCatalog.builtIns.filter { TargetCatalog.byId(it.targetFaceId) == null }
        assertTrue("rules with no face: ${missing.map { it.name }}", missing.isEmpty())
    }

    @Test
    fun `the link resolves both ways for the same pair`() {
        val rule = RuleCatalog.builtIns.first()
        val face = FaceLinks.faceFor(rule)!!
        assertTrue("the face must list the rule that named it",
            FaceLinks.competitionsFor(face.id).any { it.id == rule.id })
    }

    @Test
    fun `the new bullseye faces are reachable from their competitions`() {
        for (id in listOf(TargetCatalog.NRA_B6_50YD.id, TargetCatalog.NRA_B8_25YD.id,
                          TargetCatalog.NRA_B16_25YD.id, TargetCatalog.NRA_B2_50FT.id)) {
            assertTrue("$id is shot in nothing", FaceLinks.competitionsFor(id).isNotEmpty())
        }
    }

    @Test
    fun `the B-6 and B-8 carry the same ring ladder`() {
        // They are the same target at different distances. If one is edited
        // and the other is not, the pair has silently diverged.
        val b6 = TargetCatalog.NRA_B6_50YD.rings.map { it.value to it.diameterMm }
        val b8 = TargetCatalog.NRA_B8_25YD.rings.map { it.value to it.diameterMm }
        assertEquals(b6, b8)
    }

    @Test
    fun `a face nobody shoots produces an empty summary rather than a stray word`() {
        val unused = TargetCatalog.builtIns.firstOrNull {
            FaceLinks.competitionsFor(it.id).isEmpty()
        }
        if (unused != null) assertEquals("", FaceLinks.summaryFor(unused.id))
    }

    @Test
    fun `the summary counts the rest rather than listing them all`() {
        val busiest = TargetCatalog.builtIns.maxByOrNull {
            FaceLinks.competitionsFor(it.id).size
        }!!
        val n = FaceLinks.competitionsFor(busiest.id).size
        val summary = FaceLinks.summaryFor(busiest.id)
        if (n > 1) assertTrue(summary, summary.endsWith("more"))
    }
}
