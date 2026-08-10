package com.rfsat.bas.environment

import android.content.Context

/** Which rangefinder the shooter uses, and the last distance obtained. */
object DistanceConfig {
    private const val PREFS = "bas_distance"
    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun model(c: Context): RangefinderModel = RangefinderModel.fromName(p(c).getString("model", null))
    fun setModel(c: Context, m: RangefinderModel) = p(c).edit().putString("model", m.name).apply()

    /** Last reading in metres, 0 if none. Kept so a dropped link still leaves
     *  the number that was measured rather than an empty field. */
    fun lastMetres(c: Context): Double = p(c).getFloat("last_m", 0f).toDouble()
    fun setLastMetres(c: Context, m: Double) = p(c).edit().putFloat("last_m", m.toFloat()).apply()

    /**
     * Once the shooter confirms a reading, the characteristic and unit scale
     * that produced it are remembered, and only that pairing is trusted
     * afterwards. Without this lock a heuristic will eventually read a
     * temperature or a battery level as a range — the decoder cannot tell them
     * apart from the numbers alone.
     */
    fun lockedUuid(c: Context): String? = p(c).getString("lock_uuid", null)
    fun lockedScale(c: Context): Double = p(c).getFloat("lock_scale", 0f).toDouble()
    fun setLock(c: Context, uuid: String, scale: Double) =
        p(c).edit().putString("lock_uuid", uuid).putFloat("lock_scale", scale.toFloat()).apply()
    fun clearLock(c: Context) = p(c).edit().remove("lock_uuid").remove("lock_scale").apply()

    /** Reject nonsense before it reaches the solver. */
    fun plausible(metres: Double): Boolean = metres >= 5.0 && metres <= 4000.0
}
