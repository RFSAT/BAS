package com.rfsat.bas.cloud

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Is the answer a reading of the picture, or a shape the model filled in?
 *
 * WHY THIS EXISTS. Mistral, asked about a card whose shots were in the black
 * and strung out to the 5 ring, answered with holes marching evenly from the
 * bottom-left corner to the top-right one. That cannot be a misreading on
 * this side: everything between the reply and the plot is a scale and a flip
 * - OpinionReconciler maps x across uMin..uMax and reads y down from vMax -
 * and no scale or flip turns a group into a line across the whole face. The
 * numbers themselves were invented.
 *
 * It is a documented failure of vision models under a forced schema. The
 * schema guarantees the SHAPE of a reply and says nothing about whether the
 * model looked; one that cannot ground its answer in the image still has to
 * emit well-formed holes, and what comes out is a sweep - coordinates that
 * march across the frame in step, because they were enumerated rather than
 * seen.
 *
 * WHAT IS TESTED, AND WHY IT IS A SUBSET. The first version of this asked
 * whether the WHOLE answer was a ramp, and the answer that prompted the
 * second look was a MIXTURE: eight or so plausible holes in the black,
 * PLUS a dozen strung across the card. Taken together those are not
 * collinear, so a whole-answer test passes them and the invented ones land
 * on the plot. What is looked for now is the largest set of points lying on
 * an evenly STEPPED line, a + k·s, which is what a fabricated answer
 * literally is, and finds it whether or not real holes are mixed in.
 *
 * WHAT THIS IS NOT. It is not a judgement of accuracy and it does not touch
 * the app's own detection. It recognises one pattern that no card ever shot
 * produces, and lets everything else through untouched.
 */
object AnswerSanity {

    /** How far off the step line a point may sit and still count as on it. */
    private const val TOL = 0.035

    /** Enough points to be certain, at which the answer is refused. */
    private const val REJECT_POINTS = 8

    /** Enough to be worth saying, at which the answer is passed with the
     *  warning attached. Six or seven points can be a coincidence often
     *  enough that throwing the answer away would be the greater harm. */
    private const val WARN_POINTS = 6

    /** How far across the frame the line must run. A group - even a poor one
     *  - does not span this; the fabricated answers run corner to corner. */
    private const val SPAN = 0.55

    /** Step sizes worth testing, as a fraction of the frame. */
    private const val MIN_STEP = 0.03
    private const val MAX_STEP = 0.5

    /** A run may skip at most this many steps. A fabricated sweep is
     *  contiguous; allowing a gap of two covers a model that omitted one. */
    private const val MAX_SKIP = 2

    data class Finding(
        /** How many holes lie on the stepped line. */
        val count: Int,
        /** How far it runs, as a fraction of the frame. */
        val span: Double,
        /** True when the answer should be refused outright. */
        val reject: Boolean,
        val message: String
    )

