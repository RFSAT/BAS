package com.rfsat.sts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The recency list is plain string handling, so it is testable without a
 * device — which is the half most likely to be wrong.
 */
class StringLabelsTest {

    /** Mirrors StringLabels.remember, which needs a Context and so cannot be
     *  called directly from a plain unit test. */
    private fun remember(existing: List<String>, label: String, keep: Int = 12): List<String> {
        val clean = label.trim()
        if (clean.isEmpty()) return existing
        return (listOf(clean) + existing.filterNot { it.equals(clean, true) }).take(keep)
    }

    @Test
    fun `a label used again rises instead of appearing twice`() {
        var list = listOf("Match", "Sighters")
        list = remember(list, "Sighters")
        assertEquals(listOf("Sighters", "Match"), list)
        assertEquals("no duplicate", 2, list.size)
    }

    @Test
    fun `case does not create a second entry`() {
        val list = remember(listOf("Sighters"), "sighters")
        assertEquals(1, list.size)
        assertEquals("the newest spelling wins", "sighters", list.first())
    }

    @Test
    fun `blank labels are not remembered`() {
        assertEquals(listOf("Match"), remember(listOf("Match"), "   "))
    }

    @Test
    fun `the list is capped and drops the oldest`() {
        var list = emptyList<String>()
        for (i in 1..15) list = remember(list, "String $i", keep = 12)
        assertEquals(12, list.size)
        assertEquals("String 15", list.first())
        assertTrue("the oldest must fall off", list.none { it == "String 1" })
    }
}
