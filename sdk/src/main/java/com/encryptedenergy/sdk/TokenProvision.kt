package com.encryptedenergy.sdk

/**
 * Token provisioning invariants (plan §3). Pure: verify/mint/persist are
 * injected, so the one rule that matters — NOTHING persists until the server
 * said yes — unit-tests without Keystore or HTTP.
 *
 * Verify-before-persist: a token handed to `configure()` ([EEConfig
 * .GatewayToken]) is proven against /gateways/verify before it touches the
 * [TokenStore], so a typo'd, revoked, or stale token can never poison stored
 * state. Transient failures persist nothing either — the caller retries with
 * [Backoff] and the previous stored token (if any) stays authoritative.
 *
 * The facade (C6) wires these to the real [EEClient] and [TokenStore] and
 * maps outcomes onto [EEState]; the loops (C5) only ever read through the
 * store, so a rejected injection is invisible to them.
 */
internal object TokenProvision {

    sealed class InjectOutcome {
        /** Server accepted the token; it is now persisted. */
        object Verified : InjectOutcome()

        /** Server rejected the token (401/4xx). NOT persisted. */
        data class Rejected(val code: Int) : InjectOutcome()

        /** Couldn't reach a verdict (network/5xx/429). NOT persisted; retry. */
        data class Retry(val reason: String) : InjectOutcome()
    }

    sealed class MintOutcome {
        /** Org + gateway minted; key and name are now persisted. */
        data class Provisioned(val gatewayName: String) : MintOutcome()

        /** Server said no (attestation/shape). NOT persisted; surfaced. */
        data class Rejected(val reason: String) : MintOutcome()

        /** 503/429 mint cap or network. NOT persisted; retry with [Backoff]. */
        object Retry : MintOutcome()
    }

    /**
     * Prove [token] against the server, then persist it — in that order.
     * [verify] is `EEClient.verify(tokenOverride = token)` in production.
     */
    fun injectToken(
        token: String,
        verify: (String) -> EEClient.Result,
        persist: (String) -> Unit,
    ): InjectOutcome = when (val r = verify(token)) {
        is EEClient.Result.Ok -> {
            persist(token)
            InjectOutcome.Verified
        }
        is EEClient.Result.Unauthorized -> InjectOutcome.Rejected(401)
        // verify only answers 200/401 today; any other 4xx still means the
        // server understood us and said no — never persist on a guess.
        is EEClient.Result.Terminal -> InjectOutcome.Rejected(r.code)
        is EEClient.Result.Transient -> InjectOutcome.Retry(r.reason)
    }

    /**
     * Self-mint ([EEConfig.SelfMint]): one mint call; persist key + name only
     * on [EEClient.MintResult.Minted]. [mint] is `EEClient.mintDevice` in
     * production; the mint response's key needs no verify round-trip — the
     * server that just created it is the same server that vouched for it.
     */
    fun selfMint(
        deviceName: String,
        mint: (String) -> EEClient.MintResult,
        persist: (token: String, gatewayName: String) -> Unit,
    ): MintOutcome = when (val r = mint(deviceName)) {
        is EEClient.MintResult.Minted -> {
            persist(r.apiKey, r.gatewayName)
            MintOutcome.Provisioned(r.gatewayName)
        }
        is EEClient.MintResult.Rejected -> MintOutcome.Rejected(r.reason)
        EEClient.MintResult.Transient -> MintOutcome.Retry
    }
}
