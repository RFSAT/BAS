package com.rfsat.bas.detect

import android.content.Context
import com.rfsat.bas.cloud.OpinionReconciler
import com.rfsat.bas.log.Logger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.hypot

/**
 * What the detector got right, what it got wrong, and the evidence for both.
 *
 * This exists to make the detector IMPROVABLE. Its thresholds — SIGMA_THRESHOLD
 * 6.0, MIN_CONTRAST 8.0, MAX_ELONGATION 2.2 and a dozen more — were chosen by
 * what they rejected on the cards available at the time. Nobody can say what
 * moving one of them would do, because nothing measures the result. Every
 * change to the pipeline is therefore a guess, and a guess that improves one
 * card while quietly ruining another is indistinguishable from a fix.
 *
 * A SECOND OPINION IS A LABEL. When the shooter asks a vision model to look at
 * the same card, the reconciliation already sorts the marks into three piles:
 *
 *   * agreed        — both saw a shot in the same place. The positions form a
 *                     pair, and the distance between them is the only view of
 *                     the app's position error that exists without hand-marked
 *                     ground truth.
 *   * unsupported   — the app marked something the model did not see. Most
 *                     false positives look exactly like this.
 *   * unconfirmed   — the model saw something the app missed. Most misses look
 *                     exactly like this.
 *
 * So the labels arrive as a by-product of a feature the shooter already uses,
 * on THEIR cards, in THEIR light. That is worth more than any corpus collected
 * elsewhere, because it is the distribution the detector actually faces.
 *
 * NEITHER SIDE IS GROUND TRUTH, and the file says so. The model misses shots
 * and invents them; "unsupported" is not proof of a false positive. What the
 * record supports is a TREND across many cards — a threshold that shifts the
 * agreed count up and both disagreement piles down is better, and that
 * statement can now be checked instead of argued about.
 *
 * WHAT IS STORED: geometry and the detector's own numbers. No photograph, no
 * location, no key, nothing about the shooter. The file stays in app storage
 * until it is deliberately exported.
 */
object DetectionAudit {

    private const val DIR = "detection-audit"
    private const val FILE = "audit.jsonl"

    /** Two marks within this are the same shot. Roughly one pellet across:
     *  wide enough for the model's eyeballed coordinates, tight enough that
     *  adjacent shots in a group are not merged into one pair. */
    private const val PAIR_TOLERANCE_MM = 6.0

    /** Enough to see a trend without letting the file grow for ever. At one
     *  line per second opinion this is years of shooting. */
    private const val MAX_RECORDS = 2000

    private fun file(context: Context): File =
        File(File(context.filesDir, DIR).apply { mkdirs() }, FILE)

    data class Summary(
        val cards: Int,
        val agreed: Int,
        val unsupported: Int,
        val unconfirmed: Int,
        /** Mean distance between paired positions, mm. The app's position
         *  error is smaller than this: the model's own estimate is the
         *  coarser of the two, so most of this number is its error, not the
         *  detector's. It is a CEILING on the disagreement, not a measurement
         *  of the app. */
        val meanPairDistanceMm: Double
    ) {
        val total: Int get() = agreed + unsupported + unconfirmed
        fun describe(): String {
            if (cards == 0) return "No comparisons recorded yet. Ask for a second opinion on a " +
                "scored card and the result is kept here."
            val agreePct = if (total > 0) agreed * 100.0 / total else 0.0
            return buildString {
                appendLine("%d card(s) compared.".format(cards))
                appendLine("Agreed on %d mark(s) — %.0f%% of everything either side saw."
                    .format(agreed, agreePct))
                appendLine("App marked but service did not see: %d".format(unsupported))
                appendLine("Service saw but app did not mark: %d".format(unconfirmed))
                if (agreed > 0) {
                    appendLine("Mean distance between agreed positions: %.1f mm"
                        .format(meanPairDistanceMm))
                }
                appendLine()
                append("Neither side is ground truth — the service misses shots and invents " +
                    "them too. These numbers are for comparing one detector setting against " +
                    "another over many cards, not for judging a single one.")
            }
        }
    }

