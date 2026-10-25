package com.encryptedenergy.sdk

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueStoreTest {

    private fun tempFile(): File =
        File.createTempFile("eeq-test", ".eeq").apply { deleteOnExit() }

    private val packet = UploadPacket(
        eidHex = "5f982837",
        payloadB64 = "A1NfmCg3HoGjWrKY3zg=",
        rssi = -50,
        timestampSeconds = 1_785_545_352,
        latitude = 47.587223436883434,
        longitude = -122.07973720240967,
        positionAtSeconds = 1_785_545_340,
        accuracyM = 13.988265225086755,
    )

    @Test
    fun roundTripsExactly() {
        val store = QueueStore(tempFile())
        store.persistNow(listOf(packet, packet.copy(eidHex = "baeb60", accuracyM = null)))
        val loaded = store.load()
        assertEquals(2, loaded.size)
        assertEquals(packet, loaded[0])
        assertEquals("baeb60", loaded[1].eidHex)
        assertNull(loaded[1].accuracyM)
    }

    @Test
    fun missingFileLoadsEmpty() {
        val f = tempFile()
        f.delete()
        assertTrue(QueueStore(f).load().isEmpty())
    }

    @Test
    fun emptyQueuePersistsAndLoadsEmpty() {
        val store = QueueStore(tempFile())
        store.persistNow(emptyList())
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun malformedLinesAreSkipped() {
        val f = tempFile()
        val store = QueueStore(f)
        store.persistNow(listOf(packet))
        f.appendText("garbage|line\n")
        f.appendText("a|b|notanint|4|5|6|7|8\n")
        val loaded = store.load()
        assertEquals(1, loaded.size)
        assertEquals(packet, loaded[0])
    }

    @Test
    fun formatMarkerLineIsNotAPacket() {
        val store = QueueStore(tempFile())
        store.persistNow(listOf(packet))
        // Marker "eeq1" must not decode into a packet on load.
        assertEquals(1, store.load().size)
    }

    @Test
    fun decodeRejectsWrongFieldCount() {
        assertNull(QueueStore.decode("a|b|c"))
        assertNull(QueueStore.decode(""))
    }

    @Test
    fun encodeIsPipeSafe() {
        // No field of a valid packet can contain '|' (base64, hex, decimal),
        // so split-on-pipe round-trips: field count stays 8.
        assertEquals(8, QueueStore.encode(packet).split("|").size)
    }
}
