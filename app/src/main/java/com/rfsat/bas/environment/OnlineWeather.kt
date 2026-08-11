package com.rfsat.bas.environment

import android.content.Context
import com.rfsat.bas.log.Logger
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Conditions from an online service, for when there is no meter and the phone
 * cannot sense what is needed. Every provider is reduced to the same five
 * quantities so nothing downstream knows or cares which answered.
 *
 * A forecast describes a region, not the air at this firing point — so a
 * reading from here is marked with the service's own name, and the status line
 * says so. It is the last resort, not an equal of the meter.
 */
object OnlineWeather {

    private const val TAG = "OnlineWeather"

    /** RFSAT's Netatmo proxy: it holds the OAuth refresh token server-side, so
     *  no client secret ships in the app. */
    private const val NETATMO_PROXY =
        "https://rfsat.com/projects/HORIZON-ENACT/proxy/netatmo_token.php"

    data class Conditions(
        val temperatureC: Double?,
        val pressureHpa: Double?,
        val humidityPct: Double?,
        val windMps: Double?,
        val windGustMps: Double?,
        val windFromDeg: Double?,
        val source: String
    )

    /** Blocking; call from a worker thread. */
    fun fetch(context: Context, lat: Double, lon: Double): Conditions? {
        val svc = WeatherConfig.service(context)
        val key = WeatherConfig.key(context, svc)
        if (svc.needsKey && key.isBlank()) {
            Logger.w(TAG, "${svc.label}: no API key set")
            return null
        }
        return runCatching {
            when (svc) {
                OnlineService.OPEN_METEO -> openMeteo(lat, lon)
                OnlineService.OPEN_WEATHER_MAP -> owm(lat, lon, key)
                OnlineService.WINDY -> windy(lat, lon, key)
                OnlineService.GOOGLE_WEATHER -> google(lat, lon, key)
                OnlineService.NETATMO -> netatmo(lat, lon, key)
            }
        }.onFailure { Logger.e(TAG, "${svc.label} failed", it) }.getOrNull()
    }

    // ---- providers -------------------------------------------------------

    private fun openMeteo(lat: Double, lon: Double): Conditions? {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,relative_humidity_2m,surface_pressure," +
            "wind_speed_10m,wind_direction_10m,wind_gusts_10m&wind_speed_unit=ms"
        val cur = getJson(url)?.optJSONObject("current") ?: return null
        return Conditions(
            cur.optDouble("temperature_2m").nz(),
            cur.optDouble("surface_pressure").nz(),
            cur.optDouble("relative_humidity_2m").nz(),
            cur.optDouble("wind_speed_10m").nz(),
            cur.optDouble("wind_gusts_10m").nz(),
            cur.optDouble("wind_direction_10m").nz(),
            "Open-Meteo")
    }

    private fun owm(lat: Double, lon: Double, key: String): Conditions? {
        val j = getJson("https://api.openweathermap.org/data/2.5/weather" +
            "?lat=$lat&lon=$lon&units=metric&appid=$key") ?: return null
        val main = j.optJSONObject("main")
        val wind = j.optJSONObject("wind")
        return Conditions(
            main?.optDouble("temp").nz(),
            main?.optDouble("pressure").nz(),
            main?.optDouble("humidity").nz(),
            wind?.optDouble("speed").nz(),
            wind?.optDouble("gust").nz(),
            wind?.optDouble("deg").nz(),
            "OpenWeatherMap")
    }

    /** Windy's point forecast is a POST returning parallel arrays; the first
     *  entry is the nearest step to now. Wind arrives as U/V components. */
    private fun windy(lat: Double, lon: Double, key: String): Conditions? {
        val body = JSONObject()
            .put("lat", lat).put("lon", lon)
            .put("model", "gfs")
            .put("parameters", org.json.JSONArray(listOf("temp", "pressure", "rh", "wind")))
            .put("levels", org.json.JSONArray(listOf("surface")))
            .put("key", key).toString()
        val j = postJson("https://api.windy.com/api/point-forecast/v2", body) ?: return null
        fun first(name: String): Double? =
            j.optJSONArray(name)?.let { if (it.length() > 0) it.optDouble(0) else null }.nz()
        val u = first("wind_u-surface"); val v = first("wind_v-surface")
        val speed = if (u != null && v != null) Math.hypot(u, v) else null
        val dir = if (u != null && v != null)
            (Math.toDegrees(Math.atan2(-u, -v)) + 360.0) % 360.0 else null
        val tK = first("temp-surface")
        return Conditions(
            tK?.let { it - 273.15 },
            first("pressure-surface")?.let { it / 100.0 },   // Pa -> hPa
            first("rh-surface"),
            speed, null, dir, "Windy")
    }

