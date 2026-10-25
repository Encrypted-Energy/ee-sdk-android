package com.encryptedenergy.sdk

/**
 * The retry brain around [TokenProvision] (plan §3, C6): run provisioning
 * attempts until the server gives a verdict, sleeping out [Backoff] delays
 * between transient failures, and parking permanently on a rejection.
 *
 * Rejection parks because retrying cannot change a deliberate server "no"
 * (revoked/typo'd token, failed mint shape): the SDK stays quiet until the
 * next [EEGateway.start] — a new configure()+start with a rotated token is
 * the partner's recovery path, and every engine start re-runs provisioning
 * when the stored token doesn't match the config.
 *
 * Pure: the attempt, the sleep, and liveness are injected, so the policy
 * unit-tests without threads, HTTP, or a clock. In production the sleep is
 * `Thread.sleep` on a daemon thread; engine stop interrupts it.
 */
internal class ProvisionRunner(
    private val attempt: () -> Outcome,
    private val backoff: Backoff = Backoff(),
    private val sleep: (Long) -> Unit,
) {

    /** What one provisioning attempt concluded. */
    enum class Outcome { PROVISIONED, REJECTED, RETRY }

    /** What the whole run concluded. */
    enum class Result { PROVISIONED, REJECTED, ABANDONED }

    /**
     * Attempt until a verdict, or until [isActive] goes false (the engine
     * stopped) or the sleep is interrupted (engine stop interrupts the
     * provisioning thread). ABANDONED is not a verdict: the next start()
     * tries again from scratch.
     */
    fun run(isActive: () -> Boolean): Result {
        while (isActive()) {
            when (attempt()) {
                Outcome.PROVISIONED -> return Result.PROVISIONED
                Outcome.REJECTED -> return Result.REJECTED
                Outcome.RETRY ->
                    try {
                        sleep(backoff.nextDelayMillis())
                    } catch (_: InterruptedException) {
                        return Result.ABANDONED
                    }
            }
        }
        return Result.ABANDONED
    }
}
