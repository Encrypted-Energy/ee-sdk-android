package com.encryptedenergy.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The published ingest contract, branch by branch: 500 max per request,
 * 413 split, 422 drop + invalid_reasons, transient/401 stop-and-requeue.
 */
class BatchSenderTest {

    private fun packet(i: Int) = UploadPacket(
        eidHex = "eid$i",
        payloadB64 = "cGF5bG9hZA==",
        rssi = -60,
        timestampSeconds = 1_783_780_342L + i,
        latitude = 40.72,
        longitude = -74.04,
        positionAtSeconds = 1_783_780_342L + i,
        accuracyM = 12.0,
    )

    private fun batch(n: Int) = (0 until n).map { packet(it) }

    /** Counts the packets in one ingest body without a real JSON parser. */
    private fun packetCount(body: String) = Regex("\"payload_b64\"").findAll(body).count()

    @Test
    fun smallBatchForwardsInOneCall() {
        val bodies = mutableListOf<String>()
        val out = BatchSender.send(batch(3)) { body ->
            bodies.add(body)
            EEClient.Result.Ok(200, bountyEarned = 2)
        }
        assertEquals(1, bodies.size)
        assertEquals(3, out.forwarded)
        assertEquals(2, out.bountyEarned)
        assertEquals(0, out.dropped)
        assertTrue(out.requeue.isEmpty())
        assertFalse(out.unauthorized)
        assertNull(out.transientReason)
    }

    @Test
    fun oversizeBatchIsPreChunkedTo500() {
        val sizes = mutableListOf<Int>()
        val out = BatchSender.send(batch(1_201)) { body ->
            sizes.add(packetCount(body))
            EEClient.Result.Ok(200)
        }
        assertEquals(listOf(500, 500, 201), sizes)
        assertEquals(1_201, out.forwarded)
    }

    @Test
    fun bountyAccumulatesAcrossChunks() {
        var call = 0
        val out = BatchSender.send(batch(600)) {
            call++
            EEClient.Result.Ok(200, bountyEarned = call)
        }
        assertEquals(3, out.bountyEarned)   // 1 + 2
    }

    @Test
    fun on413TheChunkSplitsAndResends() {
        val sizes = mutableListOf<Int>()
        val out = BatchSender.send(batch(10)) { body ->
            val n = packetCount(body)
            sizes.add(n)
            if (n > 5) EEClient.Result.Terminal(413) else EEClient.Result.Ok(200)
        }
        assertEquals(listOf(10, 5, 5), sizes)
        assertEquals(10, out.forwarded)
        assertEquals(0, out.dropped)
    }

    @Test
    fun a413OnASinglePacketDropsIt() {
        // Unsplittable: retrying a one-packet 413 can't change the answer.
        val out = BatchSender.send(batch(1)) { EEClient.Result.Terminal(413) }
        assertEquals(0, out.forwarded)
        assertEquals(1, out.dropped)
        assertTrue(out.requeue.isEmpty())
    }

    @Test
    fun on422TheChunkDropsAndReasonsSurface() {
        val out = BatchSender.send(batch(4)) {
            EEClient.Result.Terminal(
                422,
                """{"ok":false,"invalid_reasons":["packet[1]: missing eid"]}""",
            )
        }
        assertEquals(0, out.forwarded)
        assertEquals(4, out.dropped)
        assertEquals(listOf("packet[1]: missing eid"), out.droppedReasons)
        assertTrue(out.requeue.isEmpty())
    }

    @Test
    fun otherTerminal4xxDropsWithoutReasons() {
        val out = BatchSender.send(batch(2)) { EEClient.Result.Terminal(400) }
        assertEquals(2, out.dropped)
        assertTrue(out.droppedReasons.isEmpty())
    }

    @Test
    fun transientStopsAndRequeuesEverythingUndecided() {
        var call = 0
        val out = BatchSender.send(batch(1_100)) {
            call++
            if (call == 1) EEClient.Result.Ok(200) else EEClient.Result.Transient("HTTP 502")
        }
        assertEquals(500, out.forwarded)
        assertEquals(600, out.requeue.size)
        assertEquals("HTTP 502", out.transientReason)
        assertFalse(out.unauthorized)
        // Order preserved: the requeue picks up exactly where the 2xx stopped.
        assertEquals("eid500", out.requeue.first().eidHex)
        assertEquals("eid1099", out.requeue.last().eidHex)
    }

    @Test
    fun unauthorizedStopsAndRequeuesEverythingUndecided() {
        val out = BatchSender.send(batch(7)) { EEClient.Result.Unauthorized }
        assertEquals(0, out.forwarded)
        assertEquals(7, out.requeue.size)
        assertTrue(out.unauthorized)
        assertNull(out.transientReason)
    }

    @Test
    fun throwingIngestIsTreatedAsTransient() {
        val out = BatchSender.send(batch(2)) { throw IllegalStateException("wiring bug") }
        assertEquals(2, out.requeue.size)
        assertEquals("IllegalStateException", out.transientReason)
    }

    @Test
    fun emptyBatchMakesNoCalls() {
        var calls = 0
        val out = BatchSender.send(emptyList()) { calls++; EEClient.Result.Ok(200) }
        assertEquals(0, calls)
        assertEquals(0, out.forwarded)
    }
}
