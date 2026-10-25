package com.encryptedenergy.sdk

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * The entire public surface of the EE gateway SDK (plan §1, §3). A partner app
 * integrates in two lines:
 *
 * ```
 * EEGateway.configure(context, EEConfig.GatewayToken(token))
 * EEGateway.start()   // from your existing service lifecycle
 * ```
 *
 * Next app update, every phone in the fleet is a silent EE gateway: no signup,
 * no UI, no new permission prompts, no EE credential in the binary.
 *
 * ## Contracts (these ARE the product, plan §3)
 *  - **Never throws.** Every public method is crash-isolated. Bad input, a
 *    dead network, a missing grant: the SDK absorbs it and reflects it in
 *    [status], it never propagates an exception into the host app.
 *  - **Never prompts for permissions.** The SDK is dormant without the host's
 *    scan/location grants. [start] with grants missing lands in
 *    [EEState.AWAITING_PERMISSIONS]; it does not request anything, and it
 *    advances to [EEState.RUNNING] on its own once the host obtains the
 *    grants. The host app owns the permission UX, because fleet apps already
 *    hold the grants.
 *  - **No foreground service of its own.** The host drives the lifecycle; the
 *    reference FGS ships in the sample app, not here.
 *  - **[configure] is idempotent** and holds only `applicationContext`, never
 *    an Activity, so it can't leak a screen. A changed config applies the
 *    next time the gateway is started from a non-running state; it never
 *    regresses a running gateway.
 *  - **[start]/[stop] are idempotent and main-thread safe.** Call them from
 *    the host's service lifecycle as often as you like.
 *  - **Listener callbacks arrive on the main thread and are crash-isolated**
 *    (see [setListener]). Counter-only changes are coalesced (≤ ~1/s) so a
 *    beacon-dense street can't flood the host's main thread; state changes
 *    ride the same tick and are never dropped.
 *
 * Thread-safety: all state transitions are serialized on a single lock, so the
 * facade is safe to call from any thread even though callbacks land on main.
 */
object EEGateway {

