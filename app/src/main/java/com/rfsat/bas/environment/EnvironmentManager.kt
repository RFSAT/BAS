package com.rfsat.bas.environment

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import com.rfsat.bas.ballistics.Atmosphere
import com.rfsat.bas.log.Logger

/**
 * Holds the CURRENT range conditions and acquires them from the phone's
 * environmental sensors (v17.0), replacing the standard-atmosphere
 * assumption at analysis time.
 *
 * Sensor availability is very device-dependent: nearly every modern
 * flagship has a barometer (TYPE_PRESSURE — the S24 does), while ambient
 * temperature and humidity sensors are rare on phones (common on Kestrel
 * meters, hence [KestrelProvider]). Whatever isn't measurable stays at the
 * ICAO default and is labelled so.
 *
 * PRESSURE MAPPING: the barometer reads STATION pressure — the actual
 * pressure where the shooter stands. Feeding it to [Atmosphere] as
 * seaLevelPressurePa with altitudeM = 0 makes the density computation use
 * the measured local pressure directly, which is exactly right: altitude
 * only ever affects ballistics through pressure, and we measured pressure.
 * The altitude shown in the status line is informational only (derived
 * back from the pressure via the standard atmosphere).
 */
object EnvironmentManager {

    private const val TAG = "EnvironmentManager"
    private const val SENSOR_TIMEOUT_MS = 2500L
    private const val PREFS = "vtb_environment"

    private var appContext: Context? = null

