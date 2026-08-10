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

    /**
     * Values NK uses for "not measured". A range decoded from one of these is
     * pure fiction — and it happened: a field log locked onto 3276.9 m, which
     * is 0x8001 scaled by 0.1, straight out of an empty weather field.
     */
    private fun isSentinel(raw: Int) = raw == 0xFFFF || raw == 0x8000 || raw == 0x8001

    /**
     * Kestrel LiNK characteristics that carry WEATHER, not range. They update
     * every few seconds with temperature, pressure, density altitude and the
     * rest, so a "plausible distance" can always be found in them — which is
     * exactly how a wandering 1600-2400 m reading was produced while the
     * target sat at 101 m. The range, when a FIRE4000 is linked, is not here.
     */
    private val WEATHER_CHARS = setOf(
        "03290300", "03290310", "03290320", "03290330", "03290340",
        "03290350", "03290360", "03290370", "03290380", "03290200",
        "00002a19" // battery level
    )

    private fun isWeather(uuid: java.util.UUID): Boolean {
        val head = uuid.toString().substringBefore('-')
        return WEATHER_CHARS.contains(head)
    }

    /** Raw values seen on each characteristic before anything was ranged. A
     *  range APPEARS when the shooter ranges; a value already sitting there is
     *  a standing measurement, not a range. */
    private val baseline = HashMap<String, MutableSet<Int>>()
    /** Last candidate per characteristic, so a value must repeat before it is
     *  believed — one frame of noise is not a measurement. */
    private val pending = HashMap<String, Int>()

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
        baseline.clear(); pending.clear()
        val startedAt = System.currentTimeMillis()
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
                val uuid = ch.uuid.toString()
                Logger.i(TAG, "frame ${ch.uuid}: ${v.joinToString(" ") { "%02X".format(it) }}")

                if (isWeather(ch.uuid)) return   // weather, never a range

                val lockUuid = DistanceConfig.lockedUuid(context)
                val lockScale = DistanceConfig.lockedScale(context)
                if (lockUuid != null && lockScale > 0.0) {
                    if (uuid != lockUuid) return
                    val raw = firstRaw(v) ?: return
                    val m = raw * lockScale
                    if (DistanceConfig.plausible(m)) {
                        Logger.i(TAG, "distance (locked): ${"%.1f".format(m)} m")
                        handler.post { onDistance(m) }
                    }
                    return
                }

                val hit = decodeCandidate(v) ?: return
                val (metres, scale, raw) = hit

                // For the first few seconds everything arriving is treated as
                // the standing state, not as a range.
                val seen = baseline.getOrPut(uuid) { HashSet() }
                if (System.currentTimeMillis() - startedAt < 4000) { seen.add(raw); return }
                if (seen.contains(raw)) return          // unchanged since connect
                if (pending[uuid] != raw) { pending[uuid] = raw; return }  // needs to repeat

                Logger.i(TAG, "distance candidate: ${"%.1f".format(metres)} m (raw=$raw scale=$scale) on $uuid")
                handler.post { onCandidate(metres, uuid, scale) }
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
        val raw = (v[0].toInt() and 0xFF) or ((v[1].toInt() and 0xFF) shl 8)
        return if (isSentinel(raw)) null else raw
    }

    /** Candidate range, the scale that produced it, and the raw value — for
     *  confirmation and for the change/stability tests. Sentinels are refused. */
    fun decodeCandidate(v: ByteArray): Triple<Double, Double, Int>? {
        for (i in 0..v.size - 2) {
            val le = (v[i].toInt() and 0xFF) or ((v[i + 1].toInt() and 0xFF) shl 8)
            val be = ((v[i].toInt() and 0xFF) shl 8) or (v[i + 1].toInt() and 0xFF)
            for (raw in listOf(le, be)) {
                if (isSentinel(raw)) continue
                for ((scale, _) in SCALES) {
                    val m = raw * scale
                    if (DistanceConfig.plausible(m)) return Triple(m, scale, raw)
                }
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
