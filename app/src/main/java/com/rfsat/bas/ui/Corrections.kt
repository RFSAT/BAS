package com.rfsat.bas.ui

import com.rfsat.bas.results.ScopeAdjustment
import com.rfsat.bas.scoring.SightCorrection

/** Formats a correction as compact arrows+clicks for a glance, or as a terse
 *  spoken sentence. Shared by Range mode, the live overlays, and TTS. */
object Corrections {
    private fun arrow(d: String) = when (d) {
        "UP" -> "▲"; "DOWN" -> "▼"; "LEFT" -> "◀"; "RIGHT" -> "▶"; else -> ""
    }
    private fun word(d: String) = when (d) {
        "UP" -> "up"; "DOWN" -> "down"; "LEFT" -> "left"; "RIGHT" -> "right"; else -> ""
    }

    fun scoringGlance(c: SightCorrection): String {
        if (!c.valid) return "—"
        if (!c.needsAdjustment) return "On centre"
        val e = if (c.elevationDirection.isNotEmpty()) "${arrow(c.elevationDirection)} ${c.elevationClicks}" else ""
        val w = if (c.windageDirection.isNotEmpty()) "${arrow(c.windageDirection)} ${c.windageClicks}" else ""
        return listOf(e, w).filter { it.isNotEmpty() }.joinToString("   ").ifEmpty { "On centre" }
    }

    fun scoringSpeech(c: SightCorrection): String {
        if (!c.valid) return ""
        if (!c.needsAdjustment) return "On centre."
        val parts = mutableListOf<String>()
        if (c.elevationDirection.isNotEmpty()) parts.add("${word(c.elevationDirection)} ${c.elevationClicks}")
        if (c.windageDirection.isNotEmpty()) parts.add("${word(c.windageDirection)} ${c.windageClicks}")
        return if (parts.isEmpty()) "On centre." else "Come " + parts.joinToString(", ") + "."
    }

    fun ballisticGlance(a: ScopeAdjustment): String {
        if (!a.valid) return "—"
        val e = if (a.elevationDirection.isNotEmpty()) "${arrow(a.elevationDirection)} ${a.elevationClicks}" else ""
        val w = if (a.windageDirection.isNotEmpty()) "${arrow(a.windageDirection)} ${a.windageClicks}" else ""
        return listOf(e, w).filter { it.isNotEmpty() }.joinToString("   ").ifEmpty { "No hold" }
    }

    fun ballisticSpeech(a: ScopeAdjustment): String {
        if (!a.valid) return ""
        val parts = mutableListOf<String>()
        if (a.elevationDirection.isNotEmpty()) parts.add("${word(a.elevationDirection)} ${a.elevationClicks}")
        if (a.windageDirection.isNotEmpty()) parts.add("${word(a.windageDirection)} ${a.windageClicks}")
        return if (parts.isEmpty()) "" else "Wind: " + parts.joinToString(", ") + "."
    }
}
