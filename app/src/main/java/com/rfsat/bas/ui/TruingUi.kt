package com.rfsat.bas.ui

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import com.rfsat.bas.profiles.BulletProfile
import com.rfsat.bas.profiles.RifleProfile
import com.rfsat.bas.profiles.TruingStore

/**
 * The truing screen, built as a dialog rather than an Activity because it is
 * read-mostly: a list of what has been measured, one sentence about the fit,
 * and three decisions.
 */
object TruingUi {

    private fun describe(
        a: Activity, rifle: RifleProfile, bullet: BulletProfile
    ): String {
        val obs = TruingStore.observations(a, rifle, bullet)
        val overlay = TruingStore.overlay(a, rifle, bullet)
        return buildString {
            appendLine("${rifle.name}")
            appendLine("${bullet.name}")
            appendLine()
            if (obs.isEmpty()) {
                appendLine("No groups recorded yet.")
                appendLine()
                appendLine("Shoot a group of at least three at a known distance, score it, then")
                appendLine("come back here and record it. Velocity is fitted from groups inside")
                appendLine("${Truing_SPLIT} m and drag from groups beyond it, so two distances —")
                appendLine("one near, one far — tell the app far more than four at one distance.")
            } else {
                appendLine("Recorded groups:")
                for (o in obs) {
                    appendLine("  %5.0f m   %+6.0f mm   %d shots"
                        .format(o.distanceM, -o.dropMm, o.shotCount))
                }
                appendLine()
                if (overlay == null) {
                    appendLine("Not fitted yet.")
                } else {
                    appendLine("Fitted: ${overlay.summary}")
                    appendLine("  catalogue %.0f fps  ->  %.0f fps"
                        .format(overlay.catalogueMuzzleVelocityFps, overlay.muzzleVelocityFps))
                    appendLine("  drag %.3f of the reference curve"
                        .format(overlay.dragCalibrationFactor))
                    appendLine("  typical error left over: %.0f mm".format(overlay.residualMm))
                    appendLine()
                    appendLine("This applies to this rifle with this load only. The ammunition")
                    appendLine("entry itself is unchanged.")
                }
            }
        }
    }

    private const val Truing_SPLIT = 500

    fun show(
        a: Activity,
        rifle: RifleProfile,
        bullet: BulletProfile,
        sightHeightM: Double,
        onChanged: () -> Unit
    ) {
        val obs = TruingStore.observations(a, rifle, bullet)
        val b = AlertDialog.Builder(a)
            .setTitle("Truing")
            .setMessage(describe(a, rifle, bullet))
            .setPositiveButton("Record this group") { _, _ ->
                val rec = TruingStore.recordCurrentGroup(a, rifle, bullet)
                val msg = if (rec == null)
                    "Nothing to record — truing needs a scored group of at least three shots " +
                    "at a known distance."
                else
                    "Recorded %d shots at %.0f m.".format(rec.shotCount, rec.distanceM)
                AlertDialog.Builder(a).setMessage(msg)
                    .setPositiveButton("OK") { _, _ -> show(a, rifle, bullet, sightHeightM, onChanged) }
                    .show()
                onChanged()
            }
            .setNegativeButton("Close", null)

        if (obs.isNotEmpty()) {
            b.setNeutralButton("Fit now") { _, _ ->
                val r = TruingStore.fit(a, rifle, bullet, sightHeightM)
                val text = buildString {
                    appendLine(r.summary)
                    appendLine()
                    appendLine("Typical error left over: %.0f mm".format(r.residualM * 1000.0))
                    if (r.warnings.isNotEmpty()) {
                        appendLine()
                        for (w in r.warnings) appendLine("• $w")
                    }
                }
                AlertDialog.Builder(a)
                    .setTitle("Fitted")
                    .setMessage(text)
                    .setPositiveButton("Keep it") { _, _ -> onChanged() }
                    .setNegativeButton("Discard") { _, _ ->
                        TruingStore.clearOverlay(a, rifle, bullet); onChanged()
                    }
                    .show()
            }
        }
        b.show()
    }
}
