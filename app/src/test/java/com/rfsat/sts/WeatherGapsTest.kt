package com.rfsat.sts

import com.rfsat.bas.environment.EnvironmentManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which quantities the automatic chain still has to go looking for.
 *
 * The bug this guards: the chain used to stop at the first source that
 * answered. A Kestrel DROP answers perfectly and has no impeller, so it could
 * never supply wind — and the online step that would have is skipped exactly
 * because the meter worked.
 *
 * Driven forward in one test rather than across several, because
 * EnvironmentManager is a singleton and its state carries between them.
 */
class WeatherGapsTest {

    @Test
    fun `gaps close as sources answer, and only then is the chain done`() {
        // A meter that measures the air but not the wind — the DROP.
        EnvironmentManager.setFromService(
            tempC = 12.0, pressPa = 100_500.0, humFrac = 0.55,
            source = "Kestrel DROP", force = true)

        val afterMeter = EnvironmentManager.missing()
        assertFalse("temperature came from the meter", afterMeter.contains("temperature"))
        assertFalse("pressure came from the meter", afterMeter.contains("pressure"))
        assertFalse("humidity came from the meter", afterMeter.contains("humidity"))
        assertTrue("wind speed cannot come from a DROP", afterMeter.contains("wind speed"))
        assertTrue("nor its direction", afterMeter.contains("wind direction"))
        assertFalse("so the chain must NOT stop here", EnvironmentManager.isComplete())

        // The online step fills what is left, without force.
        EnvironmentManager.setWind(3.4, 270.0, "Open-Meteo", force = false)

        assertEquals("nothing left to ask for", emptyList<String>(), EnvironmentManager.missing())
        assertTrue(EnvironmentManager.isComplete())
    }

    @Test
    fun `a forecast never displaces an instrument`() {
        EnvironmentManager.setFromService(20.0, 101_000.0, 0.40, "Kestrel 5700", force = true)
        val measured = EnvironmentManager.current.atmosphere.temperatureC

        // Gap-filling pass: same quantities, weaker source, force off.
        EnvironmentManager.setFromService(30.0, 90_000.0, 0.90, "Open-Meteo", force = false)

        assertEquals("the meter's temperature must survive",
            measured, EnvironmentManager.current.atmosphere.temperatureC, 1e-9)
        assertTrue("and still be attributed to it",
            EnvironmentManager.current.temperatureSource.contains("Kestrel"))
    }

    @Test
    fun `source ranking puts instruments above the phone above a forecast`() {
        assertTrue(EnvironmentManager.rankOf("Kestrel 5700") > EnvironmentManager.rankOf("phone"))
        assertTrue(EnvironmentManager.rankOf("phone") > EnvironmentManager.rankOf("Open-Meteo"))
        assertEquals("an unset value must rank lowest", 0, EnvironmentManager.rankOf("default"))
        assertEquals(0, EnvironmentManager.rankOf(""))
    }
}
