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
    val keyHint: String,
    /** Where the key comes from, named per service. The dialog used to carry
     *  one hardcoded sentence about Netatmo, which every keyed service then
     *  showed — so OpenWeatherMap and Windy both explained a service the
     *  shooter had not chosen. */
    val whereFrom: String = ""
) {
    OPEN_METEO("Open-Meteo (no key needed)", false, "",
        "No key. Open-Meteo serves forecasts free for non-commercial use."),
    OPEN_WEATHER_MAP("OpenWeatherMap", true, "appid",
        "From openweathermap.org, under API keys in your account. The free tier is enough " +
        "for the few calls this app makes."),
    WINDY("Windy point forecast", true, "Windy API key",
        "From api.windy.com — the Point Forecast API. Windy issues a separate key for it; a " +
        "Windy website login is not one."),
    GOOGLE_WEATHER("Google Weather", true, "Google Maps Platform key",
        "From console.cloud.google.com, with the Weather API enabled on the project. Billing " +
        "must be set up even inside the free allowance."),
    NETATMO("Netatmo (wind needs an anemometer station)", false, "",
        "No key here. Netatmo goes through the RFSAT proxy, which holds the credentials.");

    companion object {
        fun fromName(n: String?): OnlineService = values().firstOrNull { it.name == n } ?: OPEN_METEO
    }
}

/** Selection, keys and the position a forecast is fetched for. */
object WeatherConfig {
    const val PREFS = "bas_weather"
    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun tier(c: Context): WeatherTier =
        runCatching { WeatherTier.valueOf(p(c).getString("tier", null) ?: "") }
            .getOrDefault(WeatherTier.AUTO)
    fun setTier(c: Context, t: WeatherTier) = p(c).edit().putString("tier", t.name).apply()

    fun service(c: Context): OnlineService = OnlineService.fromName(p(c).getString("service", null))
    fun setService(c: Context, s: OnlineService) = p(c).edit().putString("service", s.name).apply()

    fun key(c: Context, s: OnlineService): String = p(c).getString(keyName(s), "") ?: ""
    fun setKey(c: Context, s: OnlineService, v: String) =
        p(c).edit().putString(keyName(s), v.trim()).apply()

    /** The preference name a service's key is stored under. Public because
     *  the backup needs to separate keys from ordinary settings, and it
     *  should not have to guess the spelling. */
    fun keyName(s: OnlineService): String = KEY_PREFIX + s.name

    const val KEY_PREFIX = "key_"

    /** The store holding all of this, so the backup can name it once. */
    const val STORE = PREFS

    /**
     * Shown as a list, the way the AI services are, so it can be seen at a
     * glance which keys are in place — including for a service that is not
     * the one currently selected, which is exactly when a missing key is a
     * surprise.
     */
    fun maskedKey(c: Context, s: OnlineService): String {
        if (!s.needsKey) return "no key needed"
        val k = key(c, s)
        return when {
            k.isBlank() -> "not set"
            k.length < 12 -> "set"
            else -> k.take(7) + "\u2026" + k.takeLast(4)
        }
    }

    /** Position for a forecast. 0,0 means "use the phone's last known location". */
    fun latitude(c: Context): Double = p(c).getFloat("lat", 0f).toDouble()
    fun longitude(c: Context): Double = p(c).getFloat("lon", 0f).toDouble()
    fun setPosition(c: Context, lat: Double, lon: Double) =
        p(c).edit().putFloat("lat", lat.toFloat()).putFloat("lon", lon.toFloat()).apply()
    fun hasPosition(c: Context): Boolean = latitude(c) != 0.0 || longitude(c) != 0.0
}
