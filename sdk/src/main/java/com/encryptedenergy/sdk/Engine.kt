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
import android.util.Log
import java.io.File
import java.util.Base64
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The pipeline owner behind [EEGateway] (plan §5 C5/C6): scanner, location,
 * queue, heartbeat, upload, token provisioning. This is gateway-android's
 * GatewayEngine with the entire product surface removed — no UI state, no
 * notifications, no claim lifecycle, no diagnostics log, no fixed-location
 * override — and the SDK-only machinery added: the permission-dormancy gate
 * (the app ASKS for grants; the SDK only WAITS for them, plan §3), the C9
 * remote kill switch, and provisioning orchestration.
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
 * ## Remote kill switch (C9/C6)
 * A heartbeat 2xx with `"disable": true` → scanning and upload stop,
 * heartbeats CONTINUE (they're how the fleet re-enables), facade shows
 * [EEState.PAUSED_REMOTE]. The flag clearing resumes everything, grants
 * permitting. Absent means enabled; a parse failure must never pause a fleet
 * (EEClient.parseDisable defaults false).
 *
 * ## Provisioning (C6, the C4 invariants wired live)
 * On every start, a daemon thread reconciles the stored token with the
 * config: [EEConfig.GatewayToken] whose token differs from the store runs
 * verify-before-persist; [EEConfig.SelfMint] with no stored token mints.
 * Transient failures retry on [Backoff]; a server rejection PARKS until the
 * next start (see [ProvisionRunner]). The loops only ever read through the
 * store, so until the server says yes they run against the previous token —
 * or none, where upload requeues and heartbeat goes Unauthorized, both safe.
 * On success the provisioning thread fires one immediate heartbeat, so
 * telemetry, `disable`, and the gateway public id land seconds after the
 * token does instead of one cadence later.
 *
 * ## Threading
 * [start]/[stop] may be called from any thread (the facade's contract); all
 * pipeline mutation is posted to the main looper, where the scan and fix
 * callbacks already live. Facade-visible tallies are @Volatile single-writer
 * fields read through [EEGateway.status] from arbitrary threads. [onChanged]
 * fires (possibly very often — once per heard advertisement) on whatever
 * thread produced the change; the facade coalesces before notifying the
 * host's listener. Every runnable that enters the main looper and every
 * executor task body is wrapped in [guard] — an SDK bug must surface as a
 * dropped packet and a log line, never as a host crash (C6).
 *
 * Android-bound: outside the JVM harness (stub-compiled by runtests-sdk.sh;
 * the C8 Studio build and on-device dogfood are the real check). Everything
 * it composes — queue, store, throttle, loops, policy, batch semantics,
 * provisioning policy — is harness-tested on its own.
 */
internal class Engine(
    private val context: Context,
    private val config: EEConfig,
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
    private var provisionExecutor: ExecutorService? = null

    // Facade-visible tallies. Each is written by exactly one thread (main or
    // one loop executor) and read via EEGateway.status() from any thread.
    @Volatile var pingsHeard = 0L; private set
    @Volatile var packetsForwarded = 0L; private set
    @Volatile var lastHeartbeatAt: Long? = null; private set
    @Volatile var gatewayPublicId: String? = null; private set
    /** Host asked us to run (start() without a matching stop()). */
    @Volatile var started = false; private set
    /** Radio + GPS actually up — false while dormant on missing grants. */
    @Volatile var scanning = false; private set
    /** Server kill switch is ON: pipeline+upload parked, heartbeats flowing. */
    @Volatile var remotePaused = false; private set

    @Volatile private var lastPacketAtMillis: Long? = null
    private val startedElapsed = SystemClock.elapsedRealtime()
    private var scanReduced = false
    private var batteryRegistered = false

    fun queueDepth(): Int = queue.size()
    fun permissionsGranted(): Boolean = ScanPermissions.granted(context)

    // ACTION_BATTERY_CHANGED is sticky: registerReceiver returns the current
    // status immediately, and this fires on every plug/unplug after that.
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) = guard {
            if (scanPolicy.onCharging(isCharging(intent))) retuneScanMode()
        }
    }

    // Dwell expiry needs no fix or broadcast to notice it: with the phone
    // dead still, fixes may keep arriving (cadence is not adapted), but if
    // the provider goes quiet this tick still flips the mode on time.
    private val retuneTick = object : Runnable {
        override fun run() = guard {
            if (!scanning) return@guard
            retuneScanMode()
            main.postDelayed(this, RETUNE_INTERVAL_MILLIS)
        }
    }

    // The dormancy gate's clock: cheap checkSelfPermission calls, main
    // looper, only while started-but-dormant (and not remote-paused).
    private val permissionPoll = object : Runnable {
        override fun run() = guard {
            if (!started || scanning || remotePaused) return@guard
            if (permissionsGranted()) {
                startPipeline()
                onChanged()
            } else {
                main.postDelayed(this, PERMISSION_POLL_MILLIS)
            }
        }
    }

    init {
        // Restore packets that survived process death — OFF the construction
        // thread: configure()/start() may run on the host's main thread, and
        // a disk read there is an ANR seed (and a StrictMode violation in
        // every partner's debug build). Persistence arms only after the
        // restore, so loading through add() can't trigger a rewrite per
        // line; anything heard in the gap persists on the next queue change
        // (the 10 s upload tick at the latest). Restored packets may land
        // behind freshly heard ones — ordering across a process death has no
        // meaning anyway.
        Thread({
            guard {
                store.load().forEach { queue.add(it) }
                queue.onChange = { store.schedulePersist(queue) }
                onChanged()
            }
        }, "ee-sdk-restore").apply { isDaemon = true }.start()

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
                result.publicId?.let { gatewayPublicId = it }
                // The kill switch acts on the main looper with the rest of
                // the pipeline lifecycle (C6).
                val disable = result.disable
                main.post { guard { applyRemoteDisable(disable) } }
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
     * ops), provisioning reconciliation in the background, pipeline when the
     * grants allow. Idempotent, any thread.
     */
    fun start() {
        if (started) return
        started = true
        heartbeatLoop.start()
        if (!remotePaused) uploadLoop.start()
        provisionIfNeeded()
        main.post { guard { maybeStartPipeline() } }
    }

    /** Stop everything. Idempotent, any thread. */
    fun stop() {
        if (!started) return
        started = false
        provisionExecutor?.shutdownNow()   // interrupts a backoff sleep
        provisionExecutor = null
        heartbeatLoop.stop()
        uploadLoop.stop()
        main.post {
            guard {
                main.removeCallbacks(permissionPoll)
                stopPipeline()
            }
        }
    }

    /**
     * Reconcile the stored token with the config on a daemon thread — C4's
     * verify-before-persist / mint-persists-only-on-yes, retried through
     * [ProvisionRunner]. No-op when the store already matches.
     */
    private fun provisionIfNeeded() {
        val executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "ee-sdk-provision").apply { isDaemon = true }
        }
        provisionExecutor = executor
        executor.execute {
            guard {
                if (needsProvisioning()) {
                    val result = ProvisionRunner(::provisionAttempt, Backoff(), Thread::sleep)
                        .run({ started })
                    if (result == ProvisionRunner.Result.PROVISIONED) {
                        // Immediate hello: telemetry, `disable`, and public id
                        // land now, not one 5-min cadence later. tick() runs on
                        // this thread and is guarded inside.
                        heartbeatLoop.tick()
                    }
                    onChanged()
                }
            }
            // One-shot work is done; don't idle a thread on a partner's
            // phone until stop(). Self-shutdown lets this task finish and
            // makes stop()'s shutdownNow a no-op.
            executor.shutdown()
        }
    }

    /** Keystore read — background threads only (provisioning executor). */
    private fun needsProvisioning(): Boolean = when (config) {
        is EEConfig.GatewayToken -> tokenStore.load() != config.token
        is EEConfig.SelfMint -> tokenStore.load() == null
    }

    private fun provisionAttempt(): ProvisionRunner.Outcome = when (config) {
        is EEConfig.GatewayToken -> when (
            TokenProvision.injectToken(
                config.token,
                verify = { client.verify(tokenOverride = it) },
                persist = { tokenStore.save(it) },
            )
        ) {
            is TokenProvision.InjectOutcome.Verified -> ProvisionRunner.Outcome.PROVISIONED
            is TokenProvision.InjectOutcome.Rejected -> ProvisionRunner.Outcome.REJECTED
            is TokenProvision.InjectOutcome.Retry -> ProvisionRunner.Outcome.RETRY
        }
        is EEConfig.SelfMint -> when (
            val outcome = TokenProvision.selfMint(
                config.deviceName ?: Build.MODEL,
                mint = { EEClient.mintDevice(it, config.baseUrl) },
                persist = { token, name ->
                    tokenStore.save(token)
                    tokenStore.saveGatewayName(name)
                },
            )
        ) {
            is TokenProvision.MintOutcome.Provisioned -> ProvisionRunner.Outcome.PROVISIONED
            is TokenProvision.MintOutcome.Rejected -> {
                Log.w(LOG_TAG, "self-mint rejected: ${outcome.reason}")
                ProvisionRunner.Outcome.REJECTED
            }
            is TokenProvision.MintOutcome.Retry -> ProvisionRunner.Outcome.RETRY
        }
    }

    /**
     * The C9 kill switch, read from every heartbeat 2xx. Main looper only.
     * ON: pipeline + upload park, heartbeats keep flowing (they're the only
     * way to learn the flag cleared). OFF: resume what the grants allow.
     */
    private fun applyRemoteDisable(disable: Boolean) {
        if (disable == remotePaused) return
        remotePaused = disable
        if (disable) {
            main.removeCallbacks(permissionPoll)
            stopPipeline()
            uploadLoop.stop()
        } else if (started) {
            uploadLoop.start()
            maybeStartPipeline()
        }
        onChanged()
    }

    /** Pipeline if allowed, else dormancy poll. Main looper only. */
    private fun maybeStartPipeline() {
        if (!started || scanning || remotePaused) return
        if (permissionsGranted()) {
            startPipeline()
        } else {
            main.postDelayed(permissionPoll, PERMISSION_POLL_MILLIS)
        }
        onChanged()
    }

    /** Scanner + location + adaptive-scan machinery. Main looper only. */
    private fun startPipeline() {
        if (scanning || remotePaused) return
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
            // "active" = radio up and earning; a dormant (awaiting-grants),
            // remote-paused, or stopped SDK reports "backgrounded", matching
            // what the silent-gateway tooling expects from a phone that
            // isn't scanning.
            clientState = if (scanning) "active" else "backgrounded",
            devicesSeen = rollup.toJson(now),
            scanMode = if (scanPolicy.mode(now) == ScanPolicy.Mode.REDUCED) "reduced" else "full",
        )
    }

    /**
     * C6 isolation: every runnable entering the main looper and every
     * executor task body rides through here. An SDK bug becomes a log line,
     * never a host-app crash.
     */
    private inline fun guard(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.w(LOG_TAG, "internal error", t)
        }
    }

    companion object {
        /** SDK-namespaced (C4 rule): never collides with a host or the
         *  first-party app sharing filesDir. */
        private const val QUEUE_FILE = "com.encryptedenergy.sdk.packet-queue.eeq"
        private const val RETUNE_INTERVAL_MILLIS = 30_000L
        private const val PERMISSION_POLL_MILLIS = 5_000L
        private const val LOG_TAG = "EEGateway"
    }
}
