package com.rfsat.bas.ui

import android.content.Context

/** Range-mode preferences: spoken output (default OFF) and keep-awake
 *  (default ON). Cached so views can read them cheaply while shooting. */
object RangeSettings {
    private const val PREFS = "bas_range"
    private var speak = false
    private var keepAwake = true
    private var autoReconnect = false
    private var autoShowResults = false

    fun init(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        speak = p.getBoolean("speak", false)
        keepAwake = p.getBoolean("keep_awake", true)
        autoReconnect = p.getBoolean("auto_reconnect", false)
        autoShowResults = p.getBoolean("auto_show_results", false)
    }
    fun speak(): Boolean = speak
    fun keepAwake(): Boolean = keepAwake
    fun setSpeak(c: Context, v: Boolean) { speak = v; c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("speak", v).apply() }
    fun setKeepAwake(c: Context, v: Boolean) { keepAwake = v; c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("keep_awake", v).apply() }
    fun autoReconnect(): Boolean = autoReconnect
    fun autoShowResults(): Boolean = autoShowResults
    fun setAutoReconnect(c: Context, v: Boolean) { autoReconnect = v; c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("auto_reconnect", v).apply() }
    fun setAutoShowResults(c: Context, v: Boolean) { autoShowResults = v; c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("auto_show_results", v).apply() }
}
