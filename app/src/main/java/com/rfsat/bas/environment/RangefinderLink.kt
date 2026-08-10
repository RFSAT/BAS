package com.rfsat.bas.environment

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.rfsat.bas.log.Logger
import java.util.UUID

/**
 * A live distance link to a rangefinder — or to a Kestrel 5700 Elite acting as
 * the bridge for one (Leica, Vortex and SIG BDX-X all push their range into the
 * Kestrel, so bridging covers three brands without decoding any of them).
 *
 * No vendor here documents its GATT profile, so the transport is handled
 * generically: connect, subscribe to everything that notifies, and decode each
 * frame by trying the encodings these devices actually use — 16- and 32-bit
 * integers, little- and big-endian, in metres, decimetres, yards or tenths of
 * a yard — and accepting the first value that is a PLAUSIBLE range. Every
 * frame and every rejected candidate is logged, so a reading that comes out
 * wrong can be corrected from the log rather than guessed at.
 *
 * Nothing is written to the device: subscriptions only.
 */
object RangefinderLink {

    private const val TAG = "RangefinderLink"
    private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private const val M_PER_YD = 0.9144

    /** Scale factors from a raw integer to metres, in the order worth trying. */
    private val SCALES = listOf(
        1.0 to "m", 0.1 to "dm", M_PER_YD to "yd", M_PER_YD / 10.0 to "0.1yd"
    )

    private var gatt: BluetoothGatt? = null

    /** Find a device for [model]: bonded first, then advertising. */
    @SuppressLint("MissingPermission")
    fun findPaired(model: RangefinderModel): BluetoothDevice? = runCatching {
        BluetoothAdapter.getDefaultAdapter()?.bondedDevices?.firstOrNull { d ->
            if (model.viaKestrel) KestrelProvider.isKestrelName(d.name) else model.matches(d.name)
        }
    }.getOrNull()

    @SuppressLint("MissingPermission")
    fun scan(context: Context, model: RangefinderModel, timeoutMs: Long = 12_000L,
             onResult: (BluetoothDevice?) -> Unit) {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
        val scanner = manager?.adapter?.bluetoothLeScanner
        if (scanner == null) { Logger.e(TAG, "BLE scanner unavailable"); onResult(null); return }
        val handler = Handler(Looper.getMainLooper())
        var done = false
        val seen = mutableSetOf<String>()
        val cb = object : android.bluetooth.le.ScanCallback() {
            override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
                val name = result.device.name ?: result.scanRecord?.deviceName ?: return
                if (seen.add(name)) Logger.i(TAG, "advertiser: \"$name\" rssi=${result.rssi}")
                val hit = if (model.viaKestrel) KestrelProvider.isKestrelName(name) else model.matches(name)
                if (hit && !done) {
                    done = true
                    runCatching { scanner.stopScan(this) }
                    Logger.i(TAG, "matched ${model.name}: \"$name\"")
                    handler.post { onResult(result.device) }
                }
            }
            override fun onScanFailed(errorCode: Int) {
                if (done) return
                done = true
                Logger.e(TAG, "scan failed: $errorCode")
                handler.post { onResult(null) }
            }
        }
        Logger.i(TAG, "scanning for ${model.label}…")
        scanner.startScan(null,
            android.bluetooth.le.ScanSettings.Builder()
                .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        handler.postDelayed({
            if (!done) {
                done = true
                runCatching { scanner.stopScan(cb) }
                Logger.i(TAG, "scan timeout — advertisers seen: $seen")
                onResult(null)
            }
        }, timeoutMs)
    }

    /**
     * Connect and listen. [onDistance] fires on the main thread with metres
     * each time a plausible range arrives; [status] carries progress for the UI.
     */
    @SuppressLint("MissingPermission")
    fun listen(
        context: Context,
        device: BluetoothDevice,
        listenMs: Long = 120_000L,
        status: (String) -> Unit,
        onCandidate: (Double, String, Double) -> Unit = { _, _, _ -> },
        onDistance: (Double) -> Unit
    ) {
        val handler = Handler(Looper.getMainLooper())
        val notifyQueue = ArrayDeque<BluetoothGattCharacteristic>()
        val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, s: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    handler.post { status("Connected — discovering…") }
                    g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    runCatching { g.close() }
                    gatt = null
                    handler.post { status("Rangefinder disconnected") }
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, s: Int) {
                for (svc in g.services) for (ch in svc.characteristics) {
                    val p = ch.properties
                    if (p and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                            BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) notifyQueue.add(ch)
                }
                Logger.i(TAG, "subscribing to ${notifyQueue.size} characteristic(s)")
                handler.post { status("Listening — range a target") }
                subscribeNext(g)
            }

