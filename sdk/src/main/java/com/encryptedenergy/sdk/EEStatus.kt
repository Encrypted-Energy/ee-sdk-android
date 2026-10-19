package com.encryptedenergy.sdk

/**
 * An immutable snapshot of what the gateway is doing right now (plan §3).
 * Returned by [EEGateway.status] and handed to [EEListener.onStatusChanged] on
 * every change. android-free so it unit-tests on a plain JVM.
 *
 * Counters are cumulative for the current process; they reset when the host
 * process is recreated, not on [EEGateway.stop]/[EEGateway.start].
 *
 * @property state the current lifecycle state; see [EEState].
 * @property pingsHeard total BLE beacons heard since process start.
 * @property packetsForwarded total packets accepted by ee-web since process start.
 * @property queueDepth packets waiting locally to upload right now.
 * @property gatewayPublicId this device's public gateway id once known, else null.
 * @property lastHeartbeatAt epoch millis of the last successful heartbeat, else null.
 */
data class EEStatus(
    val state: EEState,
    val pingsHeard: Long = 0,
    val packetsForwarded: Long = 0,
    val queueDepth: Int = 0,
    val gatewayPublicId: String? = null,
    val lastHeartbeatAt: Long? = null,    // epoch millis
)
