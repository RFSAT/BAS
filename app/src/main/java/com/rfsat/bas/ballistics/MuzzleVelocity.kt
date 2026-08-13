package com.rfsat.bas.ballistics

/**
 * The muzzle velocity a load actually leaves THIS rifle at.
 *
 * Catalogue velocities are measured in a test barrel, and the shooter's
 * barrel is a different length. That difference is not a detail: 4 inches of
 * barrel is around 100 fps on a centrefire rifle, which at 600 m is most of a
 * ring of elevation. Until now the app took the box figure literally no
 * matter what the rifle profile said the barrel was.
 *
 * These are RULES OF THUMB and are documented as such wherever they surface.
 * Real velocity depends on the powder's burn rate, the lot, the throat and
 * the bore, and the only way to know it is a chronograph — or [Truing],
 * which infers it from where the shots actually landed. This exists to make
 * the starting guess a good one, because a fit that starts near the answer
 * converges to the right minimum, and one that starts far away can converge
 * to a plausible-looking wrong one.
 */
object MuzzleVelocity {

    /** Centrefire rifle, fps per inch of barrel. The usual quoted band is
     *  20-30 fps/in over normal lengths; 25 is the middle of it. */
    const val CENTREFIRE_FPS_PER_INCH = 25.0

    /** Below this, a .22 LR is still gaining velocity; above it the powder
     *  has long since finished burning and friction slowly wins. This
     *  reversal is why applying a centrefire rule to a rimfire gets the SIGN
     *  wrong for every long-barrelled rifle. */
    const val RIMFIRE_PEAK_IN = 16.0
    const val RIMFIRE_GAIN_FPS_PER_INCH = 15.0
    const val RIMFIRE_LOSS_FPS_PER_INCH = 2.0

    /**
     * Corrects a catalogue velocity from the test barrel it was measured in
     * to the barrel actually fitted.
     *
     * Returns [catalogFps] unchanged when either length is unknown, when the
     * projectile is a pellet (an airgun's velocity comes from its power
     * plant, and barrel length trades pressure against friction in a way no
     * linear rule describes), or when the two barrels are the same.
     */
    fun forBarrel(
        catalogFps: Double,
        testBarrelIn: Double,
        actualBarrelIn: Double,
        rimfire: Boolean,
        pellet: Boolean
    ): Double {
        if (pellet) return catalogFps
        if (catalogFps <= 0.0 || testBarrelIn <= 0.0 || actualBarrelIn <= 0.0) return catalogFps
        if (testBarrelIn == actualBarrelIn) return catalogFps

        val delta = if (!rimfire) {
            (actualBarrelIn - testBarrelIn) * CENTREFIRE_FPS_PER_INCH
        } else {
            // Piecewise around the peak: gaining below it, slowly losing
            // above it. Computed as the difference of two integrals from the
            // peak so the curve is continuous and the answer does not jump
            // when a barrel happens to sit exactly at 16 inches.
            fun fromPeak(len: Double) = if (len < RIMFIRE_PEAK_IN)
                (len - RIMFIRE_PEAK_IN) * RIMFIRE_GAIN_FPS_PER_INCH
            else
                (len - RIMFIRE_PEAK_IN) * -RIMFIRE_LOSS_FPS_PER_INCH
            fromPeak(actualBarrelIn) - fromPeak(testBarrelIn)
        }
        return (catalogFps + delta).coerceAtLeast(1.0)
    }

    /** True when this is rimfire-like: subsonic-to-modest velocity from a
     *  .22-ish bore. Used only to choose which rule of thumb applies. */
    fun looksRimfire(caliberIn: Double, catalogFps: Double): Boolean =
        caliberIn <= 0.23 && catalogFps < 1800.0
}
