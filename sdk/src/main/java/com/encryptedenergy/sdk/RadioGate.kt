package com.encryptedenergy.sdk

/**
 * Radio park policy (0.2.0 items ii+iii): decides whether the BLE scanner
 * should run at all, upstream of ScanPolicy's FULL/REDUCED tuning. Pure JVM
 * so every branch unit-tests without a device; the Engine owns the clock and
 * the battery broadcasts.
 *
 * Two independent reasons to park, in priority order:
 *
 *  - **Low battery** (plan: guest courtesy in someone else's app): below
 *    [LOW_BATTERY_PCT] on battery power the scanner stops. Hysteresis:
 *    resume only on charger power or at [BATTERY_RESUME_PCT]+, so a phone
 *    hovering at the threshold doesn't flap the radio.
 *  - **Dead fix stream** (nothing in the fleet did this before 0.2.0): after
 *    [NO_FIX_PAUSE_MILLIS] without a fresh fix every heard packet is a
 *    guaranteed drop (the 25 s stamp gate), so the radio burns battery for
 *    nothing. Park it; any arriving fix reopens the gate instantly. The
 *    location provider MUST keep running while parked — it is the recovery
 *    signal.
 *
 * The no-fix clock arms from [reset] (pipeline start), so a phone that never
 * gets a first fix still scans for the grace window instead of parking at
 * birth.
 */
internal class RadioGate {

    enum class Verdict { SCAN, PAUSED_BATTERY, PAUSED_NO_FIX }

    private var charging = false
    private var batteryPct: Int? = null
    private var batteryParked = false
    private var lastFixMillis: Long? = null
    private var armedMillis: Long = 0

    /** Pipeline start: arm the no-fix grace window, keep battery state. */
    @Synchronized
    fun reset(nowMillis: Long) {
        armedMillis = nowMillis
        lastFixMillis = null
    }

    @Synchronized
    fun onCharging(isCharging: Boolean) {
        charging = isCharging
    }

    /** From ACTION_BATTERY_CHANGED's level/scale extras. Ignores garbage. */
    @Synchronized
    fun onBatteryPct(pct: Int) {
        if (pct in 0..100) batteryPct = pct
    }

    @Synchronized
    fun onFix(nowMillis: Long) {
        lastFixMillis = nowMillis
    }

    @Synchronized
    fun verdict(nowMillis: Long): Verdict {
        val pct = batteryPct
        if (charging) {
            batteryParked = false
        } else if (pct != null) {
            if (batteryParked) {
                if (pct >= BATTERY_RESUME_PCT) batteryParked = false
            } else if (pct < LOW_BATTERY_PCT) {
                batteryParked = true
            }
        }
        if (batteryParked) return Verdict.PAUSED_BATTERY

        val anchor = lastFixMillis ?: armedMillis
        if (nowMillis - anchor > NO_FIX_PAUSE_MILLIS) return Verdict.PAUSED_NO_FIX

        return Verdict.SCAN
    }

    companion object {
        const val LOW_BATTERY_PCT = 20
        const val BATTERY_RESUME_PCT = 25
        const val NO_FIX_PAUSE_MILLIS = 5 * 60_000L
    }
}