    /**
     * v19.9: weather survives app restarts. Values and their sources are
     * written on every update and restored at startup — so a Kestrel
     * reading taken at the range is still the working atmosphere after a
     * phone reboot. The phone-sensor refresh can't clobber it: on devices
     * without temp/RH sensors those fields keep the restored value AND
     * source (the prev-preserving update semantics below).
     */
    fun restore(context: Context) {
        appContext = context.applicationContext
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.contains("pressPa")) return
        runCatching {
            current = Reading(
                Atmosphere(
                    seaLevelPressurePa = p.getFloat("pressPa", 101325f).toDouble(),
                    temperatureC = p.getFloat("tempC", 15f).toDouble(),
                    altitudeM = 0.0,
                    relativeHumidity = p.getFloat("humFrac", 0f).toDouble()
                ),
                temperatureSource = p.getString("tSrc", "default")!!,
                pressureSource = p.getString("pSrc", "default")!!,
                humiditySource = p.getString("hSrc", "default")!!,
                informationalAltitudeM =
                    if (p.contains("altM")) p.getFloat("altM", 0f).toDouble() else null
            )
            runCatching {
            val ws = if (p.contains("windMps")) p.getFloat("windMps", 0f).toDouble() else null
            val wd = if (p.contains("windDeg")) p.getFloat("windDeg", 0f).toDouble() else null
            if (ws != null || wd != null)
                current = current.copy(windSpeedMps = ws, windDirectionDeg = wd,
                    windSource = p.getString("windSrc", "") ?: "")
        }
        val ageH = (System.currentTimeMillis() - p.getLong("time", 0L)) / 3_600_000.0
            Logger.i(TAG, "Environment restored from previous session (age %.1f h): %s".format(ageH, describe()))
        }
    }

    private fun persist() {
        val ctx = appContext ?: return
        val r = current
        val e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("pressPa", r.atmosphere.seaLevelPressurePa.toFloat())
            .putFloat("tempC", r.atmosphere.temperatureC.toFloat())
            .putFloat("humFrac", r.atmosphere.relativeHumidity.toFloat())
            .putString("tSrc", r.temperatureSource)
            .putString("pSrc", r.pressureSource)
            .putString("hSrc", r.humiditySource)
            .putLong("time", System.currentTimeMillis())
        r.informationalAltitudeM?.let { e.putFloat("altM", it.toFloat()) }
        r.windSpeedMps?.let { e.putFloat("windMps", it.toFloat()) }
        r.windDirectionDeg?.let { e.putFloat("windDeg", it.toFloat()) }
        e.putString("windSrc", r.windSource)
        e.apply()
    }

    /** Where each value came from, for the status line and the log. */
    data class Reading(
        val atmosphere: Atmosphere,
        val temperatureSource: String,
        val pressureSource: String,
        val humiditySource: String,
        val informationalAltitudeM: Double?,
        /** Wind from a meter with an impeller (the 5700; the DROP has none).
         *  Null means NOT MEASURED — an impeller that is not turning reports
         *  NK's sentinel, and reporting that as "0 m/s calm" would be a
         *  measurement the meter never made. */
        val windSpeedMps: Double? = null,
        val windDirectionDeg: Double? = null,
        val windSource: String = ""
    )

    @Volatile
    var current: Reading = Reading(
        Atmosphere(), "default", "default", "default", null
    )
        private set

    /** Wind from the meter. Kept separate from setFromKestrel so a weather
     *  read that measured no wind cannot clear a wind reading taken earlier. */
    fun setWind(speedMps: Double?, directionDeg: Double?, source: String = "Kestrel") {
        if (speedMps == null && directionDeg == null) return
        current = current.copy(
            windSpeedMps = speedMps ?: current.windSpeedMps,
            windDirectionDeg = directionDeg ?: current.windDirectionDeg,
            windSource = source
        )
        Logger.i(TAG, "Wind from $source: " +
            (speedMps?.let { "%.1f m/s".format(it) } ?: "—") + " " +
            (directionDeg?.let { "%.0f°".format(it) } ?: ""))
        persist()
    }

    fun setFromKestrel(temperatureC: Double?, pressurePa: Double?, humidityFrac: Double?) {
        val prev = current
        current = Reading(
            Atmosphere(
                seaLevelPressurePa = pressurePa ?: prev.atmosphere.seaLevelPressurePa,
                temperatureC = temperatureC ?: prev.atmosphere.temperatureC,
                altitudeM = 0.0,
                relativeHumidity = humidityFrac ?: prev.atmosphere.relativeHumidity
            ),
            temperatureSource = if (temperatureC != null) "Kestrel" else prev.temperatureSource,
            pressureSource = if (pressurePa != null) "Kestrel" else prev.pressureSource,
            humiditySource = if (humidityFrac != null) "Kestrel" else prev.humiditySource,
            informationalAltitudeM = pressurePa?.let {
                SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, (it / 100.0).toFloat()).toDouble()
            } ?: prev.informationalAltitudeM
        )
        Logger.i(TAG, "Environment from Kestrel: ${describe()}")
        persist()
    }

    /**
     * One-shot read of whatever environmental sensors the phone has.
     * Registers listeners, takes the first value of each, unregisters after
     * all report or [SENSOR_TIMEOUT_MS]. Calls [onDone] on the main thread.
     */
    fun refreshFromPhoneSensors(context: Context, onDone: (Reading) -> Unit = {}) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val pressure = sm.getDefaultSensor(Sensor.TYPE_PRESSURE)
        val temp = sm.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE)
        val humidity = sm.getDefaultSensor(Sensor.TYPE_RELATIVE_HUMIDITY)
        Logger.i(TAG, "Phone sensors: pressure=${pressure != null} ambientTemp=${temp != null} humidity=${humidity != null}")

        if (pressure == null && temp == null && humidity == null) {
            onDone(current)
            return
        }

        var pHpa: Float? = null
        var tC: Float? = null
        var hPct: Float? = null
        val handler = Handler(Looper.getMainLooper())
        var finished = false

        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                when (e.sensor.type) {
                    Sensor.TYPE_PRESSURE -> if (pHpa == null) pHpa = e.values[0]
                    Sensor.TYPE_AMBIENT_TEMPERATURE -> if (tC == null) tC = e.values[0]
                    Sensor.TYPE_RELATIVE_HUMIDITY -> if (hPct == null) hPct = e.values[0]
                }
                val allIn = (pressure == null || pHpa != null) &&
                    (temp == null || tC != null) && (humidity == null || hPct != null)
                if (allIn) finish()
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

            fun finish() {
                if (finished) return
                finished = true
                sm.unregisterListener(this)
                val prev = current
                // A METER OUTRANKS THE PHONE. The phone's barometer is a
                // fallback, not a correction: once a Kestrel has supplied a
                // quantity, a later phone read must not quietly replace it —
                // which is what happened when returning to the Ballistics tab
                // re-read the sensors and overwrote the Kestrel's pressure.
                // Each quantity is kept per-source, so the phone still fills
                // whatever the meter did not measure.
                fun fromMeter(src: String) = src.isNotBlank() && !src.equals("phone", true) &&
                    !src.equals("standard", true) && !src.equals("default", true)
                val keepT = fromMeter(prev.temperatureSource)
                val keepP = fromMeter(prev.pressureSource)
                val keepH = fromMeter(prev.humiditySource)
                if (keepT || keepP || keepH)
                    Logger.i(TAG, "Phone sensors: keeping meter values (" +
                        "temp=${if (keepT) prev.temperatureSource else "phone"}, " +
                        "pressure=${if (keepP) prev.pressureSource else "phone"}, " +
                        "humidity=${if (keepH) prev.humiditySource else "phone"})")
                current = Reading(
                    Atmosphere(
                        seaLevelPressurePa = if (keepP) prev.atmosphere.seaLevelPressurePa
                            else pHpa?.let { it * 100.0 } ?: prev.atmosphere.seaLevelPressurePa,
                        temperatureC = if (keepT) prev.atmosphere.temperatureC
                            else tC?.toDouble() ?: prev.atmosphere.temperatureC,
                        altitudeM = 0.0, // measured station pressure carries the altitude effect
                        relativeHumidity = if (keepH) prev.atmosphere.relativeHumidity
                            else hPct?.let { it / 100.0 } ?: prev.atmosphere.relativeHumidity
                    ),
                    temperatureSource = if (keepT) prev.temperatureSource
                        else if (tC != null) "phone" else prev.temperatureSource,
                    pressureSource = if (keepP) prev.pressureSource
                        else if (pHpa != null) "phone" else prev.pressureSource,
                    humiditySource = if (keepH) prev.humiditySource
                        else if (hPct != null) "phone" else prev.humiditySource,
                    informationalAltitudeM = pHpa?.let {
                        SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, it).toDouble()
                    } ?: prev.informationalAltitudeM
                )
                Logger.i(TAG, "Environment from phone sensors: ${describe()}")
                persist()
                handler.post { onDone(current) }
            }
        }

        pressure?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
        temp?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
        humidity?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
        handler.postDelayed({ listener.finish() }, SENSOR_TIMEOUT_MS)
    }

    fun describe(): String {
        val r = current
        val a = r.atmosphere
        val alt = r.informationalAltitudeM?.let { " ~%.0f m ASL".format(it) } ?: ""
        val wind = when {
            r.windSpeedMps != null -> " · %.1f m/s%s (%s)".format(
                r.windSpeedMps,
                r.windDirectionDeg?.let { " @ %.0f°".format(it) } ?: "",
                r.windSource.ifBlank { "meter" })
            else -> " · wind not measured"
        }
        return "%.1f°C (%s) · %.0f hPa (%s) · %.0f%% RH (%s)%s%s".format(
            a.temperatureC, r.temperatureSource,
            a.seaLevelPressurePa / 100.0, r.pressureSource,
            a.relativeHumidity * 100.0, r.humiditySource, alt, wind
        )
    }
}
