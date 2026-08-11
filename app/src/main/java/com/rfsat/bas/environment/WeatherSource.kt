package com.rfsat.bas.environment

import android.annotation.SuppressLint
import android.content.Context
import com.rfsat.bas.log.Logger

/**
 * One entry point for "get me the conditions at the firing point", honouring
 * the tier the shooter chose. Everything lands in EnvironmentManager, so the
 * ballistics solution never has to know which of the three answered.
 */
object WeatherSource {

    private const val TAG = "WeatherSource"

    /** [onDone] is called on the caller's thread with a short description. */
    @SuppressLint("MissingPermission")
    fun refresh(context: Context, onDone: (Boolean, String) -> Unit) {
        when (WeatherConfig.tier(context)) {
            WeatherTier.PHONE -> {
                EnvironmentManager.refreshFromPhoneSensors(context) {
                    onDone(true, EnvironmentManager.describe())
                }
            }
            WeatherTier.METER -> {
                val provider = KestrelProvider
                val bonded = provider.findPairedKestrel()
                if (bonded != null) provider.read(context, bonded) { ok ->
                    onDone(ok, EnvironmentManager.describe())
                } else provider.scanForKestrel(context) { d ->
                    if (d == null) onDone(false, "No meter found — switch it on, or choose another source.")
                    else provider.read(context, d) { ok -> onDone(ok, EnvironmentManager.describe()) }
                }
            }
            WeatherTier.ONLINE -> Thread {
                val lat: Double
                val lon: Double
                if (WeatherConfig.hasPosition(context)) {
                    lat = WeatherConfig.latitude(context); lon = WeatherConfig.longitude(context)
                } else {
                    val fix = lastKnownLocation(context)
                    if (fix == null) {
                        onDone(false, "No position — set coordinates in Settings, or allow location.")
                        return@Thread
                    }
                    lat = fix.first; lon = fix.second
                }
                val c = OnlineWeather.fetch(context, lat, lon)
                if (c == null) { onDone(false, "The weather service returned nothing — see the Log."); return@Thread }
                EnvironmentManager.setFromService(
                    c.temperatureC, c.pressureHpa?.times(100.0), c.humidityPct?.div(100.0), c.source)
                if (c.windMps != null || c.windFromDeg != null)
                    EnvironmentManager.setWind(c.windMps, c.windFromDeg, c.source)
                c.windGustMps?.let { EnvironmentManager.setWindGust(it) }
                Logger.i(TAG, "online conditions from ${c.source}: ${EnvironmentManager.describe()}")
                onDone(true, EnvironmentManager.describe())
            }.start()
        }
    }

    @SuppressLint("MissingPermission")
    private fun lastKnownLocation(context: Context): Pair<Double, Double>? = runCatching {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        val providers = lm.getProviders(true)
        for (p in providers.reversed()) {
            val l = lm.getLastKnownLocation(p) ?: continue
            return@runCatching l.latitude to l.longitude
        }
        null
    }.getOrNull()
}
