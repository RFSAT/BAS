package com.rfsat.bas.environment

import android.content.Context
import com.rfsat.bas.log.Logger
import com.rfsat.bas.wind.WindSample
import kotlin.math.sin

/**
 * The meter's wind, as the wind AT THE FIRING POINT.
 *
 * The two measurements answer different questions and are strongest in
 * different places. A Kestrel measures the air actually moving past the
 * shooter — accurately, but only there. The vapour trail measures how the wind
 * ACTS ON THE BULLET all the way to the target, which is what the solution
 * needs, but its near-muzzle samples are the weakest (the trail has barely
 * formed and has drifted for almost no time).
 *
 * So the meter is used as an ANCHOR at zero downrange and the trail supplies
 * the profile beyond it. The anchor is one more sample in the same weighted
 * average, not an override: if the trail disagrees strongly the average still
 * reflects both, and the spread widens — which is the honest outcome when the
 * wind at the firing point is not the wind at 300 m.
 */
object StationWind {

    private const val TAG = "StationWind"

    /**
     * Crosswind component at the firing point, +right, or null when the meter
     * measured no wind.
     *
     * Direction convention: a weather meter reports the direction the wind
     * comes FROM. The air therefore travels towards the opposite bearing, so
     * wind FROM the left (270° relative) pushes the bullet to the right:
     *
     *     crosswind_right = -speed * sin(theta_from_relative)
     */
    fun crosswindMps(context: Context): Double? {
        if (!EnvDeviceConfig.useStationWind(context)) return null
        val r = EnvironmentManager.current
        val speed = r.windSpeedMps ?: return null
        val fromDeg = r.windDirectionDeg ?: return speed  // no bearing: treat as full crosswind
        val lof = EnvDeviceConfig.lineOfFireDeg(context)
        val relative = if (lof < 0) fromDeg else (fromDeg - lof + 360.0) % 360.0
        val cross = -speed * sin(Math.toRadians(relative))
        Logger.i(TAG, "station wind %.1f m/s from %.0f° (relative %.0f°) -> crosswind %+.2f m/s"
            .format(speed, fromDeg, relative, cross))
        return cross
    }

    /** The anchor sample, at zero downrange. Confidence is high but not 1.0:
     *  it is an exact measurement of the wrong place — the firing point, not
     *  the whole flight. */
    fun anchorSample(context: Context): WindSample? {
        val cross = crosswindMps(context) ?: return null
        return WindSample(
            timeS = 0.0,
            downrangeM = 0.0,
            crosswindMps = cross,
            verticalWindMps = 0.0,
            confidence = 0.85
        )
    }
}
