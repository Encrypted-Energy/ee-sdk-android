package com.encryptedenergy.sdk

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The 0.2.0 park policy. The invariants: battery hysteresis can't flap,
 * charging always unparks the battery gate, the no-fix clock arms from
 * reset (grace window at pipeline birth), and any fix reopens instantly.
 */
class RadioGateTest {

    private fun gate(now: Long = 0L) = RadioGate().also { it.reset(now) }

    @Test
    fun scansByDefaultInsideGraceWindow() {
        val g = gate(0)
        assertEquals(RadioGate.Verdict.SCAN, g.verdict(1_000))
    }

    @Test
    fun parksBelowLowBatteryOnBattery() {
        val g = gate()
        g.onCharging(false)
        g.onBatteryPct(RadioGate.LOW_BATTERY_PCT - 1)
        assertEquals(RadioGate.Verdict.PAUSED_BATTERY, g.verdict(1_000))
    }

    @Test
    fun neverParksForBatteryWhileCharging() {
        val g = gate()
        g.onCharging(true)
        g.onBatteryPct(5)
        assertEquals(RadioGate.Verdict.SCAN, g.verdict(1_000))
    }

    @Test
    fun hysteresisHoldsParkUntilResumeThreshold() {
        val g = gate()
        g.onCharging(false)
        g.onBatteryPct(19)
        assertEquals(RadioGate.Verdict.PAUSED_BATTERY, g.verdict(1_000))
        // Climbing back above the park threshold but below resume: stay
        // parked (no flapping at the boundary).
        g.onBatteryPct(RadioGate.BATTERY_RESUME_PCT - 1)
        assertEquals(RadioGate.Verdict.PAUSED_BATTERY, g.verdict(2_000))
        g.onBatteryPct(RadioGate.BATTERY_RESUME_PCT)
        assertEquals(RadioGate.Verdict.SCAN, g.verdict(3_000))
    }

    @Test
    fun pluggingInUnparksImmediately() {
        val g = gate()
        g.onCharging(false)
        g.onBatteryPct(10)
        assertEquals(RadioGate.Verdict.PAUSED_BATTERY, g.verdict(1_000))
        g.onCharging(true)
        assertEquals(RadioGate.Verdict.SCAN, g.verdict(1_001))
    }

    @Test
    fun parksAfterNoFixWindowFromReset() {
        val g = gate(0)
        assertEquals(RadioGate.Verdict.SCAN, g.verdict(RadioGate.NO_FIX_PAUSE_MILLIS))
        assertEquals(
            RadioGate.Verdict.PAUSED_NO_FIX,
            g.verdict(RadioGate.NO_FIX_PAUSE_MILLIS + 1)
        )
    }

    @Test
    fun fixReopensInstantlyAndRestartsTheClock() {
        val g = gate(0)
        assertEquals(RadioGate.Verdict.PAUSED_NO_FIX, g.verdict(RadioGate.NO_FIX_PAUSE_MILLIS + 1))
        val fixAt = RadioGate.NO_FIX_PAUSE_MILLIS + 2
        g.onFix(fixAt)
        assertEquals(RadioGate.Verdict.SCAN, g.verdict(fixAt + 1))
        // The window now runs from the fix, not from reset.
        assertEquals(RadioGate.Verdict.SCAN, g.verdict(fixAt + RadioGate.NO_FIX_PAUSE_MILLIS))
        assertEquals(
            RadioGate.Verdict.PAUSED_NO_FIX,
            g.verdict(fixAt + RadioGate.NO_FIX_PAUSE_MILLIS + 1)
        )
    }

    @Test
    fun batteryParkOutranksNoFix() {
        val g = gate(0)
        g.onCharging(false)
        g.onBatteryPct(5)
        assertEquals(
            RadioGate.Verdict.PAUSED_BATTERY,
            g.verdict(RadioGate.NO_FIX_PAUSE_MILLIS + 1)
        )
    }

    @Test
    fun resetArmsNewGraceWindowButKeepsBatteryState() {
        val g = gate(0)
        g.onCharging(false)
        g.onBatteryPct(5)
        assertEquals(RadioGate.Verdict.PAUSED_BATTERY, g.verdict(1_000))
        // Pipeline restart: fix clock re-arms, battery park survives.
        g.reset(100_000)
        assertEquals(RadioGate.Verdict.PAUSED_BATTERY, g.verdict(100_001))
        g.onCharging(true)
        assertEquals(RadioGate.Verdict.SCAN, g.verdict(100_002))
    }

    @Test
    fun garbageBatteryPctIsIgnored() {
        val g = gate()
        g.onCharging(false)
        g.onBatteryPct(-1)
        g.onBatteryPct(101)
        assertEquals(RadioGate.Verdict.SCAN, g.verdict(1_000))
    }
}