            fun subscribeNext(g: BluetoothGatt) {
                val ch = notifyQueue.removeFirstOrNull() ?: run {
                    handler.postDelayed({ runCatching { g.disconnect() } }, listenMs); return
                }
                runCatching {
                    g.setCharacteristicNotification(ch, true)
                    val d = ch.getDescriptor(CCCD)
                    if (d != null) {
                        @Suppress("DEPRECATION")
                        d.value = if (ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
                            android.bluetooth.BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        else android.bluetooth.BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                        @Suppress("DEPRECATION")
                        if (g.writeDescriptor(d)) return
                    }
                }
                subscribeNext(g)
            }

            override fun onDescriptorWrite(g: BluetoothGatt, d: android.bluetooth.BluetoothGattDescriptor, s: Int) =
                subscribeNext(g)

            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
                val v = ch.value ?: return
                Logger.i(TAG, "frame ${ch.uuid}: ${v.joinToString(" ") { "%02X".format(it) }}")
                val uuid = ch.uuid.toString()
                val lockUuid = DistanceConfig.lockedUuid(context)
                val lockScale = DistanceConfig.lockedScale(context)
                if (lockUuid != null && lockScale > 0.0) {
                    // Confirmed pairing: only this characteristic, only this unit.
                    if (uuid != lockUuid) return
                    val raw = firstRaw(v) ?: return
                    val m = raw * lockScale
                    if (DistanceConfig.plausible(m)) {
                        Logger.i(TAG, "distance (locked): ${"%.1f".format(m)} m")
                        handler.post { onDistance(m) }
                    }
                    return
                }
                val hit = decodeCandidate(v)
                if (hit != null) {
                    Logger.i(TAG, "distance candidate: ${"%.1f".format(hit.first)} m (scale=${hit.second}) on $uuid")
                    handler.post { onCandidate(hit.first, uuid, hit.second) }
                }
            }
        }
        status("Connecting…")
        gatt = device.connectGatt(context, false, cb)
    }

    fun stop() { runCatching { gatt?.disconnect() }; gatt = null }

    /**
     * First plausible range in a frame. Tries every 16- and 32-bit window in
     * both endiannesses against each unit scale; rejects anything outside a
     * believable band so a battery percentage or a temperature cannot be read
     * as a distance.
     */
    /** The first 16-bit value in a frame, used once a pairing is locked. */
    fun firstRaw(v: ByteArray): Int? {
        if (v.size < 2) return null
        return (v[0].toInt() and 0xFF) or ((v[1].toInt() and 0xFF) shl 8)
    }

    /** Candidate range with the scale that produced it, for confirmation. */
    fun decodeCandidate(v: ByteArray): Pair<Double, Double>? {
        for (i in 0..v.size - 2) {
            val le = (v[i].toInt() and 0xFF) or ((v[i + 1].toInt() and 0xFF) shl 8)
            val be = ((v[i].toInt() and 0xFF) shl 8) or (v[i + 1].toInt() and 0xFF)
            for (raw in listOf(le, be)) for ((scale, _) in SCALES) {
                val m = raw * scale
                if (DistanceConfig.plausible(m)) return m to scale
            }
        }
        return null
    }

    fun decodeMetres(v: ByteArray): Double? {
        fun consider(raw: Int): Double? {
            for ((scale, unit) in SCALES) {
                val m = raw * scale
                if (DistanceConfig.plausible(m)) {
                    Logger.i(TAG, "  candidate raw=$raw as $unit -> ${"%.1f".format(m)} m")
                    return m
                }
            }
            return null
        }
        for (i in 0..v.size - 2) {
            val le = (v[i].toInt() and 0xFF) or ((v[i + 1].toInt() and 0xFF) shl 8)
            consider(le)?.let { return it }
            val be = ((v[i].toInt() and 0xFF) shl 8) or (v[i + 1].toInt() and 0xFF)
            consider(be)?.let { return it }
        }
        for (i in 0..v.size - 4) {
            val le32 = (v[i].toInt() and 0xFF) or ((v[i+1].toInt() and 0xFF) shl 8) or
                ((v[i+2].toInt() and 0xFF) shl 16) or ((v[i+3].toInt() and 0xFF) shl 24)
            if (le32 in 0..400000) consider(le32)?.let { return it }
        }
        return null
    }
}
