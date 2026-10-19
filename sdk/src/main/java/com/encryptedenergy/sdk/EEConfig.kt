package com.encryptedenergy.sdk

/**
 * How this device gets its gateway identity (plan §3). Pass one of the
 * subtypes to [EEGateway.configure]; everything else about the SDK is the
 * same regardless of which you pick.
 *
 * This type is deliberately android-free so the whole config surface
 * unit-tests on a plain JVM and a partner can build a config object anywhere,
 * not just on a live Android thread.
 */
sealed interface EEConfig {

    /** ee-web base URL every network call hangs off. Override only for staging. */
    val baseUrl: String

    /**
     * Primary, production mode (plan §3). The partner's backend provisions one
     * gateway per device through the Org API and hands the device its
     * `ee_live_` [token] over the partner's own authenticated channel. No EE
     * credential ever lives in the APK, so there's nothing to extract.
     *
     * @property token the per-device gateway token from the partner backend.
     * @property baseUrl ee-web base URL; leave as [DEFAULT_BASE_URL].
     */
    data class GatewayToken(
        val token: String,
        override val baseUrl: String = DEFAULT_BASE_URL,
    ) : EEConfig

    /**
     * Secondary, self-mint mode (plan §3) for demos and backend-less partners.
     * The device mints its own org/gateway on first run. Economics diverge from
     * production (multi-org overpays on fleet overlap), so this is a demo tool,
     * not a production path.
     *
     * @property deviceName name to register the gateway under; null means use
     *   `Build.MODEL` read at mint time (so an android-free config stays
     *   android-free).
     * @property baseUrl ee-web base URL; leave as [DEFAULT_BASE_URL].
     */
    data class SelfMint(
        val deviceName: String? = null,   // null -> Build.MODEL at mint time
        override val baseUrl: String = DEFAULT_BASE_URL,
    ) : EEConfig

    companion object {
        /** Production ee-web. */
        const val DEFAULT_BASE_URL = "https://encryptedenergy.com"
    }
}
