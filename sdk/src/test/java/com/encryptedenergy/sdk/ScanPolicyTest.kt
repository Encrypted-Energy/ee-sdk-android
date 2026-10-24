package com.encryptedenergy.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanPolicyTest {

    // ~1 m of latitude in degrees; longitude scaled at the equator too since
    // the fixture sits there for round numbers.
    private val mPerDegLat = 111_195.0

    private fun fix(northM: Double, accuracyM: Double?, atMillis: Long): LocationFix =
        LocationFix.of(
            latitude = 10.0 + northM / mPerDegLat,
            longitude = 20.0,
            accuracyM = accuracyM,
            capturedAtMillis = atMillis,
        )!!

    private val dwell = ScanPolicy.DWELL_MILLIS

    @Test
    fun fullRateThroughTheStartupDwellThenReduced() {
        val p = ScanPolicy()
        p.reset(1_000)
        assertEquals(ScanPolicy.Mode.FULL, p.mode(1_000 + dwell - 1))
        assertEquals(ScanPolicy.Mode.REDUCED, p.mode(1_000 + dwell))
        assertEquals("stationary on battery", p.reason(1_000 + dwell))
    }

    @Test
    fun chargingForcesFullAndUnplugRestoresPolicy() {
        val p = ScanPolicy()
        p.reset(0)
        assertTrue(p.onCharging(true))
        assertEquals(ScanPolicy.Mode.FULL, p.mode(dwell * 3))
        assertEquals("charging", p.reason(dwell * 3))
        assertTrue(p.onCharging(false))
        assertEquals(ScanPolicy.Mode.REDUCED, p.mode(dwell * 3))
    }

    @Test
    fun chargingChangeReportsOnlyTransitions() {
        val p = ScanPolicy()
        assertFalse(p.onCharging(false))
        assertTrue(p.onCharging(true))
        assertFalse(p.onCharging(true))
    }

    @Test
    fun firstFixIsPositionNotMovement() {
        val p = ScanPolicy()
        p.reset(0)
        p.onFix(fix(0.0, 5.0, 1_000))
        // A desk phone's warm-start fix must not re-arm the dwell.
        assertEquals(ScanPolicy.Mode.REDUCED, p.mode(dwell))
    }

    @Test
    fun movementRestoresFullInstantly() {
        val p = ScanPolicy()
        p.reset(0)
        p.onFix(fix(0.0, 5.0, 1_000))
        assertEquals(ScanPolicy.Mode.REDUCED, p.mode(dwell))
        // 500 m jump with tight accuracy: threshold 50 + 5 + 5 = 60.
        p.onFix(fix(500.0, 5.0, dwell))
        assertEquals(ScanPolicy.Mode.FULL, p.mode(dwell))
        assertEquals("moving", p.reason(dwell))
        // And the dwell re-arms from the movement, not from reset.
        assertEquals(ScanPolicy.Mode.FULL, p.mode(dwell * 2 - 1))
        assertEquals(ScanPolicy.Mode.REDUCED, p.mode(dwell * 2))
    }

    @Test
    fun networkTowerJumpIsNotMovement() {
        val p = ScanPolicy()
        p.reset(0)
        p.onFix(fix(0.0, 1_500.0, 1_000))
        // 2 km apart but both fixes are ±1.5 km network guesses:
        // threshold 50 + 1500 + 1500 = 3050 m swallows the jump.
        p.onFix(fix(2_000.0, 1_500.0, 2_000))
        assertEquals(ScanPolicy.Mode.REDUCED, p.mode(dwell))
    }

    @Test
    fun slowWalkAccumulatesAgainstTheAnchor() {
        val p = ScanPolicy()
        p.reset(0)
        p.onFix(fix(0.0, 5.0, 1_000))
        // 30 m steps, threshold 60: the anchor stays put, displacement
        // accumulates, the third step trips it.
        p.onFix(fix(30.0, 5.0, 2_000))
        p.onFix(fix(60.0, 5.0, 3_000))
        assertEquals(ScanPolicy.Mode.REDUCED, p.mode(dwell))
        p.onFix(fix(90.0, 5.0, dwell + 1_000))
        assertEquals(ScanPolicy.Mode.FULL, p.mode(dwell + 1_000))
    }

    @Test
    fun missingAccuracyUsesTheDefaultRadius() {
        val p = ScanPolicy()
        p.reset(0)
        p.onFix(fix(0.0, null, 1_000))
        // threshold 50 + 50 + 50 = 150: 120 m is noise, 320 m is movement.
        p.onFix(fix(120.0, null, 2_000))
        assertEquals(ScanPolicy.Mode.REDUCED, p.mode(dwell))
        p.onFix(fix(320.0, null, dwell + 1_000))
        assertEquals(ScanPolicy.Mode.FULL, p.mode(dwell + 1_000))
    }

    @Test
    fun resetRearmsTheDwellAndDropsTheAnchor() {
        val p = ScanPolicy()
        p.reset(0)
        p.onFix(fix(0.0, 5.0, 1_000))
        assertEquals(ScanPolicy.Mode.REDUCED, p.mode(dwell))
        p.reset(dwell)
        assertEquals(ScanPolicy.Mode.FULL, p.mode(dwell * 2 - 1))
        // First fix after reset anchors without reading as movement, even
        // 500 m from the pre-reset anchor.
        p.onFix(fix(500.0, 5.0, dwell))
        assertEquals(ScanPolicy.Mode.REDUCED, p.mode(dwell * 2))
    }

    @Test
    fun distanceIsAccurateAtMovementScale() {
        // 0.01° of latitude ≈ 1112 m; accept ±1%.
        val d = ScanPolicy.distanceMeters(10.0, 20.0, 10.01, 20.0)
        assertTrue("got $d", d > 1_100 && d < 1_124)
        assertEquals(0.0, ScanPolicy.distanceMeters(10.0, 20.0, 10.0, 20.0), 1e-9)
    }
}
