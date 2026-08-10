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
    /** Locks saved before this version were learned by a decoder that could
     *  latch onto NK's "not measured" sentinel (0x8001 read as 3276.9 m) or
     *  onto a weather field. Any such lock is discarded on first use. */
    private const val LOCK_EPOCH = 2

    fun lockedUuid(c: Context): String? {
        if (p(c).getInt("lock_epoch", 1) < LOCK_EPOCH) { clearLock(c); return null }
        return p(c).getString("lock_uuid", null)
    }
    fun lockedScale(c: Context): Double = p(c).getFloat("lock_scale", 0f).toDouble()
    fun setLock(c: Context, uuid: String, scale: Double) =
        p(c).edit().putString("lock_uuid", uuid).putFloat("lock_scale", scale.toFloat())
            .putInt("lock_epoch", LOCK_EPOCH).apply()
    fun clearLock(c: Context) = p(c).edit().remove("lock_uuid").remove("lock_scale").apply()

    /** Reject nonsense before it reaches the solver. */
    fun plausible(metres: Double): Boolean = metres >= 5.0 && metres <= 4000.0
}