    private fun google(lat: Double, lon: Double, key: String): Conditions? {
        val j = getJson("https://weather.googleapis.com/v1/currentConditions:lookup" +
            "?key=$key&location.latitude=$lat&location.longitude=$lon") ?: return null
        val wind = j.optJSONObject("wind")
        return Conditions(
            j.optJSONObject("temperature")?.optDouble("degrees").nz(),
            j.optJSONObject("airPressure")?.optDouble("meanSeaLevelMillibars").nz(),
            j.optDouble("relativeHumidity").nz(),
            wind?.optJSONObject("speed")?.optDouble("value").nz()?.let { it / 3.6 }, // km/h -> m/s
            wind?.optJSONObject("gust")?.optDouble("value").nz()?.let { it / 3.6 },
            wind?.optJSONObject("direction")?.optDouble("degrees").nz(),
            "Google Weather")
    }

    /**
     * Netatmo public stations near the point, through the RFSAT proxy that
     * already holds the OAuth refresh token — the same contract the ENACT web
     * app uses. That keeps client credentials out of a published APK, which is
     * the whole reason the proxy exists.
     *
     * Netatmo reports wind in km/h; measures arrive either as named wind
     * fields or as res/type arrays for the indoor modules.
     */
    private fun netatmo(lat: Double, lon: Double, unusedKey: String): Conditions? {
        val d = 0.05
        val form = "action=getpublicdata" +
            "&lat_ne=${lat + d}&lon_ne=${lon + d}&lat_sw=${lat - d}&lon_sw=${lon - d}" +
            "&required_data=&filter=true"
        val j = postForm(NETATMO_PROXY, form) ?: return null
        if (j.optString("error").isNotEmpty()) {
            Logger.w(TAG, "Netatmo: ${j.optString("error")} (the proxy may need its one-time authorisation)")
            return null
        }
        val devices = j.optJSONArray("body") ?: return null
        var t: Double? = null; var h: Double? = null; var pr: Double? = null
        var ws: Double? = null; var wg: Double? = null; var wd: Double? = null
        for (i in 0 until devices.length()) {
            val meas = devices.optJSONObject(i)?.optJSONObject("measures") ?: continue
            val keys = meas.keys()
            while (keys.hasNext()) {
                val m = meas.optJSONObject(keys.next()) ?: continue
                if (m.has("wind_strength")) {
                    ws = ws ?: m.optDouble("wind_strength").nz()?.div(3.6)   // km/h -> m/s
                    wg = wg ?: m.optDouble("gust_strength").nz()?.div(3.6)
                    wd = wd ?: m.optDouble("wind_angle").nz()
                }
                val types = m.optJSONArray("type")
                val res = m.optJSONObject("res")
                if (types != null && res != null) {
                    val stamps = res.keys()
                    if (stamps.hasNext()) {
                        val arr = res.optJSONArray(stamps.next())
                        for (k in 0 until types.length()) {
                            val v = arr?.optDouble(k).nz() ?: continue
                            when (types.optString(k)) {
                                "temperature" -> t = t ?: v
                                "humidity" -> h = h ?: v
                                "pressure" -> pr = pr ?: v
                            }
                        }
                    }
                }
            }
        }
        if (t == null && ws == null && pr == null) return null
        return Conditions(t, pr, h, ws, wg, wd, "Netatmo")
    }

    // ---- plumbing --------------------------------------------------------

    private fun Double?.nz(): Double? =
        if (this == null || this.isNaN() || this == 0.0 && false) null else this

    private fun getJson(url: String, headers: Map<String, String> = emptyMap()): JSONObject? {
        Logger.i(TAG, "GET ${url.substringBefore("?")}")
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000; readTimeout = 8000; requestMethod = "GET"
            setRequestProperty("User-Agent", "BAS")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        val code = c.responseCode
        if (code !in 200..299) { Logger.w(TAG, "  HTTP $code"); c.disconnect(); return null }
        val text = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        c.disconnect()
        return JSONObject(text)
    }

    private fun postForm(url: String, form: String): JSONObject? {
        Logger.i(TAG, "POST ${url.substringAfterLast('/')} ($form)")
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000; readTimeout = 8000; requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("User-Agent", "BAS")
        }
        c.outputStream.use { it.write(form.toByteArray()) }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)
            ?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return null
        c.disconnect()
        return runCatching { JSONObject(text) }.getOrNull()
    }

    private fun postJson(url: String, body: String): JSONObject? {
        Logger.i(TAG, "POST ${url.substringBefore("?")}")
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000; readTimeout = 8000; requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", "BAS")
        }
        c.outputStream.use { it.write(body.toByteArray()) }
        val code = c.responseCode
        if (code !in 200..299) { Logger.w(TAG, "  HTTP $code"); c.disconnect(); return null }
        val text = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        c.disconnect()
        return JSONObject(text)
    }
}
