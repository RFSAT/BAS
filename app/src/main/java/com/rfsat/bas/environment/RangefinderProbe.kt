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
 * BLE discovery probe for a laser rangefinder — built for the Tangoinnos
 * FIRE4000, which talks Bluetooth but publishes no GATT profile (it pairs with
 * its own ballistic app). So, exactly as with the undocumented cameras, this
 * DISCOVERS rather than assumes: it finds the device (bonded or advertising),
 * enumerates every service and characteristic with its properties, reads what
 * is readable, SUBSCRIBES to everything that notifies or indicates, and logs
 * each frame as raw hex with plausible little-/big-endian integer readings.
 *
 * Range a target while the probe is listening and the distance will appear in
 * the log as a value that tracks the display — that identifies the
 * characteristic and the encoding, after which reading it is trivial.
 *
 * Nothing is written to the device: every operation here is a read or a
 * subscription.
 */
object RangefinderProbe {

    private const val TAG = "RangefinderProbe"
    private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Names seen on rangefinders of this class; matched case-insensitively. */
    private val NAME_HINTS = listOf("fire", "tango", "rangefinder", "lrf", "4000")

    fun looksLikeRangefinder(name: String?): Boolean {
        val n = name?.lowercase() ?: return false
        return NAME_HINTS.any { n.contains(it) }
    }

    @SuppressLint("MissingPermission") // caller checks BLUETOOTH_CONNECT
    fun findPaired(): BluetoothDevice? = runCatching {
        BluetoothAdapter.getDefaultAdapter()?.bondedDevices?.firstOrNull { looksLikeRangefinder(it.name) }
    }.getOrNull()

    /** Scan for an advertising rangefinder; logs every named advertiser so a
     *  miss still tells us what the device actually calls itself. */
    @SuppressLint("MissingPermission") // caller checks BLUETOOTH_SCAN
    fun scan(context: Context, timeoutMs: Long = 12_000L, onResult: (BluetoothDevice?) -> Unit) {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
        val scanner = manager?.adapter?.bluetoothLeScanner
        if (scanner == null) { Logger.e(TAG, "BLE scanner unavailable (Bluetooth off?)"); onResult(null); return }
        val handler = Handler(Looper.getMainLooper())
        var done = false
        val seen = mutableSetOf<String>()
        val cb = object : android.bluetooth.le.ScanCallback() {
            override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
                val name = result.device.name ?: result.scanRecord?.deviceName ?: return
                if (seen.add(name)) Logger.i(TAG, "BLE advertiser: \"$name\" rssi=${result.rssi}")
                if (looksLikeRangefinder(name) && !done) {
                    done = true
                    runCatching { scanner.stopScan(this) }
                    Logger.i(TAG, "Rangefinder found by scan: \"$name\"")
                    handler.post { onResult(result.device) }
                }
            }
            override fun onScanFailed(errorCode: Int) {
                if (done) return
                done = true
                Logger.e(TAG, "BLE scan failed: code $errorCode")
                handler.post { onResult(null) }
            }
        }
        Logger.i(TAG, "Scanning for a rangefinder (${timeoutMs / 1000}s)…")
        scanner.startScan(null,
            android.bluetooth.le.ScanSettings.Builder()
                .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        handler.postDelayed({
            if (!done) {
                done = true
                runCatching { scanner.stopScan(cb) }
                Logger.i(TAG, "Scan timeout — named advertisers seen: $seen")
                onResult(null)
            }
        }, timeoutMs)
    }

