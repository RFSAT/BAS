package com.rfsat.bas.cloud

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Is the answer a reading of the picture, or a shape the model filled in?
 *
 * WHY THIS EXISTS. Mistral was asked for a second opinion on a card whose
 * shots were all inside the 9 and 10 rings, and answered with holes strung
 * evenly along the diagonal, corner to corner. That answer cannot be a
 * misreading on this side: everything downstream is a scale and a flip -
 * OpinionReconciler maps x across uMin..uMax and y down from vMax - and no
 * scale or flip turns a tight cluster into a line across the whole face. The
 * numbers themselves were invented.
 *
 * It is a documented failure of vision models under a forced schema. The
 * schema guarantees the SHAPE of a reply and says nothing about whether the
 * model looked; a model that cannot ground its answer in the image still has
 * to emit well-formed holes, and what comes out is a sweep - coordinates
 * that march across the frame in step, because they were enumerated rather
 * than seen.
 *
 * WHAT THIS IS NOT. It is not a judgement of accuracy, and it does not touch
 * the app's own detection. It rejects one specific, recognisable pattern that
 * no card ever shot produces, and lets everything else through untouched. A
 * merely poor answer is still an answer, and the shooter decides.
 */
object AnswerSanity {

    /** Below this a sweep cannot be told from a line of shots. Four points
     *  are collinear surprisingly often; a fabricated ramp is usually the
     *  whole string, five or more. */
    private const val MIN_POINTS = 5

    /** How straight, and how evenly spaced, are set from measurement rather
     *  than taste. Over 20,000 simulated groups - centres anywhere on the
     *  card, spreads from a tight 10-ring cluster to a 35% scatter - these
     *  two reject none. Against a diagonal walked by recoil, 200 of 200 pass.
     *  Against a fabricated ramp they catch every clean one and every one
     *  jittered by a per cent. A ramp jittered by three per cent gets
     *  through, and that is the deliberate side to err on: throwing away a
     *  real answer is worse than passing a bad one, which the shooter can
     *  see for themselves. */
    private const val STRAIGHT = 0.99

    /** How far across the frame, as a fraction of its width. A cluster in the
     *  9 and 10 rings spans a tenth of the card; the fabricated answers run
     *  corner to corner. */
    private const val SPAN = 0.55

    /** How evenly spaced, as the coefficient of variation of the gaps
     *  between consecutive points along the line. Enumerated coordinates
     *  step uniformly; shots do not. */
    private const val EVEN = 0.25

    /**
     * Returns null when the answer looks like a reading, or a sentence saying
     * what is wrong with it when it does not.
     *
     * [points] are fractional image coordinates, x left-to-right and y
     * top-to-bottom, exactly as the services are asked for them.
     */
    fun sweep(points: List<Pair<Double, Double>>): String? {
        if (points.size < MIN_POINTS) return null

        val n = points.size
        val mx = points.sumOf { it.first } / n
        val my = points.sumOf { it.second } / n
        var sxx = 0.0
        var syy = 0.0
        var sxy = 0.0
        for (p in points) {
            val dx = p.first - mx
            val dy = p.second - my
            sxx += dx * dx
            syy += dy * dy
            sxy += dx * dy
        }
        // A file of points exactly vertical or exactly horizontal has no
        // correlation to measure. Left alone deliberately: a row of shots
        // along one axis is a thing people do on test cards, and a diagonal
        // sweep is what was actually seen.
        if (sxx <= 1e-12 || syy <= 1e-12) return null
        val r = sxy / sqrt(sxx * syy)
        if (abs(r) < STRAIGHT) return null

        // Projected onto the PRINCIPAL axis rather than the regression line,
        // so a steep ramp is measured the same way as a shallow one.
        val theta = 0.5 * atan2(2 * sxy, sxx - syy)
        val ux = cos(theta)
        val uy = sin(theta)
        val along = points.map { (it.first - mx) * ux + (it.second - my) * uy }
        val t = along.sorted()
        val span = t.last() - t.first()
        if (span < SPAN) return null

        val gaps = (1 until n).map { t[it] - t[it - 1] }
        val gm = gaps.average()
        if (gm <= 1e-9) return null
        val gsd = sqrt(gaps.sumOf { (it - gm) * (it - gm) } / gaps.size)
        if (gsd / gm > EVEN) return null

        val lo = points[along.indexOf(along.min())]
        val hi = points[along.indexOf(along.max())]
        return "The reply lists $n holes spaced evenly along a straight line from " +
            "${corner(lo)} to ${corner(hi)}, spanning ${(span * 100).toInt()}% of the picture. " +
            "That is not a group of shots - it is what a vision model produces when it fills in " +
            "the answer's shape without reading the image. The answer has been discarded rather " +
            "than plotted. Try a stronger model, or use the app's own detection."
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

    private fun fmt(v: Double): String = ((v * 1000).toInt() / 1000.0).toString()
}
