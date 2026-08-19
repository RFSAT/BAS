package com.rfsat.sts

import com.rfsat.bas.environment.OnlineService
import com.rfsat.bas.environment.WeatherConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherKeysTest {

    @Test
    fun `every service explains its own key, not another service's`() {
        // The dialog carried one hardcoded sentence about Netatmo, so
        // OpenWeatherMap and Windy both explained a service the shooter had
        // not chosen.
        for (s in OnlineService.entries) {
            assertTrue("${s.label} has no provenance text", s.whereFrom.isNotBlank())
        }
        assertTrue(OnlineService.OPEN_WEATHER_MAP.whereFrom.contains("openweathermap.org"))
        assertTrue(OnlineService.WINDY.whereFrom.contains("windy.com"))
        assertTrue(OnlineService.GOOGLE_WEATHER.whereFrom.contains("cloud.google.com"))
        assertFalse("only Netatmo may mention Netatmo",
            OnlineService.entries.filter { it != OnlineService.NETATMO }
                .any { it.whereFrom.contains("Netatmo") })
    }

    @Test
    fun `a service that needs no key says so rather than reading as unset`() {
        // "not set" against Open-Meteo would send someone hunting for a key
        // that does not exist.
        assertFalse(OnlineService.OPEN_METEO.needsKey)
        assertFalse(OnlineService.NETATMO.needsKey)
        assertTrue(OnlineService.OPEN_WEATHER_MAP.needsKey)
    }

    @Test
    fun `key preference names are prefixed so a backup can separate them`() {
        for (s in OnlineService.entries) {
            assertTrue(WeatherConfig.keyName(s).startsWith(WeatherConfig.KEY_PREFIX))
            assertTrue(WeatherConfig.keyName(s).endsWith(s.name))
        }
        // Distinct per service, or one key would overwrite another.
        val names = OnlineService.entries.map { WeatherConfig.keyName(it) }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun `the weather store is named once, where the backup can find it`() {
        assertEquals("bas_weather", WeatherConfig.STORE)
    }
}
