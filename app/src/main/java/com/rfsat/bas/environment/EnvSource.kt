package com.rfsat.bas.environment

import android.content.Context

/** Where environmental data comes from. A shooter may own more than one meter,
 *  so the choice is explicit rather than "whatever answers first". */
enum class EnvSource(val label: String, val blurb: String) {
    AUTO("Automatic", "Use whichever meter is found, falling back to the phone."),
    PHONE("Phone sensors", "Pressure from the phone's barometer; temperature and humidity only if the phone has those sensors."),
    KESTREL_5700("Kestrel 5700 Elite (LiNK)", "Includes the Ruger-branded 5700AL-R. Pairs normally in Bluetooth settings."),
    KESTREL_DROP("Kestrel DROP D3", "Advertising-only — it never appears in the paired list, so BAS scans for it.");

    companion object {
        fun fromName(n: String?): EnvSource = values().firstOrNull { it.name == n } ?: AUTO
    }
}

object EnvDeviceConfig {
    private const val PREFS = "bas_env_device"
    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun source(c: Context): EnvSource = EnvSource.fromName(p(c).getString("source", null))
    fun setSource(c: Context, s: EnvSource) = p(c).edit().putString("source", s.name).apply()
}
