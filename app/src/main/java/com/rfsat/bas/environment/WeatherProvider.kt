package com.rfsat.bas.environment

import android.content.Context

/**
 * Where the conditions at the FIRING POINT come from, in the order they should
 * be trusted: what the phone can sense, then a real meter, and only then a
 * forecast — an online service describes the weather over a region, not the
 * air moving past this rifle, so it is a last resort rather than an equal.
 */
enum class WeatherTier(val label: String) {
    AUTO("Automatic — phone, then device, then online"),
    PHONE("Smartphone sensors"),
    METER("External device (Kestrel)"),
    ONLINE("Online service")
}

/** Online services. Open-Meteo needs no key, which is why it is the default. */
enum class OnlineService(
    val label: String,
    val needsKey: Boolean,
    val keyHint: String
) {
    OPEN_METEO("Open-Meteo (no key needed)", false, ""),
    OPEN_WEATHER_MAP("OpenWeatherMap", true, "appid"),
    WINDY("Windy point forecast", true, "Windy API key"),
    GOOGLE_WEATHER("Google Weather", true, "Google Maps Platform key"),
    NETATMO("Netatmo (wind needs an anemometer station)", false, "");

    companion object {
        fun fromName(n: String?): OnlineService = values().firstOrNull { it.name == n } ?: OPEN_METEO
    }
}

/** Selection, keys and the position a forecast is fetched for. */
object WeatherConfig {
    private const val PREFS = "bas_weather"
    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun tier(c: Context): WeatherTier =
        runCatching { WeatherTier.valueOf(p(c).getString("tier", null) ?: "") }
            .getOrDefault(WeatherTier.AUTO)
    fun setTier(c: Context, t: WeatherTier) = p(c).edit().putString("tier", t.name).apply()

    fun service(c: Context): OnlineService = OnlineService.fromName(p(c).getString("service", null))
    fun setService(c: Context, s: OnlineService) = p(c).edit().putString("service", s.name).apply()

    fun key(c: Context, s: OnlineService): String = p(c).getString("key_${s.name}", "") ?: ""
    fun setKey(c: Context, s: OnlineService, v: String) =
        p(c).edit().putString("key_${s.name}", v.trim()).apply()

    /** Position for a forecast. 0,0 means "use the phone's last known location". */
    fun latitude(c: Context): Double = p(c).getFloat("lat", 0f).toDouble()
    fun longitude(c: Context): Double = p(c).getFloat("lon", 0f).toDouble()
    fun setPosition(c: Context, lat: Double, lon: Double) =
        p(c).edit().putFloat("lat", lat.toFloat()).putFloat("lon", lon.toFloat()).apply()
    fun hasPosition(c: Context): Boolean = latitude(c) != 0.0 || longitude(c) != 0.0
}
