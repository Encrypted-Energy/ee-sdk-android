package com.encryptedenergy.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationFixTest {

    private val fix = LocationFix(
        latitude = 40.5,
        longitude = -74.25,
        accuracyM = 14.0,
        capturedAtMillis = 1_785_545_340_000,
    )

    // --- staleness gate ---

    @Test
    fun freshWithinGate() {
        assertTrue(fix.isFresh(fix.capturedAtMillis + 24_999))
    }

    @Test
    fun freshAtExactly25s() {
        assertTrue(fix.isFresh(fix.capturedAtMillis + 25_000))
    }

    @Test
    fun staleBeyond25s() {
        assertFalse(fix.isFresh(fix.capturedAtMillis + 25_001))
    }

    @Test
    fun futureFixCountsAsFresh() {
        // Clock skew between GPS time and wall clock: server clamps, we
        // don't drop.
        assertTrue(fix.isFresh(fix.capturedAtMillis - 5_000))
    }

    // --- ingest sanity rules (one bad value 422s a whole batch upstream) ---

    @Test
    fun ofAcceptsValidFix() {
        val f = LocationFix.of(40.5, -74.25, 14.0, 1_785_545_340_000)
        assertNotNull(f)
        assertEquals(14.0, f!!.accuracyM!!, 0.0)
    }

    @Test
    fun ofRejectsNullIsland() {
        assertNull(LocationFix.of(0.0, 0.0, 14.0, 1_785_545_340_000))
    }

    @Test
    fun ofAcceptsZeroLatitudeAlone() {
        // Equator is a real place; only the exact (0, 0) pair is the tell.
        assertNotNull(LocationFix.of(0.0, -74.25, 14.0, 1_785_545_340_000))
    }

    @Test
    fun ofRejectsOutOfRangeCoords() {
        assertNull(LocationFix.of(90.5, -74.25, 14.0, 1_785_545_340_000))
        assertNull(LocationFix.of(40.5, -180.25, 14.0, 1_785_545_340_000))
    }

    @Test
    fun ofRejectsNonFiniteCoords() {
        // NaN passes abs(x) > 90 (false), then explodes inside JSON
        // serialization — reachable once Settings accepts typed coords
        // (0006). Guarded at the gate instead.
        assertNull(LocationFix.of(Double.NaN, -74.25, 14.0, 1_785_545_340_000))
        assertNull(LocationFix.of(40.5, Double.NaN, 14.0, 1_785_545_340_000))
        assertNull(LocationFix.of(Double.POSITIVE_INFINITY, -74.25, 14.0, 1_785_545_340_000))
        assertNull(LocationFix.of(40.5, Double.NEGATIVE_INFINITY, 14.0, 1_785_545_340_000))
    }

    @Test
    fun ofRejectsNonFiniteAccuracy() {
        assertNull(LocationFix.of(40.5, -74.25, Double.NaN, 1_785_545_340_000))
        assertNull(LocationFix.of(40.5, -74.25, Double.POSITIVE_INFINITY, 1_785_545_340_000))
    }

    @Test
    fun ofRejectsNonPositiveCaptureTime() {
        assertNull(LocationFix.of(40.5, -74.25, 14.0, 0))
        assertNull(LocationFix.of(40.5, -74.25, 14.0, -1))
    }

    @Test
    fun ofRejectsAccuracyWorseThan10km() {
        // Rural cell-tower fix: real occurrence, and ee-web 422s the batch.
        assertNull(LocationFix.of(40.5, -74.25, 10_000.1, 1_785_545_340_000))
    }

    @Test
    fun ofAcceptsAccuracyAtExactly10km() {
        assertNotNull(LocationFix.of(40.5, -74.25, 10_000.0, 1_785_545_340_000))
    }

    @Test
    fun ofDropsNonPositiveAccuracyButKeepsFix() {
        // accuracy_m <= 0 is batch-rejected upstream; the position itself
        // is still good, so keep the fix and omit the field.
        val f = LocationFix.of(40.5, -74.25, 0.0, 1_785_545_340_000)
        assertNotNull(f)
        assertNull(f!!.accuracyM)
    }

    @Test
    fun ofKeepsFixWithNoAccuracy() {
        val f = LocationFix.of(40.5, -74.25, null, 1_785_545_340_000)
        assertNotNull(f)
        assertNull(f!!.accuracyM)
    }
}
