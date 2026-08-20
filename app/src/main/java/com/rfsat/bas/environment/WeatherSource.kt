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
            WeatherTier.AUTO -> {
                // Phone first — it needs nothing and is always here. Then the
                // meter, which is the real measurement. Then a forecast, which
                // describes a region rather than this firing point.
                //
                // THE CHAIN CONTINUES UNTIL NOTHING IS MISSING, not until
                // something answers. It used to stop at the first source that
                // succeeded, which is wrong for the commonest kit there is: a
                // Kestrel DROP measures temperature, pressure and humidity and
                // HAS NO IMPELLER, so it answers perfectly while never
                // supplying wind — and the online step that could have
                // supplied it was skipped precisely because the meter worked.
                //
                // Nothing can be overwritten by going further: the online step
                // runs with force = false, which fills gaps and never replaces
                // a value an instrument measured. So the only cost of asking
                // is the request itself, and that is skipped when there is
                // nothing left to ask for.
                EnvironmentManager.refreshFromPhoneSensors(context) {
                    fromMeter(context) { okMeter, meterMsg ->
                        val gaps = EnvironmentManager.missing()
                        if (gaps.isEmpty()) {
                            Logger.i(TAG, "Automatic: complete without going online")
                            onDone(true, meterMsg)
                        } else {
                            Logger.i(TAG, "Automatic: still missing ${gaps.joinToString(", ")}" +
                                " after phone and meter — asking the online service")
                            fromOnline(context, { okOnline, onlineMsg ->
                                val left = EnvironmentManager.missing()
                                val text = when {
                                    okOnline && left.isEmpty() -> onlineMsg
                                    okOnline -> onlineMsg + "\nStill not measured: " +
                                        left.joinToString(", ") + "."
                                    okMeter -> meterMsg + "\nNot measured: " +
                                        left.joinToString(", ") + "."
                                    else -> EnvironmentManager.describe()
                                }
                                onDone(true, text)
                            }, force = false)
                        }
                    }
                }
            }
            WeatherTier.PHONE -> {
                EnvironmentManager.refreshFromPhoneSensors(context, force = true) {
                    onDone(true, EnvironmentManager.describe())
                }
            }
            WeatherTier.METER -> fromMeter(context, onDone)
            WeatherTier.ONLINE -> fromOnline(context, onDone)
        }
    }

    @SuppressLint("MissingPermission")
    private fun fromMeter(context: Context, onDone: (Boolean, String) -> Unit) {
        val provider = KestrelProvider
        val bonded = provider.findPairedKestrel()
        if (bonded != null) provider.read(context, bonded) { ok ->
            onDone(ok, EnvironmentManager.describe())
        } else provider.scanForKestrel(context) { d ->
            if (d == null) onDone(false, "No weather device found — switch it on, or choose another source.")
            else provider.read(context, d) { ok -> onDone(ok, EnvironmentManager.describe()) }
        }
    }

    private fun fromOnline(context: Context, onDone: (Boolean, String) -> Unit, force: Boolean = true) {
        Thread {
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
                    c.temperatureC, c.pressureHpa?.times(100.0), c.humidityPct?.div(100.0),
                    c.source, force)
                if (c.windMps != null || c.windFromDeg != null)
                    EnvironmentManager.setWind(c.windMps, c.windFromDeg, c.source, force)
                c.windGustMps?.let { EnvironmentManager.setWindGust(it) }

                // The chosen service may not report wind at all. Netatmo only
                // does when the station owner fitted an anemometer, and a
                // shooter who chose it for temperature should not lose wind to
                // that. Open-Meteo needs no key, so it can be asked for the
                // gap without anything to set up.
                if (EnvironmentManager.current.windSpeedMps == null &&
                    WeatherConfig.service(context) != OnlineService.OPEN_METEO) {
                    OnlineWeather.fetchOpenMeteo(lat, lon)?.let { b ->
                        if (b.windMps != null || b.windFromDeg != null) {
                            EnvironmentManager.setWind(b.windMps, b.windFromDeg, b.source, false)
                            b.windGustMps?.let { EnvironmentManager.setWindGust(it) }
                            Logger.i(TAG, "wind filled from ${b.source} because " +
                                "${WeatherConfig.service(context).label} reported none")
                        }
                    }
                }
                Logger.i(TAG, "online conditions from ${c.source}: ${EnvironmentManager.describe()}")
                onDone(true, EnvironmentManager.describe())
        }.start()
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
