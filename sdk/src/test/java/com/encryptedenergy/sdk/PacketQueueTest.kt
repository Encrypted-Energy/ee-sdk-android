package com.encryptedenergy.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketQueueTest {

    private fun packet(n: Int) = UploadPacket(
        eidHex = "eid$n",
        payloadB64 = "AQID",
        rssi = -50,
        timestampSeconds = 1_785_545_352L + n,
        latitude = 40.5,
        longitude = -74.25,
        positionAtSeconds = 1_785_545_340L + n,
        accuracyM = 14.0,
    )

    @Test
    fun fifoOrder() {
        val q = PacketQueue()
        (1..3).forEach { q.add(packet(it)) }
        assertEquals(listOf("eid1", "eid2", "eid3"), q.takeBatch().map { it.eidHex })
        assertEquals(0, q.size())
    }

    @Test
    fun takeBatchRespectsMaxAndLeavesRest() {
        val q = PacketQueue()
        (1..5).forEach { q.add(packet(it)) }
        assertEquals(listOf("eid1", "eid2"), q.takeBatch(2).map { it.eidHex })
        assertEquals(3, q.size())
    }

    @Test
    fun overflowDropsOldest() {
        val q = PacketQueue(capacity = 3)
        (1..4).forEach { q.add(packet(it)) }
        assertEquals(listOf("eid2", "eid3", "eid4"), q.takeBatch().map { it.eidHex })
    }

    @Test
    fun requeuePutsBatchBackInFrontInOrder() {
        val q = PacketQueue()
        (1..4).forEach { q.add(packet(it)) }
        val batch = q.takeBatch(2) // [1, 2]; [3, 4] remain
        q.requeue(batch)
        assertEquals(listOf("eid1", "eid2", "eid3", "eid4"), q.takeBatch().map { it.eidHex })
    }

    @Test
    fun requeueRetrimsToCapacity() {
        val q = PacketQueue(capacity = 3)
        (1..3).forEach { q.add(packet(it)) }
        val batch = q.takeBatch(2) // [1, 2]; [3] remains
        q.add(packet(4))
        q.add(packet(5)) // [3, 4, 5] — full again
        q.requeue(batch) // 5 packets into capacity 3: oldest go
        assertEquals(3, q.size())
        assertEquals(listOf("eid3", "eid4", "eid5"), q.takeBatch().map { it.eidHex })
    }

    @Test
    fun maxBatchMatchesServerLimit() {
        // ee-web MAX_PACKETS_PER_REQUEST is 500; a bigger batch 413s.
        assertTrue(PacketQueue.MAX_BATCH <= 500)
    }
}
