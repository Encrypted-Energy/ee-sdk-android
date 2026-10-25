package com.encryptedenergy.sdk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File
import java.util.Base64

/**
 * The pipeline owner behind [EEGateway] (plan §5 C5): scanner, location,
 * queue, heartbeat, upload. This is gateway-android's GatewayEngine with the
 * entire product surface removed — no UI state, no notifications, no claim
 * lifecycle, no diagnostics log, no fixed-location override — and one thing
 * added that the first-party app never needed: the permission-dormancy gate.
 * The app ASKS for grants; the SDK only WAITS for them (plan §3).
 *
 * ## Dormancy gate
 * [start] with grants missing runs the heartbeat/upload loops (ops can see a
 * dormant fleet phone — gps_status `dongle_missing`, status `backgrounded`)
 * but leaves the radio and GPS untouched, and polls the grants every
 * [PERMISSION_POLL_MILLIS]. The moment the host obtains them, the pipeline
 * starts on its own — [EEState.AWAITING_PERMISSIONS] → [EEState.RUNNING]
 * with no further host call. A mid-run revocation (scanner lands in
 * UNAUTHORIZED) tears the pipeline down and returns to the same poll, so the
 * state machine is honest in both directions.
 *
 * ## Threading
 * [start]/[stop] may be called from any thread (the facade's contract); all
 * pipeline mutation is posted to the main looper, where the scan and fix
 * callbacks already live. Facade-visible tallies are @Volatile singles-writer
 * fields read through [EEGateway.status] from arbitrary threads. [onChanged]
 * fires (possibly very often — once per heard advertisement) on whatever
 * thread produced the change; the facade coalesces before notifying the
 * host's listener.
 *
 * Android-bound: outside the JVM harness (verified by review + the C8 Studio
 * build). Everything it composes — queue, store, throttle, loops, policy,
 * batch semantics — is harness-tested on its own.
 */
