package com.rfsat.sts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the scoring screen hands over to Results.
 *
 * The rule, mirrored here because the real one needs an Activity: advance
 * only when shots were actually found, and only when the shooter has left the
 * setting on.
 */
class AutoResultsTest {

    private fun advances(holesFound: Int, settingOn: Boolean) =
        holesFound > 0 && settingOn

    @Test
    fun `a scored card goes to the plot`() {
        assertTrue(advances(5, true))
    }

    @Test
    fun `a detection that found nothing stays put`() {
        // The screen is explaining WHY nothing was found. Replacing that with
        // an empty plot removes the one thing worth reading.
        assertFalse(advances(0, true))
    }

    @Test
    fun `the setting is respected`() {
        assertFalse(advances(5, false))
    }
}
