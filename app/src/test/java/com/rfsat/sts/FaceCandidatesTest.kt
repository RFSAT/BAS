package com.rfsat.sts

import com.rfsat.bas.targets.TargetCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which faces automatic identification is allowed to choose between.
 *
 * RingFinder separates the catalogue by black-radius-over-ring-pitch and then
 * by DISTANCE, because the ratio alone collides — its own comment lists 4.00
 * for ISSF precision pistol against 4.00 for the German 100 m face against
 * 4.01 for the NRA A-23/5. Adding a face at a distance where others already
 * sit therefore asks the ratio to do work it cannot.
 */
class FaceCandidatesTest {

    /** Mirrors TargetRepository.identifiableFaces, which needs a Context. */
    private fun candidates(selectedId: String?) : List<String> {
        val all = TargetCatalog.builtIns
        val out = all.filter { it.identifiable }.toMutableList()
        val sel = selectedId?.let { id -> all.firstOrNull { it.id == id } }
        if (sel != null && out.none { it.id == sel.id }) out.add(sel)
        return out.map { it.id }
    }

    @Test
    fun `the established faces are all still identifiable`() {
        for (id in listOf("issf_ar_10m", "issf_ap_10m", "issf_rifle_50m", "nra_a17_50ft",
                          "nra_a23_50yd", "de_rifle_100m", "fclass_600yd")) {
            assertTrue("$id must stay in the guess", candidates(null).contains(id))
        }
    }

    @Test
    fun `the added faces do not enter the guess uninvited`() {
        val cold = candidates(null)
        for (id in listOf("nra_b2_50ft", "nra_b3_50ft", "nra_b6_50yd", "nra_b8_25yd",
                          "nra_b16_25yd", "ibu_biathlon_prone")) {
            assertFalse("$id must not be guessed at", cold.contains(id))
        }
    }

    @Test
    fun `a face you have chosen IS recognised`() {
        // The point of the whole arrangement: pick a B-8 and the identifier
        // can see it, so the sticky rule holds it and no false "looks like a
        // different face" is raised.
        assertTrue(candidates("nra_b8_25yd").contains("nra_b8_25yd"))
        assertTrue(candidates("ibu_biathlon_prone").contains("ibu_biathlon_prone"))
    }

    @Test
    fun `choosing one adds exactly one`() {
        assertEquals(candidates(null).size + 1, candidates("nra_b8_25yd").size)
        // ...and choosing an already-identifiable face adds nothing.
        assertEquals(candidates(null).size, candidates("issf_ar_10m").size)
    }

    @Test
    fun `every withheld face collides on distance with one that is kept`() {
        val kept = TargetCatalog.builtIns.filter { it.identifiable }
        val withheld = TargetCatalog.builtIns.filter { !it.identifiable }
        assertTrue("nothing withheld without reason", withheld.isNotEmpty())
        for (w in withheld) {
            val rivals = kept.filter {
                it.nominalDistanceM <= 0.0 ||
                    (it.nominalDistanceM >= w.nominalDistanceM * 0.6 &&
                     it.nominalDistanceM <= w.nominalDistanceM * 1.7)
            }
            assertTrue("${w.name} was withheld but nothing shares its distance window",
                rivals.isNotEmpty())
        }
    }
}
