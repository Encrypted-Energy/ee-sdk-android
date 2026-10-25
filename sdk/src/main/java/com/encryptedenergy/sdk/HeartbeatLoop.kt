package com.encryptedenergy.sdk

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Heartbeat loop (plan §1b): fires immediately on start, then every
 * [INTERVAL_SECONDS]. The SDK's cadence is 5 MINUTES, not the first-party
 * app's 60 s — conservative battery/network cost on someone else's fleet is
 * a hard contract (plan §6 risk 3), and the architecture diagram pins it.
 *
 * Deltas are snapshot-and-reset before the POST and restored on transient
 * failure so nothing is lost; terminal failures (401/4xx) drop the snapshot,
 * copying the Pi worker. A 2xx carries the C9 `disable` kill-switch flag
 * through [onResult] — surfaced here, ACTED ON in C6.
 *
 * android-free by design: the wire call is injected and [onResult] fires on
 * the loop's executor thread, so every branch unit-tests on the pure JVM via
 * [tick]. The engine marshals results to the main thread.
 */
internal class HeartbeatLoop(
    private val heartbeat: (String) -> EEClient.Result,
    private val counters: Counters,
    private val runtimeInfo: () -> HeartbeatPayload.Runtime,
    private val onResult: (EEClient.Result) -> Unit = {},
) {

    private var executor: ScheduledExecutorService? = null

    fun start() {
        if (executor != null) return
        executor = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "ee-sdk-heartbeat").apply { isDaemon = true }
        }.also {
            it.scheduleWithFixedDelay(::tick, 0, INTERVAL_SECONDS, TimeUnit.SECONDS)
        }
    }

    fun stop() {
        executor?.shutdownNow()
        executor = null
    }

    /** One heartbeat POST. */
    fun tick() {
        val snapshot = counters.snapshotAndReset()
        val result = try {
            heartbeat(HeartbeatPayload.build(runtimeInfo(), snapshot))
        } catch (e: Exception) {
            // The injected call is EEClient.heartbeat, which never throws;
            // this guard keeps an engine wiring mistake from killing the loop
            // (an uncaught exception cancels scheduleWithFixedDelay forever).
            EEClient.Result.Transient(e.javaClass.simpleName)
        }
        if (result is EEClient.Result.Transient) counters.restore(snapshot)
        onResult(result)
    }

    companion object {
        /** 5 min — the SDK's conservative fleet cadence (plan §3 diagram). */
        const val INTERVAL_SECONDS = 300L
    }
}