    /**
     * Returns null when nothing about the answer says "not read", or a
     * finding when a run of evenly stepped holes was located.
     *
     * [points] are fractional image coordinates, x left-to-right and y
     * top-to-bottom, exactly as the services are asked for them.
     */
    fun sweep(points: List<Pair<Double, Double>>): Finding? {
        if (points.size < WARN_POINTS) return null

        var bestCount = 0
        var bestSpan = 0.0
        var bestLo = 0.0 to 0.0
        var bestHi = 0.0 to 0.0

        for (i in points.indices) for (j in points.indices) {
            if (i == j) continue
            val ax = points[i].first; val ay = points[i].second
            val sx = points[j].first - ax; val sy = points[j].second - ay
            val step = hypot(sx, sy)
            if (step < MIN_STEP || step > MAX_STEP) continue
            // The pair only STARTS the search. Anchoring on two holes carries
            // their own error into every step, which loses a real ramp that
            // the model jittered by a per cent; so the line is collected
            // once, refitted to everything it caught by least squares, and
            // collected again. Measured, on ramps jittered by 1%: 62% found
            // without the refit, 88% with it, and the rate at which real
            // answers are discarded stays at 4 in 10,000.
            var ox = ax; var oy = ay
            var dx = sx; var dy = sy
            var hit = HashMap<Int, Pair<Double, Double>>()
            for (pass in 0..1) {
                val sq = dx * dx + dy * dy
                if (sq <= 1e-9) break
                // One point per step index: two holes rounding to the same k
                // are one position on the line, not two, and counting both
                // would let a cluster inflate the run.
                hit = HashMap()
                for (p in points) {
                    val kr = (((p.first - ox) * dx + (p.second - oy) * dy) / sq).roundToInt()
                    if (hypot(p.first - (ox + kr * dx), p.second - (oy + kr * dy)) <= TOL)
                        hit.getOrPut(kr) { p }
                }
                if (hit.size < WARN_POINTS || pass == 1) break

                val kk = hit.keys.toList()
                val km = kk.sumOf { it }.toDouble() / kk.size
                val den = kk.sumOf { (it - km) * (it - km) }
                if (den <= 1e-9) break
                val pxm = kk.sumOf { hit[it]!!.first } / kk.size
                val pym = kk.sumOf { hit[it]!!.second } / kk.size
                dx = kk.sumOf { (it - km) * (hit[it]!!.first - pxm) } / den
                dy = kk.sumOf { (it - km) * (hit[it]!!.second - pym) } / den
                ox = pxm - km * dx
                oy = pym - km * dy
            }
            if (hit.size < WARN_POINTS || hit.size <= bestCount) continue

            val ks = hit.keys.sorted()
            val span = (ks.last() - ks.first()) * hypot(dx, dy)
            if (span < SPAN) continue
            if ((1 until ks.size).any { ks[it] - ks[it - 1] > MAX_SKIP }) continue

            bestCount = hit.size
            bestSpan = span
            bestLo = hit[ks.first()]!!
            bestHi = hit[ks.last()]!!
        }

        if (bestCount < WARN_POINTS) return null
        val reject = bestCount >= REJECT_POINTS
        val where = "$bestCount holes spaced evenly along a straight line from " +
            "${corner(bestLo)} to ${corner(bestHi)}, running ${(bestSpan * 100).roundToInt()}% " +
            "of the way across the picture"
        val msg = if (reject)
            "The reply puts $where. That is not a group of shots — it is what a vision model " +
                "produces when it fills in the answer's shape without reading the image. The " +
                "answer has been discarded rather than plotted. Try a stronger model, or use " +
                "the app's own detection."
        else
            "CHECK THIS ANSWER: it puts $where, which is more often invented than shot. It is " +
                "shown as it came, and it is worth comparing against the app's own detection " +
                "before accepting any of it."
        return Finding(bestCount, bestSpan, reject, msg)
    }

    /** Where a point is, in the words a shooter would use. */
    private fun corner(p: Pair<Double, Double>): String {
        val v = if (p.second < 0.34) "top" else if (p.second > 0.66) "bottom" else "middle"
        val h = if (p.first < 0.34) "left" else if (p.first > 0.66) "right" else "centre"
        return if (v == "middle" && h == "centre") "the centre" else "$v $h"
    }

    /** One log line for what came back: how many, and where. Coordinates
     *  only - never the picture, never the key. */
    fun describe(points: List<Pair<Double, Double>>): String {
        if (points.isEmpty()) return "no holes"
        val mx = points.sumOf { it.first } / points.size
        val my = points.sumOf { it.second } / points.size
        val spread = points.maxOf { hypot(it.first - mx, it.second - my) }
        return "${points.size} holes at " +
            points.joinToString(" ") { "(" + fmt(it.first) + "," + fmt(it.second) + ")" } +
            "; furthest from their own centre " + fmt(spread)
    }

    private fun fmt(v: Double): String = (abs(v * 1000).toInt() / 1000.0).toString()
}
