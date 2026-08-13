package com.rfsat.bas.results

import com.rfsat.bas.ballistics.Atmosphere
import com.rfsat.bas.ballistics.BallisticsEngine
import com.rfsat.bas.ballistics.DriftCorrections
import com.rfsat.bas.ballistics.Vec3
import com.rfsat.bas.profiles.BulletProfile
import com.rfsat.bas.profiles.RifleProfile
import com.rfsat.bas.profiles.ScopeProfile
import com.rfsat.bas.wind.WindEstimator
import com.rfsat.bas.wind.WindSample
import kotlin.math.abs
import kotlin.math.atan

data class ScopeAdjustment(
    // Angular corrections in the SCOPE'S OWN unit (MRAD for mil scopes,
    // MOA for MOA scopes). +right / +up.
    val windageScopeUnits: Double,
    val elevationScopeUnits: Double,
    val scopeUnitLabel: String,       // "MRAD" or "MOA"
    val windageClicks: Int,
    val elevationClicks: Int,
    val windageDirection: String,     // "LEFT" or "RIGHT" turret direction
    val elevationDirection: String,   // "UP" or "DOWN" turret direction
    val estimatedCrosswindMps: Double,     // uniform wind used in the solution, +right
    val estimatedVerticalWindMps: Double,  // +up
    val windConfidence: Double,            // 0..1
    /** v19.0: sample spread (1 sd) of the trimmed wind estimates — gust/
     *  noise scatter behind the mean. 0.0 on payloads from older versions. */
    val crosswindStdMps: Double = 0.0,
    val verticalWindStdMps: Double = 0.0,
    val impactOffsetMAtTarget: Vec3,  // diagnostic: last shot's landing point vs POA, metres (x unused)
    val warnings: List<String>,       // practicality/sanity flags for the Results screen
    /** false when the simulated trajectory never reached the target — the
     *  numbers above are then meaningless and must not be displayed. */
    val valid: Boolean = true
)

/**
 * What the app knows about where the rifle is and how it is being held —
 * none of which the trajectory integrator can infer.
 *
 * Latitude comes from the position already stored for weather lookups, so
 * for most shooters it costs nothing to know. Azimuth and cant have no
 * source but the shooter. Each is NULLABLE ON PURPOSE: a missing value
 * leaves the corresponding term out, which is honest, where a default of
 * zero would quietly assert the equator and a level rifle.
 */
data class ShotGeometry(
    val latitudeDeg: Double? = null,
    val firingAzimuthDeg: Double? = null,
    val cantDeg: Double = 0.0
)

object AdjustmentCalculator {

    private const val MRAD_TO_MOA = 3.43775 // 1 mrad = 3.43775 MOA

    /** Hard rejection: above this the estimate is an artefact, full stop.
     *  15 m/s = 54 km/h = near-gale; not a practical shooting condition and
     *  not something a filmed vapor trail survives coherently. */
    private const val MAX_CREDIBLE_WIND_MPS = 15.0
    /** Caution band: 8 m/s (29 km/h, Beaufort 5) is already an unusually
     *  strong wind to be shooting in — plausible, but worth flagging. */
    private const val STRONG_WIND_MPS = 8.0
    /** Below this the fit is statistically meaningless — using it would be
     *  worse than the zero-wind solution it replaces. */
    private const val MIN_USABLE_CONFIDENCE = 0.05
    /** Was a TESTING SWITCH (v13.0), left false through every release since,
     *  with a comment on it saying to turn it back on before shipping. It
     *  meant a wind fit of under 5% confidence — statistically nothing —
     *  was dialled as though it were a measurement. Now true, which is what
     *  the comment always said it should be. */
    private const val ENFORCE_MIN_CONFIDENCE = true