    /**
     * Files one comparison. Silent on failure: this is instrumentation, and
     * instrumentation that can break scoring is worse than none.
     */
    fun record(
        context: Context,
        rec: OpinionReconciler.Reconciliation,
        measured: List<DetectedHole>,
        faceName: String,
        distanceM: Double,
        service: String,
        model: String
    ) {
        runCatching {
            val unsupportedIds = rec.unsupported.map { System.identityHashCode(it) }.toSet()
            val agreed = measured.filter { System.identityHashCode(it) !in unsupportedIds }

            // Pair each agreed hole with the nearest claimed mark, so the
            // distance between them can be recorded. Nearest-first rather
            // than a full assignment: with a tolerance this tight the two
            // give the same answer on any group loose enough to score.
            val holes = JSONArray()
            for (h in agreed) {
                val near = rec.claimedMm.minByOrNull { hypot(it.xMm - h.xMm, it.yMm - h.yMm) }
                val d = near?.let { hypot(it.xMm - h.xMm, it.yMm - h.yMm) }
                holes.put(featuresOf(h).apply {
                    put("label", "agreed")
                    if (d != null && d <= PAIR_TOLERANCE_MM) put("pairDistanceMm", round2(d))
                })
            }
            for (h in rec.unsupported) {
                holes.put(featuresOf(h).put("label", "unsupported"))
            }
            for (s in rec.unconfirmed) {
                holes.put(JSONObject()
                    .put("label", "unconfirmed")
                    .put("xMm", round2(s.xMm))
                    .put("yMm", round2(s.yMm))
                    .put("note", s.note))
            }

            val line = JSONObject()
                .put("at", System.currentTimeMillis())
                .put("face", faceName)
                .put("distanceM", distanceM)
                .put("service", service)
                .put("model", model)
                .put("measured", rec.measured)
                .put("claimed", rec.claimed)
                .put("faceAgrees", rec.faceAgrees)
                .put("holes", holes)
                .toString()

            val f = file(context)
            f.appendText(line + "\n")
            trim(f)
            Logger.i("DetectionAudit",
                "recorded: ${agreed.size} agreed, ${rec.unsupported.size} unsupported, " +
                    "${rec.unconfirmed.size} unconfirmed")
        }.onFailure { Logger.w("DetectionAudit", "could not record: ${it.message}") }
    }

    /**
     * The detector's own numbers for one hole — the inputs any future
     * classifier would learn from, stored now so that when there IS a
     * classifier there is already something to train it on. Collecting them
     * later means starting the clock later.
     */
    private fun featuresOf(h: DetectedHole): JSONObject = JSONObject()
        .put("xMm", round2(h.xMm))
        .put("yMm", round2(h.yMm))
        .put("diameterMm", round2(h.diameterMm))
        .put("contrast", round2(h.contrast))
        .put("confidence", round2(h.confidence))
        .put("elongation", round2(h.elongation))
        .put("merged", h.merged)
        .put("radiusMm", round2(h.distanceFromCentreMm))

    private fun round2(v: Double) = Math.round(v * 100.0) / 100.0

    private fun trim(f: File) {
        val lines = f.readLines()
        if (lines.size <= MAX_RECORDS) return
        f.writeText(lines.takeLast(MAX_RECORDS).joinToString("\n") + "\n")
    }

    fun summary(context: Context): Summary = runCatching {
        val f = file(context)
        if (!f.exists()) return@runCatching Summary(0, 0, 0, 0, 0.0)
        var cards = 0; var agreed = 0; var unsup = 0; var unconf = 0
        var dSum = 0.0; var dN = 0
        for (line in f.readLines()) {
            if (line.isBlank()) continue
            val o = runCatching { JSONObject(line) }.getOrNull() ?: continue
            cards++
            val hs = o.optJSONArray("holes") ?: continue
            for (i in 0 until hs.length()) {
                val h = hs.optJSONObject(i) ?: continue
                when (h.optString("label")) {
                    "agreed" -> {
                        agreed++
                        if (h.has("pairDistanceMm")) { dSum += h.optDouble("pairDistanceMm"); dN++ }
                    }
                    "unsupported" -> unsup++
                    "unconfirmed" -> unconf++
                }
            }
        }
        Summary(cards, agreed, unsup, unconf, if (dN > 0) dSum / dN else 0.0)
    }.getOrDefault(Summary(0, 0, 0, 0, 0.0))

    /** The raw file, for exporting. Null when nothing has been recorded. */
    fun exportFile(context: Context): File? = file(context).takeIf { it.exists() && it.length() > 0 }

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }
}
