package com.rfsat.bas.ballistics

import com.rfsat.bas.profiles.BulletProfile
import com.rfsat.bas.profiles.RifleProfile
import kotlin.math.abs

/**
 * Fits the trajectory to where the shots actually went.
 *
 * Every number the solver is given is approximate. The BC is a catalogue
 * figure for a bullet that was not this one, from a test barrel that was not
 * this barrel, and the drag curve is a standard reference rather than this
 * projectile. Truing stops arguing with any of that and asks a different
 * question: what value would have produced the impacts already recorded?
 *
 * It is the highest-value correction in the app precisely because it is not
 * a model of anything. It absorbs whatever is wrong — velocity, drag, sight
 * height, the shooter's own consistent hold — into one number that makes the
 * predictions match the target.
 *
 * WHICH KNOB, AND WHY IT MATTERS
 * ------------------------------
 * Muzzle velocity and BC both move the drop curve, so fitting both to
 * observations at ONE distance is underdetermined: infinitely many pairs fit
 * equally well, and the solver would happily return a 200 fps error cancelled
 * by an absurd BC. They are separable only because they act with different
 * SHAPES against distance:
 *
 *   * Velocity dominates near. Time of flight is nearly range/velocity while
 *     the bullet is fast, so an error in velocity shows up as a proportional
 *     drop error early and never gets a chance to compound.
 *   * Drag dominates far. Its effect accumulates with every metre flown, so
 *     it is small at 300 m and the largest term by 1000.
 *
 * So: TRUE VELOCITY FROM NEAR OBSERVATIONS, DRAG FROM FAR ONES, in that
 * order, and never both from the same distance. [SPLIT_RANGE_M] is where the
 * app stops trusting an observation to tell it about velocity.
 *
 * Drag is trued through [BulletProfile.dragCalibrationFactor] rather than by
 * rewriting the BC, so the catalogue figure the shooter recognises stays
 * visible and the correction is inspectable as "12% more drag than the
 * reference curve" instead of a silently altered BC.
 */
object Truing {

    /**
     * Below this a drop observation is treated as evidence about velocity;
     * at or above it, as evidence about drag. 500 m is chosen because that
     * is roughly where a centrefire rifle bullet has lost enough speed that
     * accumulated drag error overtakes launch-velocity error — nearer, the
     * fit is dominated by how fast it started; further, by how quickly it
     * slowed down.
     */
    const val SPLIT_RANGE_M = 500.0

    /**
     * One shot group, measured.
     *
     * [verticalDropM] is where the group centre landed relative to the point
     * of aim, POSITIVE DOWN, with the scope on the same zero the profile
     * describes. That is what the scoring half of this app measures for a
     * living, which is the whole reason truing is worth having here.
     */
    data class DropObservation(
        val distanceM: Double,
        val verticalDropM: Double,
        val atmosphere: Atmosphere = Atmosphere()
    )

    data class Result(
        val trued: BulletProfile,
        /** What was fitted, for the shooter to read and accept or reject. */
        val summary: String,
        /** RMS of the remaining drop error, metres. Large means the
         *  observations disagree with each other, not that the fit failed. */
        val residualM: Double,
        val converged: Boolean,
        val warnings: List<String>
    )

    /** Predicted drop below the line of sight at a distance, positive down. */
    private fun predictedDropM(
        bullet: BulletProfile,
        rifle: RifleProfile,
        sightHeightM: Double,
        obs: DropObservation
    ): Double {
        val pitch = BallisticsEngine.solveZeroPitch(
            bullet, obs.atmosphere, rifle.zeroDistanceM, sightHeightM)
        val traj = BallisticsEngine.simulate(
            bullet, obs.atmosphere, pitch, 0.0, obs.distanceM + 1.0)
        val at = traj.lastOrNull { it.position.x <= obs.distanceM } ?: return Double.NaN
        if (at.position.x < obs.distanceM * 0.95) return Double.NaN
        return -(at.position.y - sightHeightM)
    }

    private fun rms(values: List<Double>): Double =
        if (values.isEmpty()) 0.0
        else Math.sqrt(values.sumOf { it * it } / values.size)

