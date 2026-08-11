package com.rfsat.bas.environment

import android.annotation.SuppressLint
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
 * The Kestrel's BALLISTICS side: its gun profiles, and the firing solution it
 * computes itself.
 *
 * Deliberately READ-ONLY. The LiNK protocol is not published, and a blind
 * write to a meter that holds the shooter's own profiles could silently
 * corrupt them — so BAS reads what the Kestrel exposes, learns the layout from
 * it, and only then could a write be written safely. Reading the gun list
 * first is also the cheapest way to discover the record format.
 *
 * The ballistics characteristics from a 5700AL-R:
 *
 *     03290101  read/write/notify   ballistic profile block
 *     03290102  read/write          (zero until a profile is active)
 *     03290103  read/write
 *     03290104  read/write          device clock (decoded, see KestrelProvider)
 *     03290105  read/write/notify
 *     03290106  read/write
 *     03290107  notify only         solution stream
 *     85920100  read/notify         secondary service
 */
object KestrelBallistics {

    private const val TAG = "KestrelBallistics"
    private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val BALLISTIC_CHARS = listOf(
        "03290101", "03290102", "03290103", "03290105", "03290106", "03290107", "85920100")

    private fun isBallistic(u: UUID) = BALLISTIC_CHARS.contains(u.toString().substringBefore('-'))

    data class GunProfile(val name: String, val raw: String)

    /** Elevation and windage the METER computed, in its own units. */
    data class KestrelSolution(
        val elevationMoa: Double?, val windageMoa: Double?, val source: String = "Kestrel")

    @Volatile var lastSolution: KestrelSolution? = null
        private set

    /**
     * Connect, read every ballistics characteristic, pull any printable names
     * out of them, and listen for [listenMs] so a solution computed on the
     * meter arrives too. Everything is logged verbatim: on a meter whose
     * ballistics screen has never been opened these blocks are all zeros, and
     * that is itself the answer — it says the profiles live behind a request
     * BAS does not yet know how to make.
     */
    @SuppressLint("MissingPermission")
    fun read(
        context: Context,
        device: BluetoothDevice,
        listenMs: Long = 45_000L,
        status: (String) -> Unit,
        onProfiles: (List<GunProfile>) -> Unit
    ) {
        val handler = Handler(Looper.getMainLooper())
        val readQueue = ArrayDeque<BluetoothGattCharacteristic>()
        val notifyQueue = ArrayDeque<BluetoothGattCharacteristic>()
        val found = mutableListOf<GunProfile>()

        val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, s: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    handler.post { status("Connected — reading profiles…") }
                    g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) runCatching { g.close() }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, s: Int) {
                for (svc in g.services) for (ch in svc.characteristics) {
                    if (!isBallistic(ch.uuid)) continue
                    if (ch.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) readQueue.add(ch)
                    if (ch.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                            BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) notifyQueue.add(ch)
                }
                Logger.i(TAG, "ballistics: ${readQueue.size} readable, ${notifyQueue.size} notifying")
                next(g)
            }

            fun next(g: BluetoothGatt) {
                val ch = readQueue.removeFirstOrNull() ?: run { subscribe(g); return }
                if (!g.readCharacteristic(ch)) next(g)
            }

            @Suppress("DEPRECATION")
            override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, s: Int) {
                harvest(ch.uuid, ch.value, found)
                next(g)
            }

            fun subscribe(g: BluetoothGatt) {
                val ch = notifyQueue.removeFirstOrNull() ?: run {
                    handler.post {
                        status(if (found.isEmpty())
                            "No gun profiles exposed — open the Kestrel's ballistics screen and try again (all blocks read as zeros; the Log has the detail)."
                        else "Found ${found.size} profile(s) on the meter.")
                        onProfiles(found.toList())
                    }
                    handler.postDelayed({ runCatching { g.disconnect() } }, listenMs)
                    return
                }
                runCatching {
                    g.setCharacteristicNotification(ch, true)
                    val d = ch.getDescriptor(CCCD)
                    if (d != null) {
                        @Suppress("DEPRECATION")
                        d.value = android.bluetooth.BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        if (g.writeDescriptor(d)) return
                    }
                }
                subscribe(g)
            }

            override fun onDescriptorWrite(g: BluetoothGatt, d: android.bluetooth.BluetoothGattDescriptor, s: Int) =
                subscribe(g)

            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
                harvest(ch.uuid, ch.value, found)
            }
        }
        status("Connecting…")
        device.connectGatt(context, false, cb)
    }

    /** Log a block verbatim, pull any profile names out of it, and keep a
     *  solution if the numbers look like one. */
    private fun harvest(uuid: UUID, v: ByteArray?, into: MutableList<GunProfile>) {
        if (v == null || v.isEmpty()) return
        val hex = v.joinToString("") { "%02x".format(it) }
        Logger.i(TAG, "  $uuid = $hex")
        for (n in namesIn(v)) {
            if (into.none { it.name == n }) {
                into.add(GunProfile(n, hex))
                Logger.i(TAG, "    profile name candidate: \"$n\"")
            }
        }
        solutionIn(v)?.let {
            setSolution(it)
            Logger.i(TAG, "    solution candidate: elev=${it.elevationMoa} windage=${it.windageMoa} (MOA)")
        }
    }

    /** Printable ASCII of 3+ characters — how a gun profile name shows itself
     *  in an otherwise binary block. */
    fun namesIn(v: ByteArray): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        for (b in v) {
            val c = b.toInt() and 0xFF
            if (c in 32..126) sb.append(c.toChar())
            else { if (sb.length >= 3) out.add(sb.toString().trim()); sb.setLength(0) }
        }
        if (sb.length >= 3) out.add(sb.toString().trim())
        return out.filter { it.length >= 3 }
    }

    /** A solution block, if the numbers look like a firing solution: elevation
     *  and windage as hundredths of MOA within a believable band. */
    fun solutionIn(v: ByteArray): KestrelSolution? {
        fun s16(i: Int): Int? {
            if (i + 1 >= v.size) return null
            val r = (v[i].toInt() and 0xFF) or ((v[i + 1].toInt() and 0xFF) shl 8)
            if (r == 0xFFFF || r == 0x8000 || r == 0x8001) return null
            return if (r > 0x7FFF) r - 0x10000 else r
        }
        for (i in 0 until maxOf(0, v.size - 3) step 2) {
            val e = s16(i)?.div(100.0) ?: continue
            val w = s16(i + 2)?.div(100.0) ?: continue
            if (e in -200.0..200.0 && w in -200.0..200.0 && (e != 0.0 || w != 0.0))
                return KestrelSolution(e, w)
        }
        return null
    }

    fun setSolution(s: KestrelSolution?) { lastSolution = s }
}