    /**
     * Connect, enumerate, read, and listen for [listenMs]. [status] receives
     * short progress lines for the UI; everything lands in the diagnostic log.
     */
    @SuppressLint("MissingPermission")
    fun probe(context: Context, device: BluetoothDevice, listenMs: Long = 60_000L, status: (String) -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        val readQueue = ArrayDeque<BluetoothGattCharacteristic>()
        val notifyQueue = ArrayDeque<BluetoothGattCharacteristic>()

        val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, s: Int, newState: Int) {
                Logger.i(TAG, "connection state=$newState status=$s")
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    handler.post { status("Connected — discovering services…") }
                    gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    runCatching { gatt.close() }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, s: Int) {
                Logger.i(TAG, "services discovered (status=$s): ${gatt.services.size}")
                for (svc in gatt.services) {
                    Logger.i(TAG, "SERVICE ${svc.uuid}")
                    for (ch in svc.characteristics) {
                        val p = ch.properties
                        val flags = buildString {
                            if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) append("READ ")
                            if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) append("NOTIFY ")
                            if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) append("INDICATE ")
                            if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) append("WRITE ")
                        }.trim()
                        Logger.i(TAG, "  CHAR ${ch.uuid}  [$flags]")
                        if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) readQueue.add(ch)
                        if (p and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                                BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) notifyQueue.add(ch)
                    }
                }
                handler.post { status("Found ${readQueue.size} readable, ${notifyQueue.size} notifying") }
                readNext(gatt)
            }

            fun readNext(gatt: BluetoothGatt) {
                val ch = readQueue.removeFirstOrNull()
                if (ch == null) { subscribeNext(gatt); return }
                if (!gatt.readCharacteristic(ch)) readNext(gatt)
            }

            override fun onCharacteristicRead(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic, s: Int) {
                @Suppress("DEPRECATION") val v = ch.value
                Logger.i(TAG, "READ ${ch.uuid} = ${hex(v)} ${ints(v)}")
                readNext(gatt)
            }

            fun subscribeNext(gatt: BluetoothGatt) {
                val ch = notifyQueue.removeFirstOrNull()
                if (ch == null) {
                    handler.post { status("Listening — range a target now (${listenMs / 1000}s)") }
                    Logger.i(TAG, "Subscribed to all notifying characteristics; listening ${listenMs}ms")
                    handler.postDelayed({
                        Logger.i(TAG, "Listen window over — disconnecting")
                        runCatching { gatt.disconnect() }
                        handler.post { status("Probe finished — see the Log tab") }
                    }, listenMs)
                    return
                }
                runCatching {
                    gatt.setCharacteristicNotification(ch, true)
                    val d = ch.getDescriptor(CCCD)
                    if (d != null) {
                        @Suppress("DEPRECATION")
                        d.value = if (ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
                            android.bluetooth.BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        else android.bluetooth.BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                        @Suppress("DEPRECATION")
                        if (gatt.writeDescriptor(d)) return   // continue on the descriptor callback
                    }
                }
                subscribeNext(gatt)
            }

            override fun onDescriptorWrite(gatt: BluetoothGatt, d: android.bluetooth.BluetoothGattDescriptor, s: Int) {
                Logger.i(TAG, "subscribed ${d.characteristic.uuid} (status=$s)")
                subscribeNext(gatt)
            }

            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic) {
                val v = ch.value
                Logger.i(TAG, "NOTIFY ${ch.uuid} = ${hex(v)} ${ints(v)}")
                handler.post { status("Frame from ${short(ch.uuid)}: ${hex(v)}") }
            }
        }
        status("Connecting…")
        Logger.i(TAG, "Connecting to ${device.address} (${runCatching { device.name }.getOrNull()})")
        device.connectGatt(context, false, cb)
    }

    private fun short(u: UUID): String = u.toString().substring(4, 8)

    private fun hex(v: ByteArray?): String =
        v?.joinToString(" ") { "%02X".format(it) } ?: "null"

    /** Plausible integer readings so a distance is recognisable in the log:
     *  every 16- and 32-bit window, little- and big-endian. */
    private fun ints(v: ByteArray?): String {
        if (v == null || v.size < 2) return ""
        val out = StringBuilder("| ")
        for (i in 0..v.size - 2) {
            val le = (v[i].toInt() and 0xFF) or ((v[i + 1].toInt() and 0xFF) shl 8)
            val be = ((v[i].toInt() and 0xFF) shl 8) or (v[i + 1].toInt() and 0xFF)
            out.append("u16@$i le=$le be=$be ")
        }
        return out.toString().trim()
    }
}