    /**
     * Computes the scope adjustment needed so the *next* shot lands on the
     * point of aim, using the UNIFORM average wind derived from the trail's
     * drift (see [WindEstimator] for why one boresighted camera can only
     * support a uniform-wind model). Output is in the scope's own angular
     * unit and click count, with warnings when the correction is not
     * practically achievable or the wind estimate is not credible.
     */
    fun computeAdjustment(
        bullet: BulletProfile,
        rifle: RifleProfile,
        scope: ScopeProfile,
        atmosphere: Atmosphere,
        targetDistanceYd: Double,
        windSamples: List<WindSample>,
        geometry: ShotGeometry = ShotGeometry()
    ): ScopeAdjustment {
        val warnings = mutableListOf<String>()

        val avg = WindEstimator.averageWindStats(windSamples)
        var crossMps = avg?.crossMps ?: 0.0
        var vertMps = avg?.vertMps ?: 0.0
        val windConf = avg?.confidence ?: 0.0
        val crossSd = avg?.crossSdMps ?: 0.0
        val vertSd = avg?.vertSdMps ?: 0.0
        if (avg == null) {
            warnings.add("No usable wind estimate from the trail (none, or mostly implausible samples) — this is a zero-wind solution.")
        } else if (abs(crossMps) > MAX_CREDIBLE_WIND_MPS || abs(vertMps) > MAX_CREDIBLE_WIND_MPS) {
            warnings.add(
                "Estimated wind exceeds any practical shooting condition (>${MAX_CREDIBLE_WIND_MPS.toInt()} m/s) — " +
                "check camera FOV, boresight calibration and shot-break time. Falling back to a zero-wind solution."
            )
            crossMps = 0.0; vertMps = 0.0
        } else if (ENFORCE_MIN_CONFIDENCE && windConf < MIN_USABLE_CONFIDENCE) {
            warnings.add(
                "Confidence ${(windConf * 100).toInt()}% below threshold (${(MIN_USABLE_CONFIDENCE * 100).toInt()}%) — zero-wind solution."
            )
            crossMps = 0.0; vertMps = 0.0
        } else {
            if (windConf < MIN_USABLE_CONFIDENCE) {
                warnings.add(
                    "Confidence ${(windConf * 100).toInt()}% below threshold (${(MIN_USABLE_CONFIDENCE * 100).toInt()}%)."
                )
            }
            if (abs(crossMps) > STRONG_WIND_MPS) {
                warnings.add(
                    "Estimated crosswind ${"%.1f".format(abs(crossMps))} m/s is unusually strong for " +
                    "practical shooting — verify it matches conditions at the range before dialling."
                )
            }
            if (windConf < 0.15) {
                warnings.add("Low confidence — use wind estimate with caution.")
            }
        }

        val targetDistanceM = targetDistanceYd * 0.9144
        val sightHeightM = effectiveSightHeightM(rifle, scope)

        // Muzzle velocity at the temperature actually measured. BulletProfile
        // has carried this correction since the temperature coefficient was
        // added, but only the capture screen ever called it — so the firing
        // solution, the one number a shooter dials, was computed at the
        // load's reference temperature no matter what the Kestrel said.
        //
        // TWO bullets on purpose. The zero was established in the past, at
        // the load's reference temperature, and the scope has not moved
        // since; so the launch angle must be solved with the ORIGINAL muzzle
        // velocity. Today's colder or hotter velocity applies only to the
        // shot about to be fired. Solving both with the adjusted figure
        // would re-zero the rifle in software every time the weather
        // changed, and cancel most of the very effect being modelled.
        val firedBullet = bullet
            .adjustedForTemperature(atmosphere.temperatureC)
            .adjustedForBarrel(rifle.barrelLengthIn)
        val zeroDistanceM = rifle.zeroDistanceM

        val pitch = BallisticsEngine.solveZeroPitch(bullet, atmosphere, zeroDistanceM, sightHeightM)
        val uniformWind = Vec3(0.0, vertMps, crossMps)

        // NOTE: wind must be a NAMED argument — it is not the last parameter
        // of simulate() (sampleEveryS is), so a trailing lambda mis-binds.
        val traj = BallisticsEngine.simulate(
            firedBullet, atmosphere, pitch, 0.0, targetDistanceM + 1.0,
            wind = { _, _ -> uniformWind }
        )
        val atTarget = traj.lastOrNull { it.position.x <= targetDistanceM } ?: traj.last()
        val reachedTarget = atTarget.position.x >= targetDistanceM * 0.95
        if (!reachedTarget) {
            warnings.add("Simulated trajectory fell short of the target — check bullet profile / target distance.")
        }

        // ---- corrections the point-mass integrator cannot produce ----
        //
        // Applied to the IMPACT POINT rather than to the correction, so they
        // pass through the same miss-to-angle conversion as everything else
        // and cannot disagree with it about signs or units.
        val tofS = atTarget.timeS
        val rangeAtImpactM = atTarget.position.x

        val sg = DriftCorrections.gyroscopicStability(firedBullet, rifle, atmosphere)
        val spinDriftM = DriftCorrections.spinDriftM(sg, tofS, rifle.rightHandTwist)

        val coriolis = if (geometry.latitudeDeg != null)
            DriftCorrections.coriolisM(
                geometry.latitudeDeg, geometry.firingAzimuthDeg, rangeAtImpactM, tofS)
        else DriftCorrections.Coriolis(0.0, 0.0)

        val cant = DriftCorrections.cantErrorM(geometry.cantDeg, pitch, rangeAtImpactM)

        // Line of sight is level and starts sightHeightM above the bore.
        val verticalMissM = atTarget.position.y - sightHeightM + coriolis.verticalM + cant.verticalM
        val lateralMissM = atTarget.position.z + spinDriftM + coriolis.lateralM + cant.lateralM

        // Say so when a term is large enough to matter and the app had to
        // leave it out. Silence about a missing input reads exactly like a
        // correct answer.
        if (sg > 0.0 && sg < 1.4 && tofS > 0.3) {
            warnings.add(
                "Gyroscopic stability is only ${fmt(sg)} for this bullet and twist — " +
                "below about 1.4 the bullet is marginally stable, its drag is higher than " +
                "the model assumes, and the drift figure is unreliable."
            )
        }
        if (geometry.latitudeDeg == null && rangeAtImpactM > 600.0) {
            warnings.add(
                "Coriolis is not applied — the app has no position. Beyond 600 m this is " +
                "worth several centimetres; set the range position in Settings."
            )
        } else if (geometry.latitudeDeg != null && geometry.firingAzimuthDeg == null &&
                   rangeAtImpactM > 600.0) {
            warnings.add(
                "Firing direction unknown, so the vertical part of Coriolis is left out. " +
                "It shoots high to the east and low to the west, and cancels north and south."
            )
        }
        if (DriftCorrections.cantWorthReporting(geometry.cantDeg, pitch, rangeAtImpactM)) {
            warnings.add(
                "Rifle cant of ${fmt(geometry.cantDeg)}° is included: it moves this shot " +
                "${fmt(abs(cant.lateralM) * 100.0)} cm " +
                (if (cant.lateralM >= 0) "right" else "left") + " and " +
                "${fmt(abs(cant.verticalM) * 100.0)} cm low. Levelling the rifle removes it."
            )
        }

        // Linear miss at range -> angular correction (opposite to the miss),
        // first in MOA, then into the scope's own unit.
        val rangeM = atTarget.position.x.coerceAtLeast(1.0)
        val elevationMoa = radToMoa(atan(verticalMissM / rangeM)) * -1.0
        val windageMoa = radToMoa(atan(lateralMissM / rangeM)) * -1.0

        val moaPerScopeUnit = if (scope.clickUnitIsMoa) 1.0 else MRAD_TO_MOA
        val windageScope = windageMoa / moaPerScopeUnit
        val elevationScope = elevationMoa / moaPerScopeUnit
        val unitLabel = if (scope.clickUnitIsMoa) "MOA" else "MRAD"

        // Practicality: turret travel specs are TOTAL range; from an
        // optically-centred zero roughly half is available in each direction.
        val windageHalfTravelMoa = scope.maxWindageTravelMoa / 2.0
        val elevationHalfTravelMoa = scope.maxElevationTravelMoa / 2.0
        if (abs(windageMoa) > windageHalfTravelMoa) {
            warnings.add(
                "Windage correction (${fmt(abs(windageScope))} $unitLabel) exceeds the scope's usable travel " +
                "(±${fmt(windageHalfTravelMoa / moaPerScopeUnit)} $unitLabel from centre) — hold off instead."
            )
        }
        if (abs(elevationMoa) > elevationHalfTravelMoa) {
            warnings.add(
                "Elevation correction (${fmt(abs(elevationScope))} $unitLabel) exceeds the scope's usable travel " +
                "(±${fmt(elevationHalfTravelMoa / moaPerScopeUnit)} $unitLabel from centre) — hold over instead."
            )
        }

        val clickMoa = if (scope.clickUnitIsMoa) scope.clickValue else scope.clickValue * MRAD_TO_MOA

        return ScopeAdjustment(
            windageScopeUnits = windageScope,
            elevationScopeUnits = elevationScope,
            scopeUnitLabel = unitLabel,
            windageClicks = Math.round(windageMoa / clickMoa).toInt(),
            elevationClicks = Math.round(elevationMoa / clickMoa).toInt(),
            windageDirection = if (windageMoa >= 0) "RIGHT" else "LEFT",
            elevationDirection = if (elevationMoa >= 0) "UP" else "DOWN",
            estimatedCrosswindMps = crossMps,
            estimatedVerticalWindMps = vertMps,
            windConfidence = windConf,
            crosswindStdMps = crossSd,
            verticalWindStdMps = vertSd,
            impactOffsetMAtTarget = Vec3(0.0, verticalMissM, lateralMissM),
            warnings = warnings,
            valid = reachedTarget
        )
    }

    /** The scope profile owns the optical-centerline height; fall back to
     *  the rifle's legacy sightHeightIn if a profile predates the field. */
    fun effectiveSightHeightM(rifle: RifleProfile, scope: ScopeProfile): Double =
        (if (scope.heightAboveBarrelIn > 0) scope.heightAboveBarrelIn else rifle.sightHeightIn) * 0.0254

    private fun radToMoa(rad: Double) = Math.toDegrees(rad) * 60.0
    private fun fmt(v: Double) = String.format("%.1f", v)
}
