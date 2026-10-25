package com.encryptedenergy.sdk

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.ParcelUuid
import android.util.Log

/**
 * FCA6 scan loop (plan §2, §4) — Android port of the iOS BLEScanner.
 *
 * Hubble frames arrive as service data keyed by FCA6. Two OR'd filters cover
 * beacons that advertise the service UUID and beacons that only carry the
 * service data. Callbacks are delivered on the main looper, so callers may
 * touch UI state directly.
 *
 * Where the iOS scanner tracks CBCentralManager state, this one listens for
 * adapter state broadcasts: scanning auto-resumes when Bluetooth comes back
 * on while `start()` intent is held, mirroring `startIfPoweredOn`.
 */
internal class BleScanner(private val context: Context) {

    enum class State { SCANNING, BLUETOOTH_OFF, UNAUTHORIZED, UNSUPPORTED, IDLE }

    var onState: ((State) -> Unit)? = null

    /** Every FCA6 advertisement, pre-throttle: (parsed, rssi, heardAtMillis). */
    var onHeard: ((ParsedPacket, Int, Long) -> Unit)? = null

    /**
     * Only packets that pass the EID gate and the throttle. Caller attaches
     * the current GPS fix and enqueues, or drops if there is no fix (§4).
     */
    var onPacket: ((ParsedPacket, Int, Long) -> Unit)? = null

    /** Raw onScanFailed error code — the engine counts and logs these (0006). */
    var onScanFailedCode: ((Int) -> Unit)? = null

    private val throttle = EidThrottle(PER_EID_THROTTLE_MILLIS)
    private var wantScanning = false
    private var receiverRegistered = false
    private var reduced = false

    private val adapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val scanCallback = object : ScanCallback() {
        // Framework -> SDK boundary (C6): an uncaught throw here unwinds
        // into the host app's process and kills it — the one incident that
        // ends a partner deal (plan §6.3). Swallow-and-log, never propagate.
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            try {
                val raw = result.scanRecord?.getServiceData(SERVICE_UUID) ?: return
                val parsed = HubblePacketParser.parse(raw) ?: return
                val now = System.currentTimeMillis()
                onHeard?.invoke(parsed, result.rssi, now)

                // No EID -> never forwarded: one bad packet 422s the whole batch (§1a).
                val eid = parsed.eidHex ?: return
                if (!throttle.shouldForward(eid, now)) return
                onPacket?.invoke(parsed, result.rssi, now)
            } catch (t: Throwable) {
                Log.w(LOG_TAG, "scan callback error", t)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            try {
                onScanFailedCode?.invoke(errorCode)
                onState?.invoke(
                    when (errorCode) {
                        // A second startScan with the same callback while one is
                        // live. The original scan is untouched, so reporting
                        // anything but SCANNING here would mislabel a working
                        // radio (0005 review finding).
                        SCAN_FAILED_ALREADY_STARTED -> State.SCANNING
                        SCAN_FAILED_FEATURE_UNSUPPORTED -> State.UNSUPPORTED
                        else -> State.IDLE
                    }
                )
            } catch (t: Throwable) {
                Log.w(LOG_TAG, "scan-failed callback error", t)
            }
        }
    }

    private val adapterStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            when (intent?.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_ON -> startIfPoweredOn()
                BluetoothAdapter.STATE_OFF -> if (wantScanning) onState?.invoke(State.BLUETOOTH_OFF)
            }
        }
    }

    fun start() {
        wantScanning = true
        if (!receiverRegistered) {
            context.registerReceiver(
                adapterStateReceiver,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
            )
            receiverRegistered = true
        }
        startIfPoweredOn()
    }

    /**
     * Swap the scan power profile (0009). ScanSettings are fixed at
     * startScan time, so a live scan is restarted. ScanPolicy's hysteresis
     * bounds flips to ~once per 5 min dwell — far under the framework's
     * 5-starts-per-30 s limit, past which scans fail silently.
     */
    fun setReduced(value: Boolean) {
        if (reduced == value) return
        reduced = value
        if (!wantScanning) return
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: SecurityException) {
            // Permission revoked mid-session; startIfPoweredOn surfaces it.
        }
        startIfPoweredOn()
    }

    fun stop() {
        wantScanning = false
        if (receiverRegistered) {
            context.unregisterReceiver(adapterStateReceiver)
            receiverRegistered = false
        }
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: SecurityException) {
            // Permission revoked mid-session; nothing left to stop.
        }
        onState?.invoke(State.IDLE)
    }

    private fun startIfPoweredOn() {
        if (!wantScanning) return
        val adapter = adapter ?: run { onState?.invoke(State.UNSUPPORTED); return }
        if (!adapter.isEnabled) { onState?.invoke(State.BLUETOOTH_OFF); return }
        val scanner = adapter.bluetoothLeScanner ?: run { onState?.invoke(State.BLUETOOTH_OFF); return }

        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(SERVICE_UUID).build(),
            ScanFilter.Builder().setServiceData(SERVICE_UUID, ByteArray(0)).build(),
        )
        val settings = ScanSettings.Builder()
            // Reduced = BALANCED (~1 s listening in 4), the twin of iOS
            // 1.3's 10-in-30 duty cycle. LOW_POWER's 0.5-in-5.1 window
            // could miss a 5-15 s transit entirely.
            .setScanMode(
                if (reduced) ScanSettings.SCAN_MODE_BALANCED
                else ScanSettings.SCAN_MODE_LOW_LATENCY
            )
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        try {
            scanner.startScan(filters, settings, scanCallback)
            onState?.invoke(State.SCANNING)
        } catch (_: SecurityException) {
            onState?.invoke(State.UNAUTHORIZED)
        }
    }

    companion object {
        /** Hubble FCA6, expanded to the 128-bit Bluetooth base UUID. */
        val SERVICE_UUID: ParcelUuid = ParcelUuid.fromString("0000fca6-0000-1000-8000-00805f9b34fb")

        /** Forward at most one packet per EID per window (§2). */
        const val PER_EID_THROTTLE_MILLIS = 3_000L

        private const val LOG_TAG = "EEGateway"
    }
}
