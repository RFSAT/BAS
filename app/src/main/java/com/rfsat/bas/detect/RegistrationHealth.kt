package com.rfsat.bas.detect

/**
 * Whether a registration can be trusted enough to score, judged from evidence
 * the detectors already produce — chiefly whether the printed rings match the
 * face that is selected.
 *
 * WHY THIS EXISTS AS ITS OWN THING. A wrong face does not fail; it registers
 * the wrong circle and produces a complete, confident, wrong score sheet
 * (see [TargetGeometryCheck]). The pieces to catch it were already computed —
 * the geometry check names the face the picture's proportions match, and the
 * "identify" route ranks the catalogue by the fitted pitch — but the verdict
 * lived only in transient toasts and the Log, so a shooter who looked away for
 * a moment scored on the wrong face with nothing on screen to say so.
 *
 * This turns those signals into ONE persistent line the status panel can show
 * until the face is put right. It is PURE — no Android, no Context — so the
 * wording and the branch for each case are unit-tested without a device,
 * which matters because the alternative is discovering the branch at a range
 * with no signal and no second chance at the card.
 */
object RegistrationHealth {

    enum class Trust { OK, WRONG_FACE, UNCONFIRMED }

    data class Verdict(val state: Trust, val note: String) {
        val looksWrong: Boolean get() = state != Trust.OK
    }

    /**
     * @param selectedFace     the face currently selected.
     * @param geometryWrong    [TargetGeometryCheck] found more printed ring
     *                         structure than the selected face allows.
     * @param proportionMatch  the catalogue face whose proportions the picture
     *                         actually matches, or null when none does.
     */
    fun assess(selectedFace: String, geometryWrong: Boolean, proportionMatch: String?): Verdict {
        if (!geometryWrong) return Verdict(Trust.OK, "")
        return if (!proportionMatch.isNullOrBlank() && proportionMatch != selectedFace)
            Verdict(
                Trust.WRONG_FACE,
                "⚠ The printed rings match “$proportionMatch”, not the selected " +
                    "“$selectedFace”. Every score will be wrong until the face is right — " +
                    "tap “Identify target and register”, or select $proportionMatch."
            )
        else
            Verdict(
                Trust.UNCONFIRMED,
                "⚠ The printed rings do not match the selected “$selectedFace” and no " +
                    "catalogue face fits them. Scores may be wrong — check the selection, or add " +
                    "this target under Targets."
            )
    }

    /** The persistent line for the "identify" route when the rings were fitted
     *  but nothing in the catalogue matched their proportions. */
    fun unmatchedNote(selectedFace: String): String =
        "⚠ The rings were fitted but no catalogue face matches their proportions. Registering " +
            "against “$selectedFace” — confirm it is right, or add this target under Targets."
}
