package com.rfsat.bas.environment

import android.app.Activity
import androidx.appcompat.app.AlertDialog

/**
 * Shared rangefinder UI: pick the model, then read a distance from it — used
 * identically by the Ballistics and Scoring screens and by Settings, so the
 * shooter meets one flow wherever distance is set.
 */
object RangefinderUi {

    fun chooseModel(activity: Activity, current: RangefinderModel, onChosen: (RangefinderModel) -> Unit) {
        val models = RangefinderModel.values()
        AlertDialog.Builder(activity)
            .setTitle("Rangefinder")
            .setSingleChoiceItems(models.map { it.label }.toTypedArray(), models.indexOf(current)) { d, w ->
                d.dismiss(); onChosen(models[w])
            }
            .show()
    }

    /** BLE permissions needed before any scan/connect. Returns the missing ones. */
    fun missingPermissions(activity: Activity): Array<String> {
        val needed = if (android.os.Build.VERSION.SDK_INT >= 31) arrayOf(
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.BLUETOOTH_CONNECT
        ) else arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
        return needed.filter {
            activity.checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
    }

    /**
     * Connect to [model] and report each range. [onMetres] fires per reading;
     * [status] carries progress. Falls back with a clear message rather than
     * silence when nothing is found — typing the distance always remains open.
     */
    fun readDistance(
        activity: Activity,
        model: RangefinderModel,
        status: (String) -> Unit,
        onMetres: (Double) -> Unit
    ) {
        if (model == RangefinderModel.MANUAL) { status("Set to enter distance by hand."); return }
        fun go(device: android.bluetooth.BluetoothDevice) {
            RangefinderLink.listen(
                activity, device, status = status,
                onCandidate = { m, uuid, scale ->
                    // First time on this device: the decoder cannot tell a range
                    // from a temperature by the number alone, so ask once and
                    // remember the answer. Every later reading is then read from
                    // that characteristic at that scale, with no guessing.
                    AlertDialog.Builder(activity)
                        .setTitle("Is this the range?")
                        .setMessage("The rangefinder sent a value that reads as " +
                            "${String.format("%.0f", m)} m. Confirm it matches the display and BAS will " +
                            "use this signal from now on.")
                        .setPositiveButton("Yes, use it") { _, _ ->
                            DistanceConfig.setLock(activity, uuid, scale)
                            DistanceConfig.setLastMetres(activity, m)
                            onMetres(m)
                        }
                        .setNegativeButton("No, keep listening", null)
                        .show()
                },
                onDistance = { m ->
                    DistanceConfig.setLastMetres(activity, m)
                    onMetres(m)
                })
        }
        val bonded = RangefinderLink.findPaired(model)
        if (bonded != null) { go(bonded); return }
        status("Scanning for ${model.label}…")
        RangefinderLink.scan(activity, model) { device ->
            if (device == null)
                status("No ${model.label} found — check it is on and paired. Enter the distance by hand for now.")
            else go(device)
        }
    }
}
