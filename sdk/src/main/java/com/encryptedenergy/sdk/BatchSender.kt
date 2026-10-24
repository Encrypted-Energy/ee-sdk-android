package com.encryptedenergy.sdk

/**
 * Batch semantics for packet upload, per the published ingest contract
 * (ee-web /docs):
 *
 *  - at most [MAX_BATCH] packets per request — pre-chunked here;
 *  - 413 (server disagrees about the limit): split the batch and resend;
 *  - 422: drop the chunk — retrying can't help — and surface the body's
 *    `invalid_reasons`;
 *  - other non-401 4xx: drop (ee-web already said it will never be accepted);
 *  - 401: stop, return everything unsent — the operator's token is the
 *    problem, not the packets;
 *  - 502/5xx/408/429/network: stop, return everything unsent for the next
 *    pass (the C5 loop requeues; pacing is the loop's job).
 *
 * Pure JVM: the wire call is injected, so every branch above unit-tests
 * without HTTP. The C5 upload loop wires this to [EEClient.ingest] and
 * [PacketQueue].
 */
internal object BatchSender {

    /** ee-web MAX_PACKETS_PER_REQUEST — Hubble's per-upload ceiling. */
    const val MAX_BATCH = 500

    /**
     * What one [send] pass did. [requeue] is every packet that was not
     * decided (transient failure or 401) in original order — the caller puts
     * them back. [droppedReasons] carries 422 `invalid_reasons` lines for
     * diagnostics.
     */
    data class Outcome(
        val forwarded: Int,
        val bountyEarned: Int,
        val dropped: Int,
        val droppedReasons: List<String>,
        val requeue: List<UploadPacket>,
        val unauthorized: Boolean,
        val transientReason: String?,
    )

    /**
     * Send [batch] through [ingest] (one wire call per ≤[MAX_BATCH] chunk),
     * applying the contract above. Stops at the first transient/401 outcome;
     * everything undecided comes back in [Outcome.requeue].
     */
    fun send(batch: List<UploadPacket>, ingest: (String) -> EEClient.Result): Outcome {
        var forwarded = 0
        var bounty = 0
        var dropped = 0
        val reasons = mutableListOf<String>()
        val pending = ArrayDeque(batch.chunked(MAX_BATCH))

        while (pending.isNotEmpty()) {
            val chunk = pending.removeFirst()
            val result = try {
                ingest(PacketJson.body(chunk))
            } catch (e: Exception) {
                // The injected call is EEClient.ingest, which never throws;
                // this guard keeps a C5 wiring mistake from killing the loop.
                EEClient.Result.Transient(e.javaClass.simpleName)
            }
            when (result) {
                is EEClient.Result.Ok -> {
                    forwarded += chunk.size
                    bounty += result.bountyEarned
                }
                is EEClient.Result.Unauthorized ->
                    return Outcome(
                        forwarded, bounty, dropped, reasons,
                        requeue = chunk + pending.flatten(),
                        unauthorized = true, transientReason = null,
                    )
                is EEClient.Result.Transient ->
                    return Outcome(
                        forwarded, bounty, dropped, reasons,
                        requeue = chunk + pending.flatten(),
                        unauthorized = false, transientReason = result.reason,
                    )
                is EEClient.Result.Terminal ->
                    if (result.code == 413 && chunk.size > 1) {
                        // Server's limit is lower than ours: halve until it
                        // fits. A size-1 413 falls through to the drop arm —
                        // unsplittable, and retrying can't change the answer.
                        val mid = chunk.size / 2
                        pending.addFirst(chunk.subList(mid, chunk.size))
                        pending.addFirst(chunk.subList(0, mid))
                    } else {
                        dropped += chunk.size
                        if (result.code == 422) {
                            reasons += EEClient.parseInvalidReasons(result.body)
                        }
                    }
            }
        }
        return Outcome(forwarded, bounty, dropped, reasons, emptyList(), false, null)
    }
}
