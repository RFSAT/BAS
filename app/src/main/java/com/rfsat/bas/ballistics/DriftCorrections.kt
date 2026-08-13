package com.rfsat.bas.ballistics

import com.rfsat.bas.profiles.BulletProfile
import com.rfsat.bas.profiles.RifleProfile
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Where the bullet goes for reasons the point-mass integrator cannot know.
 *
 * [BallisticsEngine] models a point with drag and gravity. That is most of
 * the answer, and at 300 m it is very nearly all of it. Past roughly 600 m
 * three effects it cannot see start to move the group by more than the group
 * itself measures, and every one of them is SYSTEMATIC — they shift the whole
 * group rather than widening it, so no amount of shooting averages them away
 * and a shooter who re-zeroes for them at one distance is wrong at every
 * other.
 *
 *  - SPIN DRIFT. A gyroscopically stable bullet noses very slightly into its
 *    own yaw of repose and slides sideways, always toward the direction of
 *    twist. Roughly 20-25 cm at 1000 m for a typical .308 or .338 — a whole
 *    ring, always in the same direction.
 *  - CORIOLIS. The target moves with the rotating Earth during the flight.
 *    Horizontal deflection depends only on latitude; the vertical (Eotvos)
 *    part depends on which way the rifle points, and reverses between east
 *    and west.
 *  - CANT. Tilting the rifle swings the elevation the shooter has dialled out
 *    of the vertical, so part of the come-up becomes sideways error. This one
 *    is not the Earth's fault and is usually the largest of the three.
 *
 * Everything here returns METRES AT THE TARGET, +z right and +y up, matching
 * [TrajectoryPoint]'s convention, so the caller adds them to the simulated
 * impact point and needs no unit bookkeeping.
 *
 * A NOTE ON WHAT THESE ARE. Spin drift and Coriolis below are the standard
 * closed-form approximations from the exterior-ballistics literature (Miller's
 * twist rule, Litz's drift fit, the usual first-order Coriolis terms), not
 * integrations of the full six-degree-of-freedom equations. They are what
 * every commercial solver uses, they are good to a centimetre or two at
 * 1000 m, and they are far closer to the truth than the zero this app used
 * before. They are NOT a substitute for the shooter's own dope.
 */
object DriftCorrections {

    /** Earth's sidereal rotation rate, rad/s. */
    const val EARTH_RATE = 7.2921159e-5

    // ---------------------------------------------------------------- spin

    /**
     * Bullet length in inches, estimated from weight and calibre when the
     * shooter has not measured it.
     *
     * Miller's stability rule needs length, which no ammunition box prints.
     * Length scales as weight / (density x form x calibre squared), and for
     * jacketed lead-core rifle bullets the whole cluster of constants
     * collapses to one number. Calibrated against three bullets spanning the
     * usual range: 175 gr .308 (1.24 in measured, 1.24 estimated), 140 gr
     * 6.5 mm (1.35 / 1.37), 300 gr .338 (1.79 / 1.76).
     *
     * It is an ESTIMATE, and it is wrong for monolithic copper (longer for
     * their weight, so this under-reads stability) and for pellets, which are
     * not this shape at all. Both are handled by the caller; a shooter who
     * knows the real length can set it on the profile and this is not used.
     */
    fun estimatedBulletLengthIn(weightGrains: Double, caliberIn: Double): Double {
        if (weightGrains <= 0.0 || caliberIn <= 0.0) return 0.0
        return weightGrains / (1490.0 * caliberIn * caliberIn)
    }

    /**
     * Miller's gyroscopic stability factor Sg, with the standard velocity and
     * atmosphere corrections. Above about 1.4 a bullet is stable; below 1.0 it
     * tumbles. Spin drift grows with it, which is the only reason it is
     * computed here.
     */
    fun gyroscopicStability(
        bullet: BulletProfile,
        rifle: RifleProfile,
        atmosphere: Atmosphere
    ): Double {
        val d = bullet.caliberDiameterIn
        val twistIn = rifle.twistRateInPerTurn
        if (d <= 0.0 || twistIn <= 0.0) return 0.0

        val lengthIn = if (bullet.lengthIn > 0.0) bullet.lengthIn
                       else estimatedBulletLengthIn(bullet.weightGrains, d)
        if (lengthIn <= 0.0) return 0.0

        val t = twistIn / d          // twist in calibres per turn
        val l = lengthIn / d         // length in calibres
        if (t <= 0.0 || l <= 0.0) return 0.0

        val sg0 = 30.0 * bullet.weightGrains / (t * t * d * d * d * l * (1.0 + l * l))

        // Velocity correction: Miller's rule is quoted at 2800 fps.
        val vCorr = Math.cbrt(bullet.muzzleVelocityFps / 2800.0)

        // Atmosphere correction: Sg goes as 1/air density, expressed in the
        // rule's original units of Fahrenheit and inches of mercury.
        val tempF = atmosphere.temperatureC * 9.0 / 5.0 + 32.0
        val pressureInHg = atmosphere.seaLevelPressurePa / 3386.389
        val aCorr = if (pressureInHg > 0.0)
            ((tempF + 460.0) / 519.0) * (29.92 / pressureInHg) else 1.0

        return sg0 * vCorr * aCorr
    }

    /**
     * Lateral drift from the yaw of repose, metres at the target, +right.
     * Litz's empirical fit: 1.25 (Sg + 1.2) t^1.83, in inches with t in
     * seconds. Left-hand twist mirrors it.
     *
     * The time exponent is why this is a long-range effect and only that: at
     * half a second it is under a centimetre, at a second and a half it is a
     * quarter of a metre.
     */
    fun spinDriftM(gyroscopicStability: Double, timeOfFlightS: Double, rightHandTwist: Boolean): Double {
        if (gyroscopicStability <= 0.0 || timeOfFlightS <= 0.0) return 0.0
        val inches = 1.25 * (gyroscopicStability + 1.2) * Math.pow(timeOfFlightS, 1.83)
        val metres = inches * 0.0254
        return if (rightHandTwist) metres else -metres
    }

    // ------------------------------------------------------------ Coriolis

    /** Horizontal and vertical Coriolis deflection at the target, metres. */
    data class Coriolis(val lateralM: Double, val verticalM: Double)

    /**
     * First-order Coriolis for a flat-fire trajectory.
     *
     * HORIZONTAL depends only on latitude, never on heading: right in the
     * northern hemisphere, left in the southern, nothing at the equator.
     * Roughly 8 cm at 1000 m at European latitudes.
     *
     * VERTICAL is the Eotvos effect and depends entirely on heading, because
     * a bullet fired east is fired along the Earth's own rotation and one
     * fired west against it. East shoots high, west shoots low, north and
     * south are unaffected — which is why a solver that ignores azimuth is
     * not merely incomplete but capable of applying this backwards.
     *
     * [azimuthDeg] is the compass bearing the rifle points along, degrees
     * clockwise from true north. Null means the app does not know it, and
     * the vertical term is then omitted rather than guessed.
     */
    fun coriolisM(
        latitudeDeg: Double,
        azimuthDeg: Double?,
        rangeM: Double,
        timeOfFlightS: Double
    ): Coriolis {
        if (rangeM <= 0.0 || timeOfFlightS <= 0.0) return Coriolis(0.0, 0.0)
        val latRad = Math.toRadians(latitudeDeg)
        val lateral = EARTH_RATE * sin(latRad) * rangeM * timeOfFlightS
        val vertical = if (azimuthDeg == null) 0.0 else
            EARTH_RATE * cos(latRad) * sin(Math.toRadians(azimuthDeg)) * rangeM * timeOfFlightS
        return Coriolis(lateral, vertical)
    }

    // ---------------------------------------------------------------- cant

    /** Lateral and vertical error from a canted rifle, metres at the target. */
    data class Cant(val lateralM: Double, val verticalM: Double)

    /**
     * What tilting the rifle does to elevation that has already been dialled.
     *
     * The scope's elevation is a rotation about the bore. Cant the rifle by
     * an angle and that rotation is no longer vertical: a fraction sin(cant)
     * of the come-up becomes lateral, and the vertical keeps only cos(cant).
     * So the error scales with the ELEVATION HELD, not with the range as
     * such — which is why cant barely matters on a flat 100 m zero and badly
     * matters at 800 m, and why it hurts an air rifle at 50 m far more than
     * the distance suggests.
     *
     * [comeUpRad] is the angle between the bore and the line of sight.
     * Positive [cantDeg] is the top of the rifle tilted to the RIGHT, which
     * throws the shot right and low.
     */
    fun cantErrorM(cantDeg: Double, comeUpRad: Double, rangeM: Double): Cant {
        if (cantDeg == 0.0 || rangeM <= 0.0 || comeUpRad == 0.0) return Cant(0.0, 0.0)
        val c = Math.toRadians(cantDeg)
        val riseM = comeUpRad * rangeM
        return Cant(riseM * sin(c), -riseM * (1.0 - cos(c)))
    }

    /** True when cant is large enough to be worth telling the shooter about
     *  at this range: about a centimetre of error. Below that it is noise
     *  next to the group. */
    fun cantWorthReporting(cantDeg: Double, comeUpRad: Double, rangeM: Double): Boolean =
        abs(cantErrorM(cantDeg, comeUpRad, rangeM).lateralM) >= 0.01
}
