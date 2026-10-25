package com.encryptedenergy.sdk

/**
 * Bounded FIFO between the scan callback (main thread) and the upload
 * executor (plan §1a). Overflow drops the OLDEST packet — recent coverage is
 * worth more than stale backlog, and the bound keeps a dead network from
 * growing the heap forever.
 *
 * [onChange] fires after any mutation that changed contents; the engine uses
 * it to schedule a QueueStore persist so the queue survives process death
 * (0005). The callback must be cheap and non-blocking.
 */
internal class PacketQueue(private val capacity: Int = DEFAULT_CAPACITY) {

    private val deque = ArrayDeque<UploadPacket>()

    var onChange: (() -> Unit)? = null

    @Synchronized
    fun add(packet: UploadPacket) {
        if (deque.size >= capacity) deque.removeFirst()
        deque.addLast(packet)
        onChange?.invoke()
    }

    /** Removes and returns up to [max] packets, oldest first. */
    @Synchronized
    fun takeBatch(max: Int = MAX_BATCH): List<UploadPacket> {
        val n = minOf(max, deque.size)
        val batch = List(n) { deque.removeFirst() }
        if (n > 0) onChange?.invoke()
        return batch
    }

    /**
     * Returns a failed batch to the FRONT in original order (transient
     * failure — retry next tick). Re-trims to capacity, dropping oldest.
     */
    @Synchronized
    fun requeue(batch: List<UploadPacket>) {
        if (batch.isEmpty()) return
        for (packet in batch.asReversed()) deque.addFirst(packet)
        while (deque.size > capacity) deque.removeFirst()
        onChange?.invoke()
    }

    /** Copy of current contents, oldest first — used for persistence. */
    @Synchronized
    fun snapshot(): List<UploadPacket> = deque.toList()

    @Synchronized fun size(): Int = deque.size

    @Synchronized
    fun clear() {
        if (deque.isEmpty()) return
        deque.clear()
        onChange?.invoke()
    }

    companion object {
        const val DEFAULT_CAPACITY = 1_000
        /** ee-web MAX_PACKETS_PER_REQUEST — larger batches 413. */
        const val MAX_BATCH = 500
    }
}
