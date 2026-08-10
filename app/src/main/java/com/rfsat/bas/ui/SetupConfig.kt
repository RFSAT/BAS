package com.rfsat.bas.ui

import android.content.Context

/** How the shooter intends to use BAS, chosen on first run. */
enum class AppMode(val label: String, val blurb: String) {
    BALLISTICS("Ballistics only",
        "Measure the wind from the shot and get the scope correction. Nothing is scored."),
    SCORING("Scoring only",
        "Score the target and read the group. No wind measurement."),
    BOTH("Both, in sequence",
        "Put the shots on centre, then score the group — BAS offers the next step for you.")
}

/** First-run state and the working mode. Cleared by a full reset, which is
 *  what makes the welcome screen appear again. */
object SetupConfig {
    private const val PREFS = "bas_setup"
    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun welcomeDone(c: Context): Boolean = p(c).getBoolean("welcome_done", false)
    fun setWelcomeDone(c: Context, v: Boolean) = p(c).edit().putBoolean("welcome_done", v).apply()

    fun mode(c: Context): AppMode =
        runCatching { AppMode.valueOf(p(c).getString("mode", null) ?: "") }.getOrDefault(AppMode.BOTH)
    fun setMode(c: Context, m: AppMode) = p(c).edit().putString("mode", m.name).apply()

    /** Whether the shooter said they own a weather meter — used only to put the
     *  Kestrel button in front of them rather than to assume a connection. */
    fun hasWeatherMeter(c: Context): Boolean = p(c).getBoolean("weather", false)
    fun setHasWeatherMeter(c: Context, v: Boolean) = p(c).edit().putBoolean("weather", v).apply()

    fun reset(c: Context) = p(c).edit().clear().apply()
}
