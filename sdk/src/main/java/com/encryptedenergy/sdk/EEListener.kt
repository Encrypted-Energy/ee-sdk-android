package com.encryptedenergy.sdk

/**
 * Status callback (plan §3). Register one with [EEGateway.setListener] to be
 * told whenever the [EEStatus] changes instead of polling [EEGateway.status].
 *
 * Contract:
 *  - Callbacks always arrive on the main (UI) thread.
 *  - Callbacks are crash-isolated: if your implementation throws, the SDK
 *    swallows it and keeps running. A buggy listener can never break the
 *    gateway or the host app.
 *
 * It's a `fun interface`, so a partner can pass a lambda:
 * `EEGateway.setListener { status -> ... }`.
 *
 * android-free so it unit-tests on a plain JVM.
 */
fun interface EEListener {
    /** Called on the main thread with the new snapshot after any change. */
    fun onStatusChanged(status: EEStatus)
}
