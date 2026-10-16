package com.rfsat.bas.detect

import com.rfsat.bas.log.Logger

/**
 * Scores a STRING of shots from a video, one shot at a time, using the
 * muzzle reports in the clip's own audio to say when to look.
 *
 * WHY THIS IS NOT THE LIVE DETECTOR. [LiveHitDetector] watches every frame
 * and has to infer, from the pictures alone, that something changed because a
 * bullet arrived and not because the light did or the paper flapped — which
 * is why it needs three consecutive frames, positional stability and a
 * global-change veto before it will believe anything. A recorded clip has
 * something the live path does not: its own soundtrack. The report says
 * exactly WHEN a hole appeared, so there is nothing left to infer, and the
 * work collapses to one difference per shot between two full-resolution
 * frames — the same comparison the photograph path has always made, and the
 * most accurate one this app has.
 *
 * THE ROLLING REFERENCE IS THE WHOLE TRICK. Each shot is differenced against
 * the frame AFTER the previous shot, never against the original clean card.
 * Differencing against the clean card would give the holes accumulated so
 * far and leave the app to work out which of them is new; differencing
 * against the previous settled frame gives exactly the hole just made. It is
 * also what makes the count meaningful: one report should produce one hole,
 * and when it does not, that is reportable rather than invisible.
 *
 * WHAT IT DOES NOT DO. It does not register — the caller registers once, from
 * the rings or from four tapped corners, as always. It never moves a
 * registration between shots either; if the camera was hand-held and the view
 * drifted, that shows up as a drift NOTE and the shooter decides, because a
 * registration quietly re-fitted mid-string would rescale a score without
 * anyone being told.
 *
 * It is pure: frames arrive through [frameAt], so every decision here is
 * testable without a device, a codec or a camera.
 */
object VideoStringScorer {

    private const val TAG = "VideoStringScorer"

    /**
     * How far BEFORE the report to take the reference frame. One frame at
     * 30 fps is 33 ms; 80 ms is comfortably clear of the report without
     * straying so far back that the shooter has moved.
     */
    const val DEFAULT_LEAD_S = 0.080

    /**
     * How long AFTER the report to wait for the picture to be worth reading.
     * The bullet arrives in milliseconds but the paper does not hold still:
     * it is struck, it flaps, and a frame caught mid-flap is both blurred and
     * displaced. This is the one number here that genuinely wants measuring
     * against real footage rather than reasoning — see the release notes.
     */
    const val DEFAULT_SETTLE_S = 0.220

    /** A frame this much changed overall is not a new hole; something moved. */
    private const val DRIFT_FRACTION = 0.10

    data class ShotFind(
        val index: Int,
        val reportTimeS: Double,
        val holes: List<DetectedHole>,
        /** Set when this shot's frames could not be read or compared. */
        val note: String? = null
    )

    data class Outcome(
        val shots: List<ShotFind>,
        val notes: List<String>
    ) {
        val holes: List<DetectedHole> get() = shots.flatMap { it.holes }
        val reportCount: Int get() = shots.size

        /**
         * True when every report produced exactly one hole. This is the one
         * line worth putting in front of the shooter: the audio and the
         * pictures are two independent counts of the same event, and they
         * agreeing is far stronger evidence than either alone.
         */
        val agrees: Boolean get() = shots.isNotEmpty() && shots.all { it.holes.size == 1 }
    }

