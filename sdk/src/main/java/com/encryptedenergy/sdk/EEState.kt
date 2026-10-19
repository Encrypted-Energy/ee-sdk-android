package com.encryptedenergy.sdk

/**
 * The lifecycle states the gateway can be in (plan §3). Every [EEStatus]
 * carries exactly one of these. android-free so it unit-tests on a plain JVM.
 */
enum class EEState {
    /** No [EEConfig] yet. [EEGateway.configure] has not been called. */
    UNCONFIGURED,

    /** Configured and idle. Waiting for [EEGateway.start]. */
    READY,

    /**
     * Started, but the host app is missing a scan or location grant, so the
     * pipeline is dormant. The SDK never prompts; the state simply reports what
     * it's waiting on. It advances to [RUNNING] on its own once the host holds
     * the grants and start is in effect.
     */
    AWAITING_PERMISSIONS,

    /** Scanning, forwarding packets, and sending heartbeats. */
    RUNNING,

    /**
     * The server-driven kill switch (plan §3, §4.1). A heartbeat response of
     * `{"disable": true}` lands here: scanning and upload stop, but heartbeats
     * keep going so the fleet re-enables the instant the flag clears. Not a
     * host action and not an error.
     */
    PAUSED_REMOTE,

    /** [EEGateway.stop] has been called. Idle until the next start. */
    STOPPED,
}
