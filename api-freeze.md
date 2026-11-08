# API freeze

This is the frozen public surface of the EE gateway SDK, as of C1 (0.1.0).

**Frozen-additive from C1 forward.** Once you ship against this surface
we can't force your app to update, so nothing here ever changes meaning,
signature, or removal. We only add: new types, new optional parameters with
defaults, new enum entries appended at the end. A `when` you wrote over
`EEState` in C1 keeps compiling, and a call you wrote in C1 keeps behaving.

Anything not listed below is internal and may change at any time.

```kotlin
object EEGateway {
    const val VERSION: String   // the release you built against
    @JvmStatic fun configure(context: Context, config: EEConfig)
    @JvmStatic fun start()
    @JvmStatic fun stop()
    @JvmStatic fun status(): EEStatus
    @JvmStatic fun setListener(listener: EEListener?)
}

sealed interface EEConfig {
    val baseUrl: String
    data class GatewayToken(
        val token: String,
        override val baseUrl: String = DEFAULT_BASE_URL,
    ) : EEConfig
    data class SelfMint(
        val deviceName: String? = null,   // null -> Build.MODEL at mint time
        override val baseUrl: String = DEFAULT_BASE_URL,
    ) : EEConfig
    companion object { const val DEFAULT_BASE_URL = "https://encryptedenergy.com" }
}

data class EEStatus(
    val state: EEState,
    val pingsHeard: Long = 0,
    val packetsForwarded: Long = 0,
    val queueDepth: Int = 0,
    val gatewayPublicId: String? = null,
    val lastHeartbeatAt: Long? = null,    // epoch millis
)

enum class EEState { UNCONFIGURED, READY, AWAITING_PERMISSIONS, RUNNING, PAUSED_REMOTE, STOPPED }

fun interface EEListener { fun onStatusChanged(status: EEStatus) }
```

## Behavioral contracts (frozen too)

- Public API never throws. Every boundary is crash-isolated.
- Never prompts for permissions. `start()` with grants missing lands in
  `AWAITING_PERMISSIONS`; it does not request.
- No foreground service of its own. Your app drives the lifecycle.
- `configure()` is idempotent and holds only `applicationContext`, never an
  Activity.
- `start()` / `stop()` are idempotent and main-thread safe.
- `PAUSED_REMOTE` is the server-driven kill switch (heartbeat response
  `"disable": true`): scanning and upload stop, heartbeats continue.
- Listener callbacks arrive on the main thread and are crash-isolated.