    /**
     * Golden-section search on a single parameter.
     *
     * Not Newton's method, and not the secant method: a trajectory is
     * integrated numerically, so its derivative with respect to velocity is
     * only available as a difference of two noisy simulations, and a
     * derivative-based solver chases that noise. Golden section needs no
     * derivative and cannot diverge — it merely narrows a bracket, so the
     * worst case is that it stops early with the interval still wide, which
     * [converged] reports honestly.
     */
    private fun minimise(lo: Double, hi: Double, tol: Double, f: (Double) -> Double): Pair<Double, Boolean> {
        val phi = (Math.sqrt(5.0) - 1.0) / 2.0
        var a = lo; var b = hi
        var c = b - phi * (b - a); var d = a + phi * (b - a)
        var fc = f(c); var fd = f(d)
        var iterations = 0
        while (abs(b - a) > tol && iterations < 60) {
            if (fc < fd) { b = d; d = c; fd = fc; c = b - phi * (b - a); fc = f(c) }
            else { a = c; c = d; fc = fd; d = a + phi * (b - a); fd = f(d) }
            iterations++
        }
        return (a + b) / 2.0 to (abs(b - a) <= tol)
    }

    /**
     * Trues the profile against whatever observations exist: velocity from
     * those inside [SPLIT_RANGE_M], drag from those beyond it, velocity
     * first so the drag fit starts from a corrected launch speed.
     */
    fun trueProfile(
        bullet: BulletProfile,
        rifle: RifleProfile,
        sightHeightM: Double,
        observations: List<DropObservation>
    ): Result {
        val warnings = mutableListOf<String>()
        val usable = observations.filter { it.distanceM > 0.0 && it.verticalDropM.isFinite() }
        if (usable.isEmpty()) {
            return Result(bullet, "Nothing to fit — no usable observations.", 0.0, false,
                listOf("Truing needs at least one measured group at a known distance."))
        }

        val near = usable.filter { it.distanceM < SPLIT_RANGE_M }
        val far = usable.filter { it.distanceM >= SPLIT_RANGE_M }
        var current = bullet
        val parts = mutableListOf<String>()

        if (near.isNotEmpty()) {
            val v0 = bullet.muzzleVelocityFps
            // +-20% brackets any believable disagreement between a catalogue
            // figure and a real barrel. A fit that runs to the edge of that
            // is not a velocity error, and says so below.
            val (bestV, ok) = minimise(v0 * 0.8, v0 * 1.2, 1.0) { v ->
                val trial = current.copy(muzzleVelocityFps = v)
                rms(near.map { o ->
                    val p = predictedDropM(trial, rifle, sightHeightM, o)
                    if (p.isNaN()) 1e6 else p - o.verticalDropM
                })
            }
            current = current.copy(muzzleVelocityFps = bestV)
            val shift = bestV - v0
            parts.add("muzzle velocity %.0f fps (%+.0f)".format(bestV, shift))
            if (!ok) warnings.add("The velocity fit did not settle; treat it as indicative.")
            if (abs(shift) > v0 * 0.15) warnings.add(
                "Fitted velocity is %.0f fps from the catalogue figure — that is more than a barrel ".format(abs(shift)) +
                "difference explains. Check the zero distance and sight height before trusting it."
            )
        } else {
            warnings.add(
                "No group closer than ${SPLIT_RANGE_M.toInt()} m, so muzzle velocity was left alone " +
                "and only drag was fitted. A near group makes both numbers meaningful."
            )
        }

        if (far.isNotEmpty()) {
            val (bestK, ok) = minimise(0.7, 1.5, 0.001) { k ->
                val trial = current.copy(dragCalibrationFactor = k)
                rms(far.map { o ->
                    val p = predictedDropM(trial, rifle, sightHeightM, o)
                    if (p.isNaN()) 1e6 else p - o.verticalDropM
                })
            }
            current = current.copy(dragCalibrationFactor = bestK)
            parts.add("drag %.3f of the reference curve".format(bestK))
            if (!ok) warnings.add("The drag fit did not settle; treat it as indicative.")
            if (bestK < 0.75 || bestK > 1.4) warnings.add(
                "Fitted drag is %.2f of the standard curve, which is outside what a real bullet does. ".format(bestK) +
                "Something else is wrong — most often the distance or the zero."
            )
        } else {
            warnings.add(
                "No group at ${SPLIT_RANGE_M.toInt()} m or beyond, so the drag curve is unchanged. " +
                "Velocity alone cannot be extrapolated past the distance it was fitted at."
            )
        }

        val residual = rms(usable.map { o ->
            val p = predictedDropM(current, rifle, sightHeightM, o)
            if (p.isNaN()) 1e6 else p - o.verticalDropM
        })

        return Result(
            trued = current,
            summary = if (parts.isEmpty()) "Nothing fitted." else "Fitted " + parts.joinToString(" and ") + ".",
            residualM = residual,
            converged = residual < 0.5,
            warnings = warnings
        )
    }
}
