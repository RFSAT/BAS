package com.rfsat.bas.environment

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.rfsat.bas.log.Logger
import com.rfsat.bas.results.ShotGeometry

/**
 * Which way the rifle points, and whether it is level.
 *
 * Coriolis and cant were implemented in 1.27.0 and have been computing zero
 * ever since, because nothing supplied them with a heading or a tilt. This is
 * where those come from.
 *
 * WHY THE COMPASS READING IS TRUSTWORTHY HERE, and only here. A phone lying
 * on a mat next to a rifle knows its own heading and nothing about the
 * rifle's. But on the capture and session screens the phone is AIMED AT THE
 * TARGET — that is what it is doing, it is filming it — so its heading is the
 * firing direction to well within the few degrees the vertical Coriolis term
 * needs. So the azimuth is sampled while a camera screen is live and is
 * allowed to go stale: [FRESH_FOR_MS] after the last sample it is discarded
 * rather than reused, because a phone that has since been picked up and
 * pocketed is no longer pointing anywhere useful.
 *
 * CANT IS DIFFERENT and is NOT taken from the phone by default. The phone's
 * roll is the phone's, and only a phone clamped to the rail shares it with
 * the barrel. That is a physical fact about the mount, not something the app
 * can detect, so it is a setting the shooter states — off by default, because
 * a wrong cant is worse than no cant: it moves the correction sideways with
 * complete confidence.
 */
object ShotOrientation : SensorEventListener {

    /** A heading older than this is thrown away rather than used. */
    private const val FRESH_FOR_MS = 5 * 60_000L

    private const val PREFS = "bas_orientation"
    private const val KEY_RAIL_MOUNTED = "rail_mounted"
    private const val KEY_MANUAL_CANT = "manual_cant_deg"

    private var manager: SensorManager? = null
    private var lastAzimuthDeg: Double? = null
    private var lastRollDeg: Double? = null
    private var lastSampleAtMs = 0L

    private val rotation = FloatArray(9)
    private val orientation = FloatArray(3)

    fun start(context: Context) {
        if (manager != null) return
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: return
        manager = sm
        sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
    }

    fun stop() {
        manager?.unregisterListener(this)
        manager = null
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(event: SensorEvent?) {
        val e = event ?: return
        if (e.sensor?.type != Sensor.TYPE_ROTATION_VECTOR) return
        runCatching {
            SensorManager.getRotationMatrixFromVector(rotation, e.values)
            SensorManager.getOrientation(rotation, orientation)
            // orientation[0] is azimuth from magnetic north, radians, and
            // negative to the west — normalised here to a 0..360 compass
            // bearing so no consumer has to think about it.
            val az = (Math.toDegrees(orientation[0].toDouble()) + 360.0) % 360.0
            // orientation[2] is roll. Held upright and aimed, this is the
            // tilt of the phone about the viewing axis.
            val roll = Math.toDegrees(orientation[2].toDouble())
            lastAzimuthDeg = az
            lastRollDeg = roll
            lastSampleAtMs = System.currentTimeMillis()
        }
    }

    /** True when the shooter has said the phone is clamped to the rifle, so
     *  its roll is the rifle's cant. */
    fun railMounted(c: Context): Boolean =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_RAIL_MOUNTED, false)

    fun setRailMounted(c: Context, on: Boolean) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_RAIL_MOUNTED, on).apply()
    }

    /** Cant the shooter has entered by hand, degrees, top of the rifle to
     *  the right positive. Used when the phone is not on the rail. */
    fun manualCantDeg(c: Context): Double =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat(KEY_MANUAL_CANT, 0f).toDouble()

    fun setManualCantDeg(c: Context, deg: Double) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_MANUAL_CANT, deg.toFloat()).apply()
    }

    private fun freshAzimuth(): Double? {
        val az = lastAzimuthDeg ?: return null
        return if (System.currentTimeMillis() - lastSampleAtMs <= FRESH_FOR_MS) az else null
    }

    /**
     * Everything the solver needs that is not in a profile. Each field is
     * null or zero when genuinely unknown — the solver leaves the matching
     * term out rather than assuming a value, and says so past 600 m.
     */
    fun geometry(context: Context): ShotGeometry {
        val lat = com.rfsat.bas.environment.WeatherConfig.latitude(context)
        val cant = if (railMounted(context)) (lastRollDeg ?: 0.0) else manualCantDeg(context)
        val g = ShotGeometry(
            latitudeDeg = if (com.rfsat.bas.environment.WeatherConfig.hasPosition(context)) lat else null,
            firingAzimuthDeg = freshAzimuth(),
            cantDeg = cant
        )
        Logger.i("ShotOrientation",
            "geometry lat=${g.latitudeDeg} az=${g.firingAzimuthDeg} cant=${g.cantDeg}")
        return g
    }
}
