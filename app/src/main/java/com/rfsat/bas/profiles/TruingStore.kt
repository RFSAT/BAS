package com.rfsat.bas.profiles

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.rfsat.bas.ballistics.Truing
import com.rfsat.bas.log.Logger

/**
 * Measured groups, and the fit derived from them, kept PER RIFLE AND LOAD.
 *
 * Truing absorbs this barrel, this lot of ammunition and this shooter's
 * consistent hold. None of that belongs in a catalogue entry shared across
 * every profile, so nothing here writes back into one. The catalogue keeps
 * saying 2600 fps for ever; the overlay says "in this rifle, with this load,
 * it behaves like 2455", and the two are shown together so the shooter can
 * see which is which and throw the overlay away without losing the original.
 *
 * The key is the rifle name and the ammunition name. Rename either and the
 * overlay stops applying — which is the correct behaviour, because a renamed
 * load is usually a different load.
 */
object TruingStore {

    private const val PREFS = "bas_truing"
    private const val KEY_OBS = "observations"
    private const val KEY_OVERLAYS = "overlays"
    private val gson = Gson()

    /** One recorded group: where it landed relative to the point of aim. */
    data class Observation(
        val key: String,
        val distanceM: Double,
        /** Positive DOWN, millimetres, group centre relative to point of aim. */
        val dropMm: Double,
        val shotCount: Int,
        val recordedAtMs: Long
    )

    /** The fit, and enough of its provenance to judge it later. */
    data class Overlay(
        val key: String,
        val muzzleVelocityFps: Double,
        val dragCalibrationFactor: Double,
        val catalogueMuzzleVelocityFps: Double,
        val summary: String,
        val residualMm: Double,
        val observationCount: Int,
        val fittedAtMs: Long
    )

    fun keyFor(rifle: RifleProfile, bullet: BulletProfile): String =
        "${rifle.name}|${bullet.name}"

    // ------------------------------------------------------------- storage

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun allObservations(c: Context): MutableList<Observation> {
        val raw = prefs(c).getString(KEY_OBS, null) ?: return mutableListOf()
        val type = object : TypeToken<MutableList<Observation>>() {}.type
        return runCatching { gson.fromJson<MutableList<Observation>>(raw, type) }
            .getOrNull() ?: mutableListOf()
    }

    private fun saveObservations(c: Context, list: List<Observation>) {
        prefs(c).edit().putString(KEY_OBS, gson.toJson(list)).apply()
    }

    private fun allOverlays(c: Context): MutableMap<String, Overlay> {
        val raw = prefs(c).getString(KEY_OVERLAYS, null) ?: return mutableMapOf()
        val type = object : TypeToken<MutableMap<String, Overlay>>() {}.type
        return runCatching { gson.fromJson<MutableMap<String, Overlay>>(raw, type) }
            .getOrNull() ?: mutableMapOf()
    }

    private fun saveOverlays(c: Context, map: Map<String, Overlay>) {
        prefs(c).edit().putString(KEY_OVERLAYS, gson.toJson(map)).apply()
    }

    // -------------------------------------------------------- observations

    fun observations(c: Context, rifle: RifleProfile, bullet: BulletProfile): List<Observation> {
        val k = keyFor(rifle, bullet)
        return allObservations(c).filter { it.key == k }.sortedBy { it.distanceM }
    }