    /** SDK version, matching the published artifact (`com.encryptedenergy:gateway-sdk`). */
    const val VERSION = SdkVersion.STRING

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())

    // Held state. appContext is the application context only (never an
    // Activity), so configure can be called from anywhere without a leak.
    private var appContext: Context? = null
    private var config: EEConfig? = null
    private var listener: EEListener? = null
    private var current = EEStatus(state = EEState.UNCONFIGURED)
    private var engine: Engine? = null
    /** The config [engine] was built with — a later configure() may differ. */
    private var engineConfig: EEConfig? = null
    private var refreshPending = false

    /**
     * Store the [config] and the host's application context, then move to
     * [EEState.READY] if this is the first configuration. Idempotent: calling
     * it again just refreshes the config and never regresses a running
     * gateway. Only `context.applicationContext` is retained.
     *
     * Never throws.
     */
    @JvmStatic
    fun configure(context: Context, config: EEConfig) {
        try {
            synchronized(lock) {
                appContext = context.applicationContext
                this.config = config
                if (current.state == EEState.UNCONFIGURED) {
                    transition(current.copy(state = EEState.READY))
                }
            }
        } catch (t: Throwable) {
            // Never throws (plan §3) — but never silently either: a swallowed
            // failure a partner can't see in logcat is undiagnosable.
            Log.w(TAG, "configure failed", t)
        }
    }

    /**
     * Begin (or resume) the gateway from the host's service lifecycle.
     * Idempotent and main-thread safe. A no-op if not yet configured.
     *
     * With the host's scan/location grants present this lands in
     * [EEState.RUNNING]; without them, [EEState.AWAITING_PERMISSIONS] — the
     * pipeline stays dormant, heartbeats still flow (so the fleet dashboard
     * can see the phone), and the SDK advances by itself when the grants
     * appear.
     *
     * Never throws; never prompts for permissions.
     */
    @JvmStatic
    fun start() {
        try {
            synchronized(lock) {
                val context = appContext ?: return   // nothing to start; not an error
                val cfg = config ?: return
                if (current.state == EEState.RUNNING ||
                    current.state == EEState.AWAITING_PERMISSIONS ||
                    current.state == EEState.PAUSED_REMOTE
                ) return                             // idempotent (incl. remote-paused: already started)

                // A config change (new token, new base URL) takes effect
                // here, from a non-running state: tear down and rebuild.
                if (engine != null && engineConfig != cfg) {
                    engine?.stop()
                    engine = null
                }
                val e = engine ?: Engine(context, cfg, ::engineChanged).also {
                    engine = it
                    engineConfig = cfg
                }
                e.start()
                transition(
                    composeStatus(
                        when {
                            e.remotePaused -> EEState.PAUSED_REMOTE
                            e.permissionsGranted() -> EEState.RUNNING
                            else -> EEState.AWAITING_PERMISSIONS
                        }
                    )
                )
            }
        } catch (t: Throwable) {
            // Never throws (plan §3); logged so the failure is visible.
            Log.w(TAG, "start failed", t)
        }
    }

    /**
     * Stop the gateway from the host's service lifecycle. Idempotent and
     * main-thread safe. A no-op if not yet configured or already stopped.
     *
     * Never throws.
     */
    @JvmStatic
    fun stop() {
        try {
            synchronized(lock) {
                when (current.state) {
                    EEState.UNCONFIGURED, EEState.STOPPED -> return
                    else -> {
                        engine?.stop()
                        transition(composeStatus(EEState.STOPPED))
                    }
                }
            }
        } catch (t: Throwable) {
            // Never throws (plan §3); logged so the failure is visible.
            Log.w(TAG, "stop failed", t)
        }
    }

    /** The current [EEStatus] snapshot. Never throws. */
    @JvmStatic
    fun status(): EEStatus = synchronized(lock) { composeStatus(current.state) }

    /**
     * Register (or clear, with null) the status [listener]. Callbacks always
     * arrive on the main thread and are crash-isolated: a throwing listener is
     * swallowed and never breaks the SDK or the host app. Never throws.
     */
    @JvmStatic
    fun setListener(listener: EEListener?) {
        synchronized(lock) {
            this.listener = listener
        }
    }

    /** Live counters from the engine grafted onto [state]. Call under [lock]. */
    private fun composeStatus(state: EEState): EEStatus {
        val e = engine ?: return current.copy(state = state)
        return current.copy(
            state = state,
            pingsHeard = e.pingsHeard,
            packetsForwarded = e.packetsForwarded,
            queueDepth = e.queueDepth(),
            gatewayPublicId = e.gatewayPublicId,
            lastHeartbeatAt = e.lastHeartbeatAt,
        )
    }

    /**
     * Engine change feed — potentially once per heard advertisement, from
     * main or a loop executor. Coalesced to one main-thread refresh per
     * [REFRESH_COALESCE_MILLIS]: dense traffic becomes at most ~1 listener
     * callback/s, and the dormancy-gate flip (AWAITING_PERMISSIONS ↔
     * RUNNING) rides the next tick, never lost because [transition] compares
     * snapshots.
     */
    private fun engineChanged() {
        synchronized(lock) {
            if (refreshPending) return
            refreshPending = true
        }
        mainHandler.postDelayed({
            try {
                synchronized(lock) {
                    refreshPending = false
                    refreshLocked()
                }
            } catch (t: Throwable) {
                // Posted work runs outside the public-method guards; it gets
                // its own (plan §3: an SDK bug never crashes the host).
                Log.w(TAG, "status refresh failed", t)
            }
        }, REFRESH_COALESCE_MILLIS)
    }

    /** Recompute status from the engine and notify if it changed. Under [lock]. */
    private fun refreshLocked() {
        val e = engine ?: return
        val state = when {
            !e.started -> current.state          // READY or STOPPED: host-driven
            e.remotePaused -> EEState.PAUSED_REMOTE
            e.scanning -> EEState.RUNNING
            else -> EEState.AWAITING_PERMISSIONS
        }
        transition(composeStatus(state))
    }

    /**
     * Commit a new status and notify the listener on the main thread. Called
     * only while holding [lock]; the notification itself is posted, so it never
     * runs under the lock and a slow listener can't stall a caller. A no-op
     * when nothing changed, so coalesced refreshes don't echo.
     */
    private fun transition(next: EEStatus) {
        if (next == current) return
        current = next
        val l = listener ?: return
        mainHandler.post {
            try {
                l.onStatusChanged(next)
            } catch (_: Throwable) {
                // A throwing listener must never break the SDK (plan §3).
            }
        }
    }

    private const val REFRESH_COALESCE_MILLIS = 1_000L
    private const val TAG = "EEGateway"
}