internal class Engine(
    private val context: Context,
    config: EEConfig,
    private val onChanged: () -> Unit,
) {

    private val tokenStore = TokenStore(context)
    private val client = EEClient({ tokenStore.load() }, config.baseUrl)
    private val counters = Counters()
    private val rollup = DeviceRollup()
    private val store = QueueStore(File(context.filesDir, QUEUE_FILE))
    private val queue = PacketQueue()
    private val locationProvider = LocationProvider(context)
    private val scanner = BleScanner(context)
    private val scanPolicy = ScanPolicy()
    private val heartbeatLoop: HeartbeatLoop
    private val uploadLoop: UploadLoop

    private val main = Handler(Looper.getMainLooper())

    // Facade-visible tallies. Each is written by exactly one thread (main or
    // one loop executor) and read via EEGateway.status() from any thread.
    @Volatile var pingsHeard = 0L; private set
    @Volatile var packetsForwarded = 0L; private set
    @Volatile var lastHeartbeatAt: Long? = null; private set
    /** Host asked us to run (start() without a matching stop()). */
    @Volatile var started = false; private set
    /** Radio + GPS actually up — false while dormant on missing grants. */
    @Volatile var scanning = false; private set
    /** Last heartbeat's C9 kill-switch flag. Surfaced here, ACTED ON in C6. */
    @Volatile var remoteDisable = false; private set

    @Volatile private var lastPacketAtMillis: Long? = null
    private val startedElapsed = SystemClock.elapsedRealtime()
    private var scanReduced = false
    private var batteryRegistered = false

    fun queueDepth(): Int = queue.size()
    fun permissionsGranted(): Boolean = ScanPermissions.granted(context)

    // ACTION_BATTERY_CHANGED is sticky: registerReceiver returns the current
    // status immediately, and this fires on every plug/unplug after that.
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (scanPolicy.onCharging(isCharging(intent))) retuneScanMode()
        }
    }

    // Dwell expiry needs no fix or broadcast to notice it: with the phone
    // dead still, fixes may keep arriving (cadence is not adapted), but if
    // the provider goes quiet this tick still flips the mode on time.
    private val retuneTick = object : Runnable {
        override fun run() {
            if (!scanning) return
            retuneScanMode()
            main.postDelayed(this, RETUNE_INTERVAL_MILLIS)
        }
    }

    // The dormancy gate's clock: cheap checkSelfPermission calls, main
    // looper, only while started-but-dormant.
    private val permissionPoll = object : Runnable {
        override fun run() {
            if (!started || scanning) return
            if (permissionsGranted()) {
                startPipeline()
                onChanged()
            } else {
                main.postDelayed(this, PERMISSION_POLL_MILLIS)
            }
        }
    }

    init {
        // Restore packets that survived process death, THEN arm persistence
        // (loading through add() must not trigger a rewrite per line).
        store.load().forEach { queue.add(it) }
        queue.onChange = { store.schedulePersist(queue) }

        locationProvider.onFix = { fix ->
            scanPolicy.onFix(fix)
            retuneScanMode()
        }

        scanner.onState = { state ->
            // Mid-run revocation: the radio said no. Back to dormant; the
            // poll re-arms and the pipeline resumes if the grant returns.
            if (state == BleScanner.State.UNAUTHORIZED && scanning) {
                stopPipeline()
                if (started) main.postDelayed(permissionPoll, PERMISSION_POLL_MILLIS)
            }
            onChanged()
        }
        scanner.onScanFailedCode = { counters.incScanErrors() }
        scanner.onHeard = { parsed, rssi, at ->
            pingsHeard++
            counters.incHeard()
            parsed.eidHex?.let { rollup.record(it, rssi, at) }
            onChanged()
        }
        scanner.onPacket = { parsed, rssi, at ->
            val eid = parsed.eidHex
            val fix = locationProvider.freshFix(at)
            when {
                eid == null -> {}
                fix == null ->
                    // No fix fresher than 25 s: drop, never stamp stale
                    // coords (the 7/31 incident, on Android from day one).
                    counters.incDroppedNoFix()
                else -> {
                    queue.add(
                        UploadPacket(
                            eidHex = eid,
                            payloadB64 = Base64.getEncoder().encodeToString(parsed.payload),
                            rssi = rssi,
                            timestampSeconds = at / 1000,
                            latitude = fix.latitude,
                            longitude = fix.longitude,
                            positionAtSeconds = fix.capturedAtMillis / 1000,
                            accuracyM = fix.accuracyM,
                        )
                    )
                    lastPacketAtMillis = at
                    onChanged()
                }
            }
        }

        heartbeatLoop = HeartbeatLoop(client::heartbeat, counters, ::runtimeInfo) { result ->
            if (result is EEClient.Result.Ok) {
                lastHeartbeatAt = System.currentTimeMillis()
                remoteDisable = result.disable
            }
            onChanged()
        }
        uploadLoop = UploadLoop(client::ingest, queue, counters) { outcome ->
            packetsForwarded += outcome.forwarded
            onChanged()
        }
    }

    /**
     * Begin: loops immediately (heartbeats make dormant phones visible to
     * ops), pipeline when the grants allow. Idempotent, any thread.
     */
    fun start() {
        if (started) return
        started = true
        heartbeatLoop.start()
        uploadLoop.start()
        main.post {
            if (!started || scanning) return@post
            if (permissionsGranted()) {
                startPipeline()
                onChanged()
            } else {
                main.postDelayed(permissionPoll, PERMISSION_POLL_MILLIS)
            }
        }
    }

    /** Stop everything. Idempotent, any thread. */
    fun stop() {
        if (!started) return
        started = false
        heartbeatLoop.stop()
        uploadLoop.stop()
        main.post {
            main.removeCallbacks(permissionPoll)
            stopPipeline()
        }
    }

    /** Scanner + location + adaptive-scan machinery. Main looper only. */
    private fun startPipeline() {
        if (scanning) return
        scanPolicy.reset(System.currentTimeMillis())
        val sticky = context.registerReceiver(
            batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        batteryRegistered = true
        scanPolicy.onCharging(isCharging(sticky))
        // Pre-tune so the scan starts on the right power profile instead of
        // restarting one tick in (setReduced before start() only records).
        retuneScanMode()
        locationProvider.start()
        scanning = true
        scanner.start()
        // A grant revoked between the permission check and the radio call
        // surfaces as a SYNCHRONOUS UNAUTHORIZED inside start(): the onState
        // handler tears the pipeline back down (scanning must already be
        // true for it to act — hence the order above). Don't arm the tick
        // over a corpse.
        if (!scanning) return
        main.postDelayed(retuneTick, RETUNE_INTERVAL_MILLIS)
    }

    /** Main looper only. */
    private fun stopPipeline() {
        if (!scanning) return
        main.removeCallbacks(retuneTick)
        if (batteryRegistered) {
            context.unregisterReceiver(batteryReceiver)
            batteryRegistered = false
        }
        scanner.stop()
        locationProvider.stop()
        scanning = false
    }

    /**
     * One policy source (0009): the scanner profile follows ScanPolicy.
     * Main looper only (fix callbacks, battery broadcasts, the tick) — the
     * heartbeat thread reads scanPolicy.mode directly, never this state.
     */
    private fun retuneScanMode() {
        val reduce = scanPolicy.mode(System.currentTimeMillis()) == ScanPolicy.Mode.REDUCED
        if (reduce == scanReduced) return
        scanReduced = reduce
        scanner.setReduced(reduce)
    }

    private fun isCharging(intent: Intent?): Boolean =
        when (intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING,
            BatteryManager.BATTERY_STATUS_FULL -> true
            else -> false
        }

    // Called from the heartbeat executor thread; all reads are safe there.
    private fun runtimeInfo(): HeartbeatPayload.Runtime {
        val now = System.currentTimeMillis()
        return HeartbeatPayload.Runtime(
            osVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            uptimeSeconds = (SystemClock.elapsedRealtime() - startedElapsed) / 1000,
            lastPacketAtMillis = lastPacketAtMillis,
            gpsStatus = locationProvider.status(now),
            lastKnownPositionAtMillis = locationProvider.lastFix()?.capturedAtMillis,
            // "active" = radio up and earning; a dormant (awaiting-grants)
            // or stopped SDK reports "backgrounded", matching what the
            // silent-gateway tooling expects from a phone that isn't scanning.
            clientState = if (scanning) "active" else "backgrounded",
            devicesSeen = rollup.toJson(now),
            scanMode = if (scanPolicy.mode(now) == ScanPolicy.Mode.REDUCED) "reduced" else "full",
        )
    }

    companion object {
        /** SDK-namespaced (C4 rule): never collides with a host or the
         *  first-party app sharing filesDir. */
        private const val QUEUE_FILE = "com.encryptedenergy.sdk.packet-queue.eeq"
        private const val RETUNE_INTERVAL_MILLIS = 30_000L
        private const val PERMISSION_POLL_MILLIS = 5_000L
    }
}
