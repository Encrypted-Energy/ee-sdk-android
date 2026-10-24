package com.encryptedenergy.sdk

/**
 * Heartbeat delta counters (plan §1b). Snapshot-and-reset atomically before
 * POST; the caller restores the snapshot on transient failure so deltas are
 * never lost, and drops it on terminal failure (401/4xx) — copying the Pi
 * worker's semantics exactly.
 */
internal class Counters {

    data class Snapshot(
        val heard: Int,
        val forwarded: Int,
        val droppedNoFix: Int,
        val scanErrors: Int,
    )

    private var heard = 0
    private var forwarded = 0
    private var droppedNoFix = 0
    private var scanErrors = 0

    @Synchronized fun incHeard() { heard++ }
    @Synchronized fun incForwarded() { forwarded++ }
    @Synchronized fun incDroppedNoFix() { droppedNoFix++ }
    @Synchronized fun incScanErrors() { scanErrors++ }

    @Synchronized
    fun snapshotAndReset(): Snapshot {
        val s = Snapshot(heard, forwarded, droppedNoFix, scanErrors)
        heard = 0; forwarded = 0; droppedNoFix = 0; scanErrors = 0
        return s
    }

    /** Adds a failed POST's deltas back on top of anything counted since. */
    @Synchronized
    fun restore(s: Snapshot) {
        heard += s.heard
        forwarded += s.forwarded
        droppedNoFix += s.droppedNoFix
        scanErrors += s.scanErrors
    }
}