    /**
     * Records the group currently on the scoring screen.
     *
     * Returns null when there is nothing worth recording. THREE SHOTS IS THE
     * MINIMUM and it is not arbitrary: the fit is only as good as the group
     * centre, and the centre of one or two shots is not a measurement of
     * anything. A bad observation does not make truing noisy, it makes it
     * confidently wrong for every distance.
     */
    fun recordCurrentGroup(c: Context, rifle: RifleProfile, bullet: BulletProfile): Observation? {
        val session = com.rfsat.bas.scoring.ScoringSession
        val group = runCatching { session.group() }.getOrNull() ?: return null
        if (group.shotCount < 3) return null
        val distanceM = session.state.distanceM
        if (distanceM <= 0.0) return null

        // Target-plane millimetres: y is UP on the card, and drop is positive
        // DOWN, so the sign flips here and nowhere else.
        val dropMm = -(group.mpiYMm - session.state.poaYMm)

        val obs = Observation(
            key = keyFor(rifle, bullet),
            distanceM = distanceM,
            dropMm = dropMm,
            shotCount = group.shotCount,
            recordedAtMs = System.currentTimeMillis()
        )
        val all = allObservations(c)
        // One observation per distance per load: a later group at the same
        // distance is a better measurement, not a second vote.
        all.removeAll { it.key == obs.key && Math.abs(it.distanceM - obs.distanceM) < 1.0 }
        all.add(obs)
        saveObservations(c, all)
        Logger.i("TruingStore", "Recorded ${group.shotCount} shots at ${distanceM} m, drop ${dropMm} mm")
        return obs
    }

    fun forget(c: Context, obs: Observation) {
        saveObservations(c, allObservations(c).filterNot {
            it.key == obs.key && it.recordedAtMs == obs.recordedAtMs
        })
    }

    fun clearAll(c: Context, rifle: RifleProfile, bullet: BulletProfile) {
        val k = keyFor(rifle, bullet)
        saveObservations(c, allObservations(c).filterNot { it.key == k })
        saveOverlays(c, allOverlays(c).filterKeys { it != k })
    }

    // --------------------------------------------------------------- fit

    fun fit(
        c: Context,
        rifle: RifleProfile,
        bullet: BulletProfile,
        sightHeightM: Double
    ): Truing.Result {
        val obs = observations(c, rifle, bullet).map {
            Truing.DropObservation(it.distanceM, it.dropMm / 1000.0)
        }
        // Fit from the velocity the rifle actually produces, not the box
        // figure — otherwise the barrel-length correction is refitted as if
        // it were an error, and the overlay hides a known quantity inside an
        // inferred one.
        val start = bullet.adjustedForBarrel(rifle.barrelLengthIn)
        val result = Truing.trueProfile(start, rifle, sightHeightM, obs)
        if (obs.isNotEmpty()) {
            val overlays = allOverlays(c)
            overlays[keyFor(rifle, bullet)] = Overlay(
                key = keyFor(rifle, bullet),
                muzzleVelocityFps = result.trued.muzzleVelocityFps,
                dragCalibrationFactor = result.trued.dragCalibrationFactor,
                catalogueMuzzleVelocityFps = bullet.muzzleVelocityFps,
                summary = result.summary,
                residualMm = result.residualM * 1000.0,
                observationCount = obs.size,
                fittedAtMs = System.currentTimeMillis()
            )
            saveOverlays(c, overlays)
        }
        return result
    }

    fun overlay(c: Context, rifle: RifleProfile, bullet: BulletProfile): Overlay? =
        allOverlays(c)[keyFor(rifle, bullet)]

    fun clearOverlay(c: Context, rifle: RifleProfile, bullet: BulletProfile) {
        saveOverlays(c, allOverlays(c).filterKeys { it != keyFor(rifle, bullet) })
    }

    /**
     * The profile to actually shoot with: the catalogue entry, overlaid with
     * whatever this rifle and load were measured to do. Returns the bullet
     * untouched when nothing has been fitted, so every caller can apply this
     * unconditionally.
     */
    fun applied(c: Context, rifle: RifleProfile, bullet: BulletProfile): BulletProfile {
        val o = allOverlays(c)[keyFor(rifle, bullet)] ?: return bullet
        return bullet.copy(
            muzzleVelocityFps = o.muzzleVelocityFps,
            dragCalibrationFactor = o.dragCalibrationFactor,
            // AND the test barrel becomes this barrel. The fitted velocity
            // was measured THROUGH this rifle, so it already contains the
            // barrel-length correction; leaving the catalogue test length in
            // place would make the solver apply that correction a second
            // time and take another 100 fps off a figure that was already
            // right. Saying the test barrel is this barrel makes
            // adjustedForBarrel a no-op, which is exactly true of a
            // velocity that came from this barrel.
            testBarrelIn = rifle.barrelLengthIn
        )
    }
}