    /**
     * @param reportTimesS muzzle-report times in seconds from clip start,
     *   ascending, from capture/AudioShotDetector.detectShotTimesS.
     * @param frameAt returns the rectified-ready frame nearest a time, or
     *   null when the clip has nothing there.
     */
    fun score(
        reg: TargetRegistration,
        reportTimesS: List<Double>,
        gaugeDiameterMm: Double,
        leadS: Double = DEFAULT_LEAD_S,
        settleS: Double = DEFAULT_SETTLE_S,
        frameAt: (Double) -> LumaFrame?
    ): Outcome {
        if (reportTimesS.isEmpty()) {
            return Outcome(emptyList(), listOf(
                "No muzzle report was found in the clip's audio, so there is nothing to " +
                    "time the shots by. Score a photograph of the card instead."
            ))
        }

        val notes = ArrayList<String>()
        val shots = ArrayList<ShotFind>()

        // The reference starts before the FIRST report, which is the clean
        // card: the one frame in the clip guaranteed to carry no shot from
        // this string.
        var reference = frameAt(reportTimesS.first() - leadS)?.let { reg.rectify(it) }
        if (reference == null) {
            return Outcome(emptyList(), listOf(
                "The clip has no frame before the first shot, so there is no clean card to " +
                    "compare against. Start the recording before the first shot."
            ))
        }

        for ((i, t) in reportTimesS.withIndex()) {
            val raw = frameAt(t + settleS)
            if (raw == null) {
                shots += ShotFind(i + 1, t, emptyList(),
                    "no frame %.2f s after the report — the clip may end too soon".format(settleS))
                continue
            }
            val after = reg.rectify(raw)
            if (after.width != reference!!.width || after.height != reference!!.height) {
                shots += ShotFind(i + 1, t, emptyList(), "frame size changed mid-clip")
                continue
            }

            val drift = changedFraction(reference!!, after)
            if (drift > DRIFT_FRACTION) {
                // NOT a refusal. The difference is still taken, because a
                // genuine hit on a dark card can move a lot of pixels, and
                // refusing here would throw away a real shot. But the shooter
                // is told, because the usual cause is the camera moving and
                // that quietly ruins every millimetre downstream.
                notes += ("Shot %d: %.0f%% of the card changed, far more than one hole. " +
                    "If the camera moved, the positions after this point are not " +
                    "trustworthy — a tripod fixes it.").format(i + 1, drift * 100)
            }

            val found = HoleDetector.detectByDifference(reg, reference!!, after, gaugeDiameterMm)
            shots += ShotFind(i + 1, t, found)
            Logger.i(TAG, "Shot %d at %.3f s: %d hole(s)%s".format(
                i + 1, t, found.size, if (drift > DRIFT_FRACTION) " (frame changed %.0f%%)".format(drift * 100) else ""))

            // Roll the reference forward whatever happened. A shot that found
            // nothing must not make the NEXT difference span two shots — and
            // if it does find two, the count mismatch is the thing worth
            // reporting, not something to paper over.
            reference = after
        }

        val found = shots.sumOf { it.holes.size }
        if (found != shots.size) {
            notes += ("%d report%s timed in the audio but %d hole%s found. " +
                "Check the shots below against the card: the two counts are independent, " +
                "so a disagreement means one of them is wrong.").format(
                shots.size, if (shots.size == 1) "" else "s",
                found, if (found == 1) " was" else "s were")
        }
        shots.filter { it.holes.size > 1 }.forEach {
            notes += "Shot ${it.index} accounts for ${it.holes.size} holes — " +
                "probably a shot whose own frame showed nothing."
        }

        return Outcome(shots, notes)
    }

    /**
     * Fraction of the rectified card whose brightness moved appreciably.
     *
     * The same measure [LiveHitDetector] uses for its global-change veto, and
     * for the same reason: one bullet hole is a tiny part of a card, so a
     * large fraction changing means the light, the exposure or the camera
     * moved, and nothing measured across that pair means what it says.
     */
    internal fun changedFraction(before: LumaFrame, after: LumaFrame, delta: Int = 24): Double {
        if (before.width != after.width || before.height != after.height) return 1.0
        val n = before.width * before.height
        if (n == 0) return 0.0
        var changed = 0
        for (k in 0 until n) {
            val a = before.data[k].toInt() and 0xFF
            val b = after.data[k].toInt() and 0xFF
            if (a - b > delta || b - a > delta) changed++
        }
        return changed.toDouble() / n
    }
}
