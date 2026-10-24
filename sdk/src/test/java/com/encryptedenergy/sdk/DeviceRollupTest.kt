package com.encryptedenergy.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceRollupTest {

    private val t0 = 1_785_545_340_000L

    @Test
    fun recordsAndShapesLikeIos() {
        val r = DeviceRollup()
        r.record("5f982837", -54, t0)
        r.record("5f982837", -57, t0 + 1_000)
        r.record("3c9b25df", -73, t0 + 2_000)
        val json = r.toJson(t0 + 3_000).text
        assertTrue(json, json.contains("\"as_of\":\""))
        assertTrue(json, json.contains("\"window_hours\":24"))
        assertTrue(json, json.contains("\"eid\":\"5f982837\""))
        assertTrue(json, json.contains("\"packets\":2"))
        assertTrue(json, json.contains("\"last_rssi\":-57"))
        assertTrue(json, json.contains("\"eid\":\"3c9b25df\""))
        assertTrue(json, json.contains("\"last_seen_at\":\""))
    }

    @Test
    fun emptyRollupIsValidBlock() {
        val json = DeviceRollup().toJson(t0).text
        assertTrue(json, json.contains("\"devices\":[]"))
    }

    @Test
    fun prunesBeyondWindow() {
        val r = DeviceRollup()
        r.record("old00", -60, t0)
        r.record("new00", -60, t0 + DeviceRollup.DEFAULT_WINDOW_MILLIS)
        val json = r.toJson(t0 + DeviceRollup.DEFAULT_WINDOW_MILLIS + 1_000).text
        assertFalse(json, json.contains("old00"))
        assertTrue(json, json.contains("new00"))
        assertEquals(1, r.deviceCount(t0 + DeviceRollup.DEFAULT_WINDOW_MILLIS + 1_000))
    }

    @Test
    fun capsAtServerMaxKeepingMostRecent() {
        val r = DeviceRollup()
        (1..60).forEach { r.record("eid$it", -60, t0 + it * 1_000L) }
        val json = r.toJson(t0 + 61_000).text
        // 50 device objects, newest kept, oldest 10 dropped.
        assertEquals(DeviceRollup.MAX_DEVICES, Regex("\"eid\":").findAll(json).count())
        assertTrue(json, json.contains("\"eid\":\"eid60\""))
        assertFalse(json, json.contains("\"eid\":\"eid10\""))
    }

    @Test
    fun lastSeenNeverMovesBackwards() {
        val r = DeviceRollup()
        r.record("5f982837", -54, t0 + 5_000)
        r.record("5f982837", -60, t0 + 1_000) // late-arriving older packet
        val json = r.toJson(t0 + 6_000).text
        assertTrue(json, json.contains("\"last_seen_at\":\"${HeartbeatPayload.isoUtc(t0 + 5_000)}\""))
    }

    // --- 0006 UI accessors ---

    @Test
    fun snapshotIsNewestFirstAndTyped() {
        val r = DeviceRollup()
        r.record("aa", -54, t0)
        r.record("bb", -70, t0 + 2_000)
        r.record("aa", -57, t0 + 5_000)
        val snap = r.snapshot(t0 + 6_000)
        assertEquals(listOf("aa", "bb"), snap.map { it.eidHex })
        assertEquals(2, snap[0].packets)
        assertEquals(-57, snap[0].lastRssi)
        assertEquals(t0 + 5_000, snap[0].lastSeenAtMillis)
    }

    @Test
    fun snapshotPrunesAndCaps() {
        val r = DeviceRollup()
        r.record("old00", -60, t0)
        (1..55).forEach { r.record("eid$it", -60, t0 + DeviceRollup.DEFAULT_WINDOW_MILLIS + it) }
        val snap = r.snapshot(t0 + DeviceRollup.DEFAULT_WINDOW_MILLIS + 60_000)
        assertEquals(DeviceRollup.MAX_DEVICES, snap.size)
        assertFalse(snap.any { it.eidHex == "old00" })
    }

    @Test
    fun packets24hSumsAcrossDevicesWithinWindow() {
        val r = DeviceRollup()
        r.record("aa", -54, t0)
        r.record("aa", -54, t0 + 1_000)
        r.record("bb", -70, t0 + 2_000)
        assertEquals(3, r.packets24h(t0 + 3_000))
        // Window rolls: aa ages out entirely, bb stays.
        r.record("bb", -70, t0 + DeviceRollup.DEFAULT_WINDOW_MILLIS + 1_500)
        assertEquals(2, r.packets24h(t0 + DeviceRollup.DEFAULT_WINDOW_MILLIS + 2_000))
    }
}
