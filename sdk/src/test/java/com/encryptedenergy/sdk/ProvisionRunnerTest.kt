package com.encryptedenergy.sdk

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvisionRunnerTest {

    private fun seededBackoff() = Backoff(random = Random(42))

    @Test
    fun provisionedFirstTryNeverSleeps() {
        var slept = false
        val result = ProvisionRunner(
            attempt = { ProvisionRunner.Outcome.PROVISIONED },
            backoff = seededBackoff(),
            sleep = { slept = true },
        ).run({ true })
        assertEquals(ProvisionRunner.Result.PROVISIONED, result)
        assertTrue(!slept)
    }

    @Test
    fun rejectedParksImmediately() {
        var attempts = 0
        val result = ProvisionRunner(
            attempt = { attempts++; ProvisionRunner.Outcome.REJECTED },
            backoff = seededBackoff(),
            sleep = { },
        ).run({ true })
        assertEquals(ProvisionRunner.Result.REJECTED, result)
        assertEquals(1, attempts)
    }

    @Test
    fun retriesThroughBackoffThenSucceeds() {
        val outcomes = ArrayDeque(
            listOf(
                ProvisionRunner.Outcome.RETRY,
                ProvisionRunner.Outcome.RETRY,
                ProvisionRunner.Outcome.PROVISIONED,
            )
        )
        val delays = mutableListOf<Long>()
        val result = ProvisionRunner(
            attempt = { outcomes.removeFirst() },
            backoff = seededBackoff(),
            sleep = { delays.add(it) },
        ).run({ true })
        assertEquals(ProvisionRunner.Result.PROVISIONED, result)
        assertEquals(2, delays.size)
        // Equal-jitter ladder: every delay within [base/2, cap], ascending caps.
        delays.forEach { assertTrue(it in 1_000..Backoff.DEFAULT_CAP_MILLIS) }
    }

    @Test
    fun stopsWhenNoLongerActive() {
        var attempts = 0
        val result = ProvisionRunner(
            attempt = { attempts++; ProvisionRunner.Outcome.RETRY },
            backoff = seededBackoff(),
            sleep = { },
        ).run({ attempts < 3 })
        assertEquals(ProvisionRunner.Result.ABANDONED, result)
        assertEquals(3, attempts)
    }

    @Test
    fun interruptedSleepAbandons() {
        val result = ProvisionRunner(
            attempt = { ProvisionRunner.Outcome.RETRY },
            backoff = seededBackoff(),
            sleep = { throw InterruptedException() },
        ).run({ true })
        assertEquals(ProvisionRunner.Result.ABANDONED, result)
    }

    @Test
    fun inactiveBeforeFirstAttemptAbandonsWithoutAttempting() {
        var attempts = 0
        val result = ProvisionRunner(
            attempt = { attempts++; ProvisionRunner.Outcome.PROVISIONED },
            backoff = seededBackoff(),
            sleep = { },
        ).run({ false })
        assertEquals(ProvisionRunner.Result.ABANDONED, result)
        assertEquals(0, attempts)
    }
}
