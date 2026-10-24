package com.encryptedenergy.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartbeatPayloadTest {

    private val runtime = HeartbeatPayload.Runtime(
        osVersion = "Android 16 (API 36)",
        uptimeSeconds = 3600,
        lastPacketAtMillis = null,
        gpsStatus = "no_fix",
    )
    private val deltas = Counters.Snapshot(heard = 14, forwarded = 12, droppedNoFix = 0, scanErrors = 0)

    @Test
    fun containsContractFields() {
        val json = HeartbeatPayload.build(runtime, deltas)
        assertTrue(json.contains("\"worker_version\":\"ee-sdk-android/"))
        assertTrue(json.contains("\"platform\":\"android\""))
        assertTrue(json.contains("\"os_version\":\"Android 16 (API 36)\""))
        assertTrue(json.contains("\"uptime_seconds\":3600"))
        assertTrue(json.contains("\"gps_status\":\"no_fix\""))
        assertTrue(json.contains("\"packets_heard_delta\":14"))
        assertTrue(json.contains("\"packets_forwarded_delta\":12"))
        assertTrue(json.contains("\"packets_dropped_no_fix_delta\":0"))
        assertTrue(json.contains("\"ble_scan_errors_delta\":0"))
    }

    @Test
    fun omitsLastPacketAtWhenNeverHeard() {
        assertFalse(HeartbeatPayload.build(runtime, deltas).contains("last_packet_at"))
    }

    @Test
    fun formatsLastPacketAtAsUtcIso() {
        val json = HeartbeatPayload.build(runtime.copy(lastPacketAtMillis = 1_783_780_342_000), deltas)
        assertTrue(
            json,
            Regex("\"last_packet_at\":\"\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z\"").containsMatchIn(json)
        )
    }

    @Test
    fun omitsLastKnownPositionAtWhenNoFixEver() {
        assertFalse(HeartbeatPayload.build(runtime, deltas).contains("last_known_position_at"))
    }

    @Test
    fun includesLastKnownPositionAtWhenPresent() {
        val json = HeartbeatPayload.build(
            runtime.copy(lastKnownPositionAtMillis = 1_783_780_342_000), deltas
        )
        assertTrue(json, json.contains("\"last_known_position_at\":\"2026-07-11T14:32:22Z\""))
    }

    @Test
    fun omitsClientStateWhenAbsent() {
        // Absence means "no change" server-side; never send an empty status.
        assertFalse(HeartbeatPayload.build(runtime, deltas).contains("\"status\""))
    }

    @Test
    fun includesClientStateWhenPresent() {
        val json = HeartbeatPayload.build(runtime.copy(clientState = "active"), deltas)
        assertTrue(json, json.contains("\"status\":\"active\""))
    }

    @Test
    fun includesDevicesSeenBlockVerbatim() {
        val rollup = DeviceRollup()
        rollup.record("5f982837", -54, 1_783_780_342_000)
        val block = rollup.toJson(1_783_780_343_000)
        val json = HeartbeatPayload.build(runtime.copy(devicesSeen = block), deltas)
        assertTrue(json, json.contains("\"devices_seen\":" + block.text))
    }

    @Test
    fun omitsDevicesSeenWhenAbsent() {
        assertFalse(HeartbeatPayload.build(runtime, deltas).contains("devices_seen"))
    }

    @Test
    fun omitsScanModeWhenAbsent() {
        assertFalse(HeartbeatPayload.build(runtime, deltas).contains("scan_mode"))
    }

    @Test
    fun includesScanModeWhenPresent() {
        val json = HeartbeatPayload.build(runtime.copy(scanMode = "reduced"), deltas)
        assertTrue(json, json.contains("\"scan_mode\":\"reduced\""))
    }

    @Test
    fun isoUtcIsStable() {
        // 1783780342 unix seconds is the plan §1a fixture timestamp.
        assertEquals("2026-07-11T14:32:22Z", HeartbeatPayload.isoUtc(1_783_780_342_000))
    }

    @Test
    fun jsonEscapesQuotes() {
        assertEquals("{\"a\":\"x\\\"y\"}", Json.obj(listOf("a" to "x\"y")))
    }
}
