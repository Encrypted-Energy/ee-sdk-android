package com.encryptedenergy.sdk

/**
 * Forward gate: at most one packet per EID per window (plan §2). Every heard
 * advertisement still counts toward heard tallies; this only gates what is
 * eligible for upload.
 */
internal class EidThrottle(private val windowMillis: Long) {

    private val lastForwardByEid = HashMap<String, Long>()

    fun shouldForward(eid: String, nowMillis: Long): Boolean {
        val last = lastForwardByEid[eid]
        if (last != null && nowMillis - last < windowMillis) return false
        lastForwardByEid[eid] = nowMillis
        return true
    }
}
