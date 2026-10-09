package com.processlens.core.system

import com.processlens.core.common.Observed
import com.processlens.core.common.RestrictionReason
import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers which reads the sandbox has already refused, so each one is attempted
 * once per session instead of once per sample.
 *
 * A refusal is an answer. When SELinux policy denies `/sys/class/thermal`, the
 * kernel gives the same answer to the next identical read and to the ten thousand
 * after it — but the sampler asks again every one to ten seconds (Section 6), and
 * every attempt costs a syscall, an audit record, and a line of `avc: denied` in
 * the device log. One bug report against this app carried fifty minutes of them.
 * Nothing was learned after the first.
 *
 * So the first refusal is kept and later callers get it back without touching the
 * filesystem. Section 43 — ProcessLens must not become the problem it investigates
 * — is why; Section 42 is what makes it safe, because what comes back out is the
 * same [Observed.Restricted] the real read produced, carrying the same reason to
 * the same "Not available" block. Nothing is substituted and nothing is invented.
 *
 * ## What is deliberately not remembered
 *
 * [Observed.Failed] never is. A parse that failed, or a file that happened to be
 * empty, is a fault rather than a policy: `/proc/loadavg` yielding nothing once
 * says nothing about the next read, and memoising it would turn a single bad
 * sample into a permanently blank screen — the opposite failure to the one this
 * class exists to fix.
 *
 * Nor is every restriction. Only the reasons that describe the *platform* are
 * terminal. [RestrictionReason.PERMISSION_REQUIRED] is one dialog away from being
 * false, and [RestrictionReason.SAMPLING_DISABLED] is the user's own switch, which
 * they can flip back between two ticks (Section 40); treating either as settled
 * would leave a value unavailable after the user had already fixed it.
 *
 * ## Why it must be clearable
 *
 * A denial is permanent only at the access level that earned it. `/proc/stat` is
 * refused to a normal app and read freely through a root shell, so the moment the
 * user grants root or Shizuku (Sections 27, 28) every memo here describes a device
 * the app no longer is. [clear] exists for that transition, and `ProcFsReader`
 * exposes it so the capability re-evaluation — the one moment the app stops
 * assuming and asks what it can read *now* — can drop the lot before it probes.
 *
 * Plain Kotlin on purpose: no `android.*`, no context, nothing that needs a device.
 * This is session state that decides whether a screen says "Not available", it is
 * cheap to get subtly wrong, and it is worth being able to test on the JVM.
 */
class RestrictionCache {

    /**
     * Keys are logical metric names, supplied by the caller rather than derived
     * from the paths it read, so a unit test with a temporary `/proc` root memoises
     * and reports under exactly the names a real device does.
     */
    private val refusals = ConcurrentHashMap<String, Observed.Restricted>()

    /** Keys whose one permitted log line has been spent. See [shouldAnnounce]. */
    private val announced = ConcurrentHashMap<String, Boolean>()

    /**
     * Returns the remembered refusal for [key], or performs [read] and remembers it
     * if it turns out to be a terminal one.
     *
     * [read] is not invoked at all once a refusal is remembered, which is the whole
     * point: the memo has to remove the filesystem access, not merely hide its
     * result. Thread-safe but not atomic — two samplers racing on a cold key both
     * read, and both arrive at the same answer, which is cheaper than holding a lock
     * across file I/O.
     */
    fun <T> attempt(key: String, read: () -> Observed<T>): Observed<T> {
        refusals[key]?.let { return it }
        val outcome = read()
        if (outcome is Observed.Restricted && outcome.isTerminalForThisSession()) {
            refusals[key] = outcome
        }
        return outcome
    }

    /** The remembered refusal for [key], or null if it has not been refused yet. */
    fun refusalFor(key: String): Observed.Restricted? = refusals[key]

    fun isRefused(key: String): Boolean = refusals.containsKey(key)

    /** Metric names currently known to be refused. Order is not defined. */
    val refusedKeys: Set<String> get() = refusals.keys.toSet()

    /**
     * True the first time it is asked about [key] and false ever afterwards, which
     * is how "at most one log line per metric per session" is enforced in one place
     * rather than at each of the call sites that might want to log.
     *
     * Note that [clear] does not reset this. The budget is per session, not per
     * access level: a user who grants root and has the same path refused again does
     * not need to be told twice, and the line was never for them anyway — it is for
     * whoever reads the log of a bug report.
     */
    fun shouldAnnounce(key: String): Boolean = announced.putIfAbsent(key, true) == null

    /**
     * Forgets every refusal, so the next read of each metric reaches the filesystem
     * again.
     *
     * Called when the app's access level may have changed. Clearing too eagerly
     * merely costs the reads this class was added to avoid; not clearing at all
     * would mean a user who granted root kept seeing "Not available" for everything
     * ProcessLens had given up on beforehand, which is a worse bug than the one
     * being fixed.
     */
    fun clear() {
        refusals.clear()
    }

    /**
     * Whether this restriction will still be true at the next tick.
     *
     * Exhaustive on purpose: a new [RestrictionReason] should fail to compile here
     * and force an answer, because defaulting a new reason to "terminal" would
     * silently memoise something revocable.
     */
    private fun Observed.Restricted.isTerminalForThisSession(): Boolean = when (reason) {
        RestrictionReason.PLATFORM_RESTRICTED,
        RestrictionReason.NOT_PRESENT_ON_DEVICE,
        RestrictionReason.NOT_SUPPORTED_ON_API_LEVEL,
        RestrictionReason.REQUIRES_ELEVATED_ACCESS,
        -> true

        RestrictionReason.PERMISSION_REQUIRED,
        RestrictionReason.SAMPLING_DISABLED,
        -> false
    }
}
