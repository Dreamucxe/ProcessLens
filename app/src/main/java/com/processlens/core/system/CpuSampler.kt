package com.processlens.core.system

import com.processlens.core.common.DataSource
import com.processlens.core.common.Observed
import com.processlens.core.common.Precision
import java.util.concurrent.ConcurrentHashMap

/**
 * Turns cumulative jiffy counters into utilisation percentages.
 *
 * CPU usage is not a value any Android API returns — it only exists as the delta
 * between two readings of a monotonically increasing counter. That has three
 * consequences this class exists to handle honestly:
 *
 *  1. **The first sample can never produce a percentage.** There is no previous
 *     reading to subtract. It returns [Observed.Restricted] rather than 0%, so a
 *     freshly-opened screen says "sampling…" instead of claiming an idle CPU.
 *  2. **The interval matters**, so every figure it produces is marked
 *     [Precision.SAMPLED] rather than EXACT.
 *  3. **Counters reset** when a process dies and its PID is recycled. A negative
 *     delta is detected and the baseline is dropped instead of being clamped to
 *     zero, which would silently report a wrong number.
 */
class CpuSampler {

    private var previousSystem: CpuTimes? = null
    private var previousPerCore: List<CpuTimes>? = null

    /** pid → (cpuJiffies, elapsedRealtimeMillis) at the last sample. */
    private val previousProcess = ConcurrentHashMap<Int, ProcessCpuSample>()

    /**
     * Wall-clock elapsed time is needed as the denominator for per-process usage,
     * because a process's jiffies must be compared against real time, not against
     * the system's total jiffies (which count every core).
     */
    private var previousElapsedMillis: Long = 0L

    private val coreCount: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    /**
     * System-wide utilisation from two `/proc/stat` readings.
     * Returns [Observed.Restricted] on the first call by design.
     */
    fun sampleSystem(current: Observed<CpuTimes>): Observed<Float> {
        val now = (current as? Observed.Value)?.value
            ?: return when (current) {
                is Observed.Restricted -> current
                is Observed.Failed -> current
                else -> Observed.Failed("No CPU counters")
            }

        val prev = previousSystem
        previousSystem = now

        if (prev == null) {
            return Observed.Restricted(
                com.processlens.core.common.RestrictionReason.NOT_PRESENT_ON_DEVICE,
                null,
                "Waiting for a second sample — CPU usage is a rate, not an instant value.",
            )
        }
        // A counter that went backwards means the source was reset or swapped.
        if (now.total < prev.total) {
            previousSystem = now
            return Observed.Failed("CPU counters went backwards; baseline reset")
        }
        val util = now.utilisationSince(prev)
            ?: return Observed.Restricted(
                com.processlens.core.common.RestrictionReason.NOT_PRESENT_ON_DEVICE,
                null,
                "Counters did not advance between samples.",
            )
        return Observed.of(util, DataSource.PROC_FS, Precision.SAMPLED)
    }

    fun samplePerCore(current: Observed<List<CpuTimes>>): Observed<List<Float>> {
        val now = (current as? Observed.Value)?.value
            ?: return when (current) {
                is Observed.Restricted -> current
                is Observed.Failed -> current
                else -> Observed.Failed("No per-core counters")
            }

        val prev = previousPerCore
        previousPerCore = now

        if (prev == null || prev.size != now.size) {
            return Observed.Restricted(
                com.processlens.core.common.RestrictionReason.NOT_PRESENT_ON_DEVICE,
                null,
                "Waiting for a second sample.",
            )
        }
        val values = now.zip(prev).map { (n, p) -> n.utilisationSince(p) }
        // A core that was offline for the whole interval yields null; report 0 for
        // it only when at least one core produced a real figure, so a wholly
        // unreadable set stays unavailable rather than becoming a row of zeros.
        if (values.all { it == null }) {
            return Observed.Restricted(
                com.processlens.core.common.RestrictionReason.NOT_PRESENT_ON_DEVICE,
                null,
                "No core advanced its counters between samples.",
            )
        }
        return Observed.of(values.map { it ?: 0f }, DataSource.PROC_FS, Precision.SAMPLED)
    }

    /**
     * Per-process utilisation, expressed as a share of one CPU-second per
     * wall-second — the convention `top` uses, so a process pinning two cores
     * reads as 200%. Divided by [coreCount] would understate a single-threaded
     * hot loop, which is exactly the case an investigation is looking for.
     */
    fun sampleProcess(pid: Int, cpuJiffies: Long, nowElapsedMillis: Long): Observed<Float> {
        val prev = previousProcess.put(pid, ProcessCpuSample(cpuJiffies, nowElapsedMillis))

        if (prev == null) {
            return Observed.Restricted(
                com.processlens.core.common.RestrictionReason.NOT_PRESENT_ON_DEVICE,
                null,
                "Waiting for a second sample.",
            )
        }
        val wallDeltaMillis = nowElapsedMillis - prev.elapsedMillis
        if (wallDeltaMillis <= 0) {
            return Observed.Restricted(
                com.processlens.core.common.RestrictionReason.NOT_PRESENT_ON_DEVICE,
                null,
                "No time elapsed between samples.",
            )
        }
        val jiffyDelta = cpuJiffies - prev.cpuJiffies
        if (jiffyDelta < 0) {
            // PID reuse: this is a different process wearing the same number.
            previousProcess[pid] = ProcessCpuSample(cpuJiffies, nowElapsedMillis)
            return Observed.Failed("Process counters reset (PID likely reused)")
        }
        // jiffies → ms (USER_HZ = 100), then as a fraction of wall time.
        val cpuMillis = jiffyDelta * 10L
        val percent = (cpuMillis.toFloat() / wallDeltaMillis.toFloat()) * 100f
        return Observed.of(
            percent.coerceIn(0f, 100f * coreCount),
            DataSource.PROC_FS,
            Precision.SAMPLED,
        )
    }

    /** Drops baselines for processes that are gone, so the map cannot grow forever. */
    fun retainOnly(livePids: Set<Int>) {
        val iterator = previousProcess.keys.iterator()
        while (iterator.hasNext()) {
            if (iterator.next() !in livePids) iterator.remove()
        }
    }

    /** Clears all baselines — used when the refresh rate or access level changes. */
    fun reset() {
        previousSystem = null
        previousPerCore = null
        previousProcess.clear()
        previousElapsedMillis = 0L
    }

    val trackedProcessCount: Int get() = previousProcess.size

    private data class ProcessCpuSample(val cpuJiffies: Long, val elapsedMillis: Long)
}
