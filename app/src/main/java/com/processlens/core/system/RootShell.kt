package com.processlens.core.system

import com.processlens.core.common.AccessLevel
import com.processlens.core.common.DataSource
import com.processlens.core.common.IoDispatcher
import com.processlens.core.common.Observed
import com.processlens.core.common.RestrictionReason
import com.processlens.domain.model.RootState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Root-backed shell (Section 28).
 *
 * Detection is deliberately in two stages. Finding an `su` binary on PATH proves
 * only that a superuser manager is *installed* — spawning it is what proves
 * access, and on Magisk that spawn is what raises the user's grant prompt. So
 * [state] reports [RootState.BINARY_PRESENT] from the cheap filesystem check and
 * only escalates to [RootState.GRANTED] after a real `id` probe has succeeded.
 *
 * The probe result is cached: re-running `su` on every capability refresh would
 * spam the superuser log and, on some managers, re-prompt the user.
 *
 * ### What defect 4 was
 *
 * That cache used to be one nullable [RootState] field and [invalidate] used to
 * null it. Routing then wiped a grant it had just been handed:
 * `AccessViewModel.probeRoot()` set GRANTED, its own `refreshCapabilitiesOnly()`
 * called `invalidateAccess()` → `CompositeSystemObserver.invalidate()` →
 * `invalidate()` here, and by the time `resolve()` looked, the field was null
 * again. `state()` answered BINARY_PRESENT, whose `isUsable` is false, and the
 * standard observer stayed in place — so root never engaged no matter how many
 * times the user granted it.
 *
 * The cause was conflating two different nothings behind one null: "we have never
 * checked" and "forget what we learned". Those are now distinct, and so is a
 * refusal — see [probed].
 */
