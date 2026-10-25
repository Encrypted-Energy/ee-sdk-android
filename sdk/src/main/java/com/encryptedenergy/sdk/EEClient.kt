package com.encryptedenergy.sdk

import java.net.HttpURLConnection
import java.net.URL

/**
 * ee-web API client, ported from gateway-android 0.6.0 with the app-only
 * claim flow removed (a fleet SDK has no end user to attach; the partner's
 * org already owns every gateway). Zero third-party deps: HttpURLConnection,
 * 10 s timeouts, Bearer auth. All methods are BLOCKING — call from a
 * background thread, never the main looper.
 *
 * Response mapping copies the Pi worker: 2xx ok; 401 terminal-unauthorized;
 * other 4xx terminal; 408/429/5xx/network transient (§1a, §1b). Terminal
 * results keep the response body so the caller can surface 422
 * `invalid_reasons` (see [BatchSender]).
 */
internal class EEClient(
    private val tokenProvider: () -> String?,
    private val baseUrl: String = EEConfig.DEFAULT_BASE_URL,
) {

    sealed class Result {
        /**
         * 2xx. [bountyEarned] is the server's count of bounty events THIS
         * batch minted (first-to-report wins only; ee-web 19f4803) — 0 when
         * the field is absent or this isn't an ingest response. [disable] is
         * the heartbeat response's fleet kill switch (`"disable": true`,
         * C9): parsed here, acted on in C6 — absent means enabled.
         */
        data class Ok(
            val code: Int,
            val bountyEarned: Int = 0,
            val disable: Boolean = false,
            /** `gateway.public_id` from a heartbeat 2xx body (C6) — feeds
             *  [EEStatus.gatewayPublicId]; null when absent/malformed. */
            val publicId: String? = null,
        ) : Result()
        object Unauthorized : Result()
        /** Non-401 4xx. [body] survives so 422 invalid_reasons can be read. */
        data class Terminal(val code: Int, val body: String? = null) : Result()
        data class Transient(val reason: String) : Result()
    }

    /** POST /api/v1/devices outcome (anonymous self-mint, no token yet). */
    sealed class MintResult {
        /** 2xx: org + gateway + key created. Save the key, start scanning. */
        data class Minted(val apiKey: String, val gatewayName: String) : MintResult()
        /** 403 attestation / 422 shape / other 4xx: server said no, surfaced. */
        data class Rejected(val reason: String) : MintResult()
        /** Network / 5xx / 429: worth retrying with [Backoff] (300/hr global cap). */
        object Transient : MintResult()
    }

    /**
     * GET /api/v1/gateways/verify — token check. [tokenOverride] exists for
     * verify-before-persist: a token handed to `configure()` is proven against
     * the server BEFORE it ever reaches the TokenStore (C4), so a typo'd or
     * revoked token can't poison stored state.
     */
    fun verify(tokenOverride: String? = null): Result =
        request("GET", "/api/v1/gateways/verify", null, "verify", tokenOverride)

    /**
     * POST /api/v1/gateways/heartbeat — the telemetry ping (§1b). The 2xx
     * body is parsed for the C9 `disable` flag ([Result.Ok.disable]).
     */
    fun heartbeat(payloadJson: String): Result {
        val token = tokenProvider() ?: return Result.Unauthorized
        val http = send("POST", "$baseUrl/api/v1/gateways/heartbeat", token, payloadJson, "heartbeat")
            ?: return Result.Transient("network")
        return map(http) { code, body ->
            Result.Ok(code, disable = parseDisable(body), publicId = parsePublicId(body))
        }
    }

    /**
     * POST /api/v1/gateways/packets — batch BLE packet upload (§1a). The 2xx
     * body is parsed for `bounty_earned`. Batch semantics (500 max, 413
     * split, 422 drop) live in [BatchSender]; this is one wire call.
     */
    fun ingest(batchJson: String): Result {
        val token = tokenProvider() ?: return Result.Unauthorized
        val http = send("POST", "$baseUrl/api/v1/gateways/packets", token, batchJson, "ingest")
            ?: return Result.Transient("network")
        return map(http) { code, body -> Result.Ok(code, bountyEarned = parseBountyEarned(body)) }
    }

    private fun request(
        method: String,
        path: String,
        body: String?,
        tag: String,
        tokenOverride: String? = null,
    ): Result {
        val token = tokenOverride ?: tokenProvider() ?: return Result.Unauthorized
        val http = send(method, baseUrl + path, token, body, tag)
            ?: return Result.Transient("network")
        return map(http) { code, _ -> Result.Ok(code) }
    }

    companion object {
        const val USER_AGENT_PREFIX = "ee-sdk-android"
        private const val TIMEOUT_MILLIS = 10_000

        /** HTTP code + response body text; null on a network-level failure. */
        private class Http(val code: Int, val body: String?)

        /** The shared worker status mapping; [onOk] shapes the 2xx result. */
        private inline fun map(http: Http, onOk: (Int, String?) -> Result): Result =
            when (val code = http.code) {
                in 200..299 -> onOk(code, http.body)
                401 -> Result.Unauthorized
                408, 429 -> Result.Transient("HTTP $code")
                in 400..499 -> Result.Terminal(code, http.body)
                else -> Result.Transient("HTTP $code")
            }

        /**
         * One blocking request. Reads the 2xx input stream or the error
         * stream so 4xx/5xx bodies (mint errors, 422 invalid_reasons)
         * survive. [token] null omits the Authorization header — the mint
         * path has no key yet. Returns null on any network-level exception.
         */
        private fun send(
            method: String,
            urlString: String,
            token: String?,
            body: String?,
            tag: String,
        ): Http? = try {
            val conn = URL(urlString).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = method
                conn.connectTimeout = TIMEOUT_MILLIS
                conn.readTimeout = TIMEOUT_MILLIS
                if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
                conn.setRequestProperty("User-Agent", "$USER_AGENT_PREFIX/$tag")
                if (body != null) {
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.doOutput = true
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.use { String(it.readBytes(), Charsets.UTF_8) }
                Http(code, text)
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            null
        }

        /**
         * POST /api/v1/devices (ee-web 19f4803) — self-mint mode
         * ([EEConfig.SelfMint]). The one call that happens before any token
         * exists, so it's static — no [tokenProvider]. attestation_token is
         * omitted: the server runs DEVICE_ATTESTATION in log mode and allows
         * its absence. Transient outcomes (503/429: the 300/hr global mint
         * cap) must be retried through [Backoff], never in a tight loop.
         */
        fun mintDevice(deviceName: String, baseUrl: String = EEConfig.DEFAULT_BASE_URL): MintResult {
            val body = Json.obj(listOf("platform" to "android", "device_name" to deviceName))
            val http = send("POST", "$baseUrl/api/v1/devices", null, body, "mint")
                ?: return MintResult.Transient
            return mapMint(http.code, http.body, fallbackName = deviceName)
        }

        // --- Pure response mappers: status + body -> outcome. Kept free of
        // any HTTP so the parse/branch logic is unit-tested on the JVM
        // (org.json is stubbed; these ride Json.parse instead). ---

        /** `bounty_earned` from an ingest 2xx body; 0 if absent or malformed. */
        fun parseBountyEarned(body: String?): Int =
            body?.let { runCatching { Json.int(Json.parse(it), "bounty_earned") }.getOrNull() } ?: 0

        /**
         * `disable` from a heartbeat 2xx body (C9 fleet kill switch); false
         * if absent or malformed — a parse failure must never pause a fleet.
         */
        fun parseDisable(body: String?): Boolean =
            body?.let { runCatching { Json.bool(Json.parse(it), "disable") }.getOrNull() } ?: false

        /**
         * `gateway.public_id` from a heartbeat/verify 2xx body; null if
         * absent or malformed — identity is a nicety, never a failure.
         */
        fun parsePublicId(body: String?): String? =
            body?.let {
                runCatching { Json.str(Json.child(Json.parse(it), "gateway"), "public_id") }
                    .getOrNull()
            }

        /** `invalid_reasons` from an ingest 422 body; empty if absent/malformed. */
        fun parseInvalidReasons(body: String?): List<String> =
            body?.let {
                runCatching { Json.strings(Json.parse(it), "invalid_reasons") }.getOrNull()
            } ?: emptyList()

        fun mapMint(code: Int, body: String?, fallbackName: String): MintResult {
            val node = body?.let { runCatching { Json.parse(it) }.getOrNull() }
            return when (code) {
                in 200..299 -> {
                    val key = Json.str(node, "api_key")
                    if (key == null || !key.startsWith("ee_live_")) {
                        MintResult.Rejected("mint response missing api_key")
                    } else {
                        MintResult.Minted(key, Json.str(Json.child(node, "gateway"), "name") ?: fallbackName)
                    }
                }
                408, 429, in 500..599 -> MintResult.Transient
                else -> MintResult.Rejected(Json.str(node, "error") ?: "mint failed ($code)")
            }
        }
    }
}
