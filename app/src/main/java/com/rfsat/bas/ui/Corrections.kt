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

    /** The same correction as an ANGLE, because clicks are turret-specific and
     *  a shooter checking a solution wants the MOA/MRAD it came from. */
    fun scoringAngles(c: SightCorrection): String {
        if (!c.valid || !c.needsAdjustment) return ""
        return "elev %.2f mrad / %.2f MOA   wind %.2f mrad / %.2f MOA"
            .format(c.elevationMrad, c.elevationMoa, c.windageMrad, c.windageMoa)
    }

    fun ballisticAngles(a: ScopeAdjustment): String {
        if (!a.valid) return ""
        val u = a.scopeUnitLabel
        return "elev %.2f %s   wind %.2f %s"
            .format(a.elevationScopeUnits, u, a.windageScopeUnits, u)
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

    // --- Angle-first presentation, matching the Ballistics results screen:
    // the correction the solution produced is the headline, the turret clicks
    // that deliver it are the caption. Clicks are specific to one turret; the
    // angle is the quantity itself.

    private fun arrowFor(d: String) = arrow(d)

    /** "◀ 0.40 MRAD" — windage as an angle, in the scope's own unit. */
    fun ballisticWindageBig(a: ScopeAdjustment): String =
        if (!a.valid) "—" else "${arrow(a.windageDirection)} %.2f %s".format(
            kotlin.math.abs(a.windageScopeUnits), a.scopeUnitLabel)

    fun ballisticElevationBig(a: ScopeAdjustment): String =
        if (!a.valid) "—" else "${arrow(a.elevationDirection)} %.2f %s".format(
            kotlin.math.abs(a.elevationScopeUnits), a.scopeUnitLabel)

    fun ballisticWindageCaption(a: ScopeAdjustment): String =
        if (!a.valid) "" else "WINDAGE — ${kotlin.math.abs(a.windageClicks)} clk ${a.windageDirection}"

    fun ballisticElevationCaption(a: ScopeAdjustment): String =
        if (!a.valid) "" else "ELEVATION — ${kotlin.math.abs(a.elevationClicks)} clk ${a.elevationDirection}"

    /** The scoring correction as an angle in the scope's own unit, arrows
     *  first, with the clicks kept for the caption. */
    fun scoringBig(c: SightCorrection, useMoa: Boolean): String {
        if (!c.valid) return "—"
        if (!c.needsAdjustment) return "On centre"
        val u = if (useMoa) "MOA" else "MRAD"
        val e = if (c.elevationDirection.isNotEmpty())
            "${arrow(c.elevationDirection)} %.2f".format(
                kotlin.math.abs(if (useMoa) c.elevationMoa else c.elevationMrad)) else ""
        val w = if (c.windageDirection.isNotEmpty())
            "${arrow(c.windageDirection)} %.2f".format(
                kotlin.math.abs(if (useMoa) c.windageMoa else c.windageMrad)) else ""
        return listOf(e, w).filter { it.isNotEmpty() }.joinToString("   ") + "  $u"
    }

    fun scoringCaption(c: SightCorrection): String {
        if (!c.valid || !c.needsAdjustment) return ""
        val parts = mutableListOf<String>()
        if (c.elevationDirection.isNotEmpty()) parts.add("${kotlin.math.abs(c.elevationClicks)} clk ${c.elevationDirection}")
        if (c.windageDirection.isNotEmpty()) parts.add("${kotlin.math.abs(c.windageClicks)} clk ${c.windageDirection}")
        return parts.joinToString("   ")
    }

    // --- Grouping (scoring) split the same way as the ballistic rows, so the
    // glance screen reads identically whichever source is selected.

    fun groupWindageBig(c: SightCorrection, useMoa: Boolean): String {
        if (!c.valid) return "—"
        if (!c.needsAdjustment || c.windageDirection.isEmpty()) return "0.00 " + (if (useMoa) "MOA" else "MRAD")
        return "${arrow(c.windageDirection)} %.2f %s".format(
            kotlin.math.abs(if (useMoa) c.windageMoa else c.windageMrad), if (useMoa) "MOA" else "MRAD")
    }

    fun groupElevationBig(c: SightCorrection, useMoa: Boolean): String {
        if (!c.valid) return "—"
        if (!c.needsAdjustment || c.elevationDirection.isEmpty()) return "0.00 " + (if (useMoa) "MOA" else "MRAD")
        return "${arrow(c.elevationDirection)} %.2f %s".format(
            kotlin.math.abs(if (useMoa) c.elevationMoa else c.elevationMrad), if (useMoa) "MOA" else "MRAD")
    }

    fun groupWindageCaption(c: SightCorrection): String =
        if (!c.valid) "WINDAGE — grouping"
        else if (!c.needsAdjustment) "WINDAGE — on centre"
        else "WINDAGE — ${kotlin.math.abs(c.windageClicks)} clk ${c.windageDirection}"

    fun groupElevationCaption(c: SightCorrection): String =
        if (!c.valid) "ELEVATION — grouping"
        else if (!c.needsAdjustment) "ELEVATION — on centre"
        else "ELEVATION — ${kotlin.math.abs(c.elevationClicks)} clk ${c.elevationDirection}"
}
