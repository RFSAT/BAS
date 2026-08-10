package com.rfsat.bas.environment

/**
 * Rangefinders BAS can take a distance from, and how it reaches them.
 *
 * NONE of these publish a GATT specification, so the strategy is deliberately
 * layered rather than one client per brand:
 *
 *   KESTREL_BRIDGE  the shortcut. Leica, Vortex and SIG BDX-X devices all push
 *                   their range INTO a Kestrel 5700 Elite, and BAS already
 *                   speaks Kestrel — so one integration covers three brands
 *                   without decoding a single rangefinder protocol.
 *   direct models   connect to the rangefinder itself. The transport is
 *                   ordinary BLE notify, so [RangefinderLink]'s generic
 *                   decoder handles them; the name hints only pick the device.
 *   MANUAL          type it. Always available, and the fallback whenever a
 *                   link is absent or a reading is not trusted.
 */
enum class RangefinderModel(
    val label: String,
    val hints: List<String>,
    val viaKestrel: Boolean
) {
    MANUAL("Enter by hand", emptyList(), false),
    KESTREL_BRIDGE("Via Kestrel 5700 Elite (Leica / Vortex / SIG)", listOf("kestrel", "5700", "elite", "drop"), true),
    SIG_KILO("SIG KILO (BDX / BDX-X)", listOf("kilo", "sig", "bdx"), false),
    LEICA("Leica Geovid Pro / CRF .COM", listOf("leica", "geovid", "crf", "rangemaster"), false),
    VORTEX("Vortex Fury HD 5000 AB / Razor HD 4000 GB", listOf("vortex", "fury", "razor", "impact"), false),
    TERRAPIN("Vectronix Terrapin-X", listOf("terrapin", "vectronix", "safran"), false),
    FIRE4000("Tangoinnos FIRE4000", listOf("fire", "tango", "4000"), false),
    GENERIC("Any Bluetooth rangefinder", listOf("range", "lrf", "laser"), false);

    fun matches(name: String?): Boolean {
        val n = name?.lowercase() ?: return false
        return hints.any { n.contains(it) }
    }

    companion object {
        fun fromName(n: String?): RangefinderModel = values().firstOrNull { it.name == n } ?: MANUAL
        /** Models that are an actual BLE link (everything but MANUAL). */
        fun linkable(): List<RangefinderModel> = values().filter { it != MANUAL }
    }
}
