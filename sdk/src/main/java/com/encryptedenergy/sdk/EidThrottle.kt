package com.encryptedenergy.sdk

/**
 * Forward gate: at most one packet per EID per window (plan §2). Every heard
 * advertisement still counts toward heard tallies; this only gates what is
 * eligible for upload.
 *
 * Bounded (0.2.0 item iv): identifiers rotate at least daily, so a phone
 * parked in a dense corridor accumulates unique EIDs without limit. The map
 * is an access-ordered LRU capped at [MAX_ENTRIES]; evicting the
 * least-recently-forwarded EID at worst re-forwards one packet for a device
 * not heard in thousands of others — harmless, the server dedupes bounties
 * per day anyway.
 */
internal class EidThrottle(private val windowMillis: Long) {

    private val lastForwardByEid =
        object : LinkedHashMap<String, Long>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) =
                size > MAX_ENTRIES
        }

    fun shouldForward(eid: String, nowMillis: Long): Boolean {
        val last = lastForwardByEid[eid]
        if (last != null && nowMillis - last < windowMillis) return false
        lastForwardByEid[eid] = nowMillis
        return true
    }

    companion object {
        /** ~4k rotating identifiers ≈ hours of dense-corridor traffic. */
        const val MAX_ENTRIES = 4_096
    }
}
