package com.encryptedenergy.sdk

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * 10 s queue-drain loop (plan §1a). Each tick takes up to one server batch
 * off the queue and hands it to [BatchSender], which owns the published
 * contract (413 split, 422 drop + reasons, transient/401 stop). The loop's
 * jobs are the ones BatchSender deliberately doesn't do: pacing, requeueing
 * the undecided remainder in original order, and bumping the forwarded
 * counters.
 *
 * android-free by design: the wire call is injected (`(String) ->
 * EEClient.Result`) and [onOutcome] fires on the loop's executor thread, so
 * every branch unit-tests on the pure JVM via [tick]. The engine marshals
 * outcomes to the main thread; this class never touches a Looper.
 */
internal class UploadLoop(
    private val ingest: (String) -> EEClient.Result,
    private val queue: PacketQueue,
    private val counters: Counters,
    private val onOutcome: (BatchSender.Outcome) -> Unit = {},
) {

    private var executor: ScheduledExecutorService? = null

    fun start() {
        if (executor != null) return
        executor = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "ee-sdk-upload").apply { isDaemon = true }
        }.also {
            it.scheduleWithFixedDelay(::tick, INITIAL_DELAY_SECONDS, INTERVAL_SECONDS, TimeUnit.SECONDS)
        }
    }

    fun stop() {
        executor?.shutdownNow()
        executor = null
    }

    /** One drain pass. No-op (and no callback) on an empty queue. */
    fun tick() {
        val batch = queue.takeBatch(PacketQueue.MAX_BATCH)
        if (batch.isEmpty()) return
        val outcome = BatchSender.send(batch, ingest)
        // Undecided packets (transient/401) go back to the FRONT so a
        // recovered network uploads oldest-first; capacity re-trim drops the
        // stalest if the queue filled meanwhile.
        if (outcome.requeue.isNotEmpty()) queue.requeue(outcome.requeue)
        repeat(outcome.forwarded) { counters.incForwarded() }
        onOutcome(outcome)
    }

    companion object {
        const val INTERVAL_SECONDS = 10L
        private const val INITIAL_DELAY_SECONDS = 3L
    }
}
