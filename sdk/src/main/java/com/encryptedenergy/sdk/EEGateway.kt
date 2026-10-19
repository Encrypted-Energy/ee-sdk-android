package com.encryptedenergy.sdk

import android.content.Context
import android.os.Handler
import android.os.Looper

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
 *    [EEState.AWAITING_PERMISSIONS]; it does not request anything. The host
 *    app owns the permission UX, because fleet apps already hold the grants.
 *  - **No foreground service of its own.** The host drives the lifecycle; the
 *    reference FGS ships in the sample app, not here.
 *  - **[configure] is idempotent** and holds only `applicationContext`, never
 *    an Activity, so it can't leak a screen.
 *  - **[start]/[stop] are idempotent and main-thread safe.** Call them from
 *    the host's service lifecycle as often as you like.
 *  - **Listener callbacks arrive on the main thread and are crash-isolated**
 *    (see [setListener]).
 *  - **The API surface is frozen-additive from C1 forward.** Once a partner
 *    ships, we can never force their app update, so nothing here changes
 *    meaning; we only add. See api-freeze.md.
 *
 * Thread-safety: all state transitions are serialized on a single lock, so the
 * facade is safe to call from any thread even though callbacks land on main.
 */
object EEGateway {

    /** SDK version, matching the published artifact (`com.encryptedenergy:gateway-sdk`). */
    const val VERSION = "0.1.0"

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())

    // Held state. appContext is the application context only (never an
    // Activity), so configure can be called from anywhere without a leak.
    private var appContext: Context? = null
    private var config: EEConfig? = null
    private var listener: EEListener? = null
    private var current = EEStatus(state = EEState.UNCONFIGURED)

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
        synchronized(lock) {
            appContext = context.applicationContext
            this.config = config
            if (current.state == EEState.UNCONFIGURED) {
                transition(current.copy(state = EEState.READY))
            }
        }
    }

    /**
     * Begin (or resume) the gateway from the host's service lifecycle.
     * Idempotent and main-thread safe. A no-op if not yet configured.
     *
     * The permission-dormancy gate ([EEState.AWAITING_PERMISSIONS]) and the
     * real scan/upload pipeline land in later chunks (plan §5, C5); in this
     * scaffold [start] flips the state to [EEState.RUNNING].
     *
     * Never throws; never prompts for permissions.
     */
    @JvmStatic
    fun start() {
        synchronized(lock) {
            when (current.state) {
                EEState.UNCONFIGURED -> return   // nothing to start; not an error
                EEState.RUNNING -> return        // idempotent
                else -> transition(current.copy(state = EEState.RUNNING))
            }
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
        synchronized(lock) {
            when (current.state) {
                EEState.UNCONFIGURED, EEState.STOPPED -> return
                else -> transition(current.copy(state = EEState.STOPPED))
            }
        }
    }

    /** The current [EEStatus] snapshot. Never throws. */
    @JvmStatic
    fun status(): EEStatus = synchronized(lock) { current }

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

    /**
     * Commit a new status and notify the listener on the main thread. Called
     * only while holding [lock]; the notification itself is posted, so it never
     * runs under the lock and a slow listener can't stall a caller.
     */
    private fun transition(next: EEStatus) {
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
}