@Singleton
class RootShell @Inject constructor(
    @IoDispatcher private val io: CoroutineDispatcher,
    private val runner: ProcessRunner,
) : ElevatedShell {

    override val accessLevel: AccessLevel = AccessLevel.ROOT

    /**
     * What the last real `su` execution established, or null when none has run.
     *
     * Modelled as [Observed] rather than as a nullable [RootState] because the
     * states routing has to tell apart are exactly the ones [Observed] already
     * exists to express, and because collapsing them is what caused defect 4:
     *
     *  - `null` — never checked. A blank slate, and the only thing [invalidate]
     *    is allowed to produce.
     *  - [Observed.Value] — `su` ran and reported uid 0. The proof is recorded as
     *    what it literally is: a reading whose [DataSource] is a root shell. This
     *    is the one state that survives [invalidate].
     *  - [Observed.Restricted] — the superuser manager answered, and the answer
     *    was no. A decision about access, not a fault, which is what `Restricted`
     *    means everywhere else in this codebase.
     *  - [Observed.Failed] — the probe itself broke: `su` never answered inside
     *    the deadline, or could not be started.
     *
     * [AtomicReference] rather than `@Volatile` so [invalidate] can decide and
     * write as one step. The window is small — a probe and the invalidation that
     * follows it run sequentially in `AccessViewModel` — but it is the exact
     * window the defect lived in, and a compare-and-set closes it rather than
     * narrowing it.
     */
    private val probed = AtomicReference<Observed<RootState>?>(null)

    /** Cheap, synchronous: does an `su` binary exist anywhere standard? */
    fun hasBinary(): Boolean = SU_PATHS.any { path ->
        try {
            File(path).let { it.exists() && it.canExecute() }
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * Cached state. Returns [RootState.BINARY_PRESENT] until [probe] has run.
     *
     * Consults [probed] first and only falls back to [hasBinary], so the common
     * case is a field read. The fallback is a handful of `stat` calls and never a
     * process spawn — requirement 5 of the issue spec depends on that, because
     * `CompositeSystemObserver.resolve()` reaches this method on the path that
     * produces the app's first frame.
     */
    fun state(): RootState = when (val proof = probed.get()) {
        null -> if (hasBinary()) RootState.BINARY_PRESENT else RootState.UNAVAILABLE
        is Observed.Value -> proof.value
        is Observed.Restricted -> when (proof.reason) {
            RestrictionReason.NOT_PRESENT_ON_DEVICE -> RootState.UNAVAILABLE
            else -> RootState.DENIED
        }
        // A probe that never got an answer is reported as a refusal when the
        // binary is there. Not a guess: the Access screen's own wording for
        // DENIED is "denied or timed out", so the two already share a bucket in
        // the only place a user reads them.
        is Observed.Failed -> if (hasBinary()) RootState.DENIED else RootState.UNAVAILABLE
    }

    /**
     * Actually attempts elevation. This may show the superuser prompt, so it is
     * only called when the user has enabled root support in Settings — never
     * speculatively at startup.
     *
     * Always re-runs `su`, including over a cached grant. That is how a revoked
     * grant becomes discoverable: tapping "Probe root" again is a genuine
     * re-check, and a manager that has since withdrawn the grant produces a
     * refusal here that overwrites the proof.
     */
    suspend fun probe(): RootState = withContext(io) {
        if (!hasBinary()) {
            // Cached rather than recomputed, so the route resolution that follows
            // does not repeat the filesystem walk.
            probed.set(Observed.notPresent("No su binary is present on this device."))
            return@withContext RootState.UNAVAILABLE
        }

        val result = execute(DiagnosticCommand.Probe.argv, PROBE_TIMEOUT_MILLIS)
        val granted = result.isSuccess && result.stdout.contains("uid=0")

        probed.set(
            when {
                granted -> Observed.of(RootState.GRANTED, DataSource.SHELL_ROOT)

                result.exitCode == ProcessRunner.EXIT_TIMED_OUT -> Observed.Failed(
                    "su did not answer within $PROBE_TIMEOUT_MILLIS ms.",
                    result.stderr.trim().take(DETAIL_LIMIT).takeIf { it.isNotBlank() },
                )

                // A manager that denies returns non-zero quickly, often with
                // nothing on stdout. Distinguish that from "no binary at all".
                else -> Observed.Restricted(
                    RestrictionReason.REQUIRES_ELEVATED_ACCESS,
                    AccessLevel.ROOT,
                    result.stderr.trim().take(DETAIL_LIMIT).takeIf { it.isNotBlank() }
                        ?: "su exited with ${result.exitCode} without reporting uid 0.",
                )
            },
        )
        state()
    }

    /**
     * Forgets stale conclusions so a routing change can re-evaluate — and keeps a
     * grant that a real `su` execution proved.
     *
     * This is the fix for defect 4 and the asymmetry is the whole point. A
     * negative is worth forgetting: the user may have granted root in their
     * manager since, and `AccessViewModel` already states the principle — "a
     * stale 'denied' is as misleading as a stale 'granted'". A proven positive is
     * not, because routing is re-evaluated immediately after a probe and dropping
     * the proof mid-sequence is precisely what kept root from ever engaging.
     *
     * Keeping it does not make a revoked grant permanent. Two things still clear
     * it: [probe], which always re-runs `su`, and [forgetGrant], which routing
     * calls when the user withdraws root support.
     */
    fun invalidate() {
        val current = probed.get()
        if (current is Observed.Value) return
        // Compare-and-set, not set: if a probe proved a grant between the read
        // above and here, that proof is newer than this decision and wins.
        probed.compareAndSet(current, null)
    }

    /**
     * Forgets everything, a proven grant included, so the next [probe] has to
     * earn it again.
     *
     * Separate from [invalidate] because it is a different question. Routing asks
     * "has anything about the route changed?"; this answers "the user has
     * withdrawn root support, so nothing we proved under the old settings should
     * be carried forward". Resurrecting an old grant when root is switched back
     * on would elevate without the user's manager being consulted again.
     */
    fun forgetGrant() {
        probed.set(null)
    }

    override suspend fun isAvailable(): Boolean = state().isUsable

    /**
     * Runs a diagnostic as root.
     *
     * The argv is passed to `su -c` as *one* shell word per element, joined with
     * single-quote escaping, so a package name containing shell metacharacters
     * cannot break out of its argument position. Only the enumerated
     * [DiagnosticCommand]s ever reach here, and none of them mutate state.
     *
     * The deadline, the concurrent drain of both pipes and the destroy-on-every-
     * path teardown all live in [ProcessRunner]; see its KDoc for why reading the
     * two pipes in sequence — which this used to do — was a deadlock and not
     * merely slow.
     *
     * [timeoutMillis] stays the caller's to choose, and deliberately so: the
     * three-second figure in requirement 4 is about *probes*, and `ElevatedObserver`
     * legitimately asks for ten to twenty seconds to pull `dumpsys batterystats`
     * off a device that has been up for weeks. Capping those at three would turn
     * working screens into empty ones. Only [probe] is pinned, to
     * [PROBE_TIMEOUT_MILLIS].
     */
    override suspend fun execute(argv: List<String>, timeoutMillis: Long): ShellResult =
        withContext(io) {
            if (argv.isEmpty()) {
                return@withContext ShellResult.failure("Empty command", accessLevel)
            }
            if (!hasBinary()) {
                return@withContext ShellResult.failure("No su binary on this device", accessLevel)
            }

            val command = argv.joinToString(" ") { shellQuote(it) }
            runner.execute(argv, accessLevel, timeoutMillis) {
                // redirectErrorStream(false) is the default, and it is spelled out
                // because it is load-bearing: the two pipes are kept separate so a
                // denial message on stderr is never mistaken for command output,
                // and keeping them separate is what makes a concurrent drain
                // necessary in the first place.
                ProcessBuilder(SU, "-c", command)
                    .redirectErrorStream(false)
                    .start()
            }
        }

    /**
     * POSIX single-quote escaping: wrap in `'…'` and replace each embedded quote
     * with `'\''`. Safe for every byte, unlike a blocklist of metacharacters.
     */
    private fun shellQuote(arg: String): String =
        "'" + arg.replace("'", "'\\''") + "'"

    companion object {
        /**
         * Budget for the access probe (requirement 4 of the issue #1 spec).
         *
         * Three seconds, down from the ten this used to pass — and ten was never
         * real anyway, because the old sequential drain meant the timeout was
         * never consulted until the first `read()` returned.
         *
         * Three seconds is shorter than a human takes to answer a Magisk prompt,
         * and that trade is deliberate: a probe is a question about access, not
         * the act of gaining it, and nothing in the app may block on it
         * (requirement 5). A prompt that is still on screen when this expires
         * costs the user one more tap — the grant their manager records is
         * persistent, so the second `su` returns immediately. The alternative is a
         * dialog nobody is looking at holding an IO thread and a screen's refresh
         * for as long as the phone is in a pocket.
         */
        const val PROBE_TIMEOUT_MILLIS = 3_000L

        private const val SU = "su"

        /** Matches the detail length `ElevatedObserver` keeps from a failed shell. */
        private const val DETAIL_LIMIT = 200

        private val SU_PATHS = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
            "/system/sbin/su",
            "/vendor/bin/su",
            "/debug_ramdisk/su",
        )
    }
}
