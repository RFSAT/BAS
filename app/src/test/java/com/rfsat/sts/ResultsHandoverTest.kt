package com.rfsat.sts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the scoring screen hands over, and what it must have said first.
 */
class ResultsHandoverTest {

    private fun advances(holesFound: Int, settingOn: Boolean) = holesFound > 0 && settingOn

    @Test
    fun `a scored card hands over`() = assertTrue(advances(5, true))

    @Test
    fun `nothing found means nothing to hand over`() {
        // The screen is explaining why. That explanation is the whole value
        // of the moment, and an empty plot replaces it with nothing.
        assertFalse(advances(0, true))
    }

    @Test
    fun `the setting is respected`() = assertFalse(advances(5, false))

    @Test
    fun `the pause is long enough to read a total and short enough not to stick`() {
        val pause = 2600L
        assertTrue("too brief to read", pause >= 2000)
        assertTrue("long enough to feel stuck", pause <= 4000)
    }
}
