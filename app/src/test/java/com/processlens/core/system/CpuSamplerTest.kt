package com.processlens.core.system

import com.processlens.core.common.DataSource
import com.processlens.core.common.Observed
import com.processlens.core.common.Precision
import com.processlens.core.common.RestrictionReason
import com.processlens.core.common.valueOrNull
import com.processlens.testing.assertContains
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The CPU rate sampler (Sections 6, 42, 56).
 *
 * There is no Android API that returns "CPU usage". It only exists as the delta between
 * two readings of a counter, and every test here is about the consequences of that:
 * the first sample cannot produce a figure, a counter that went backwards invalidates
 * the baseline, and neither case may be smoothed over with a 0%.
 */
class CpuSamplerTest {

    private lateinit var sampler: CpuSampler

    @Before
    fun setUp() {
        sampler = CpuSampler()
    }

    private fun times(active: Long, idle: Long, name: String = "cpu") =
        Observed.of(
            CpuTimes(name, user = active, nice = 0, system = 0, idle = idle, iowait = 0, irq = 0, softirq = 0, steal = 0),
            DataSource.PROC_FS,
        )

    // ------------------------------------------------------------------- system

    @Test
    fun `the first system sample cannot produce a percentage`() {
        val first = sampler.sampleSystem(times(active = 100, idle = 100))

        assertNull("the first sample must not yield a figure", first.valueOrNull)
        val restricted = first as Observed.Restricted
        // The wording has to explain *why* there is no number yet, or the UI would show
        // an unexplained blank on every fresh screen.
        assertContains(restricted.detail, "rate")
    }

    @Test
    fun `the second sample yields the utilisation between the two`() {
        sampler.sampleSystem(times(active = 100, idle = 100))
        val second = sampler.sampleSystem(times(active = 200, idle = 200))

        assertEquals(50f, second.valueOrNull!!, 0.01f)
    }

    @Test
    fun `a sampled figure is labelled sampled rather than exact`() {
        // Section 7: a percentage derived from two readings is not the same kind of
        // fact as a byte count the platform returned, and the UI says so.
        sampler.sampleSystem(times(active = 100, idle = 100))
        val second = sampler.sampleSystem(times(active = 200, idle = 200))

        assertEquals(Precision.SAMPLED, (second as Observed.Value).precision)
        assertEquals(DataSource.PROC_FS, second.source)
    }

    @Test
    fun `counters that did not advance yield no figure`() {
        sampler.sampleSystem(times(active = 100, idle = 100))
        val second = sampler.sampleSystem(times(active = 100, idle = 100))

        assertNull(second.valueOrNull)
        assertContains((second as Observed.Restricted).detail, "did not advance")
    }

    @Test
    fun `counters that went backwards are reported as a failure and reset the baseline`() {
        sampler.sampleSystem(times(active = 500, idle = 500))
        val backwards = sampler.sampleSystem(times(active = 10, idle = 10))

        assertTrue("a reset counter is a fault, not a restriction", backwards is Observed.Failed)
        assertContains((backwards as Observed.Failed).detail, "backwards")

        // The baseline was replaced, so the next ordinary pair works again.
        val recovered = sampler.sampleSystem(times(active = 60, idle = 60))
        assertEquals(50f, recovered.valueOrNull!!, 0.01f)
    }

    @Test
    fun `a fully busy interval reads as one hundred percent`() {
        sampler.sampleSystem(times(active = 0, idle = 0))
        val second = sampler.sampleSystem(times(active = 100, idle = 0))

        assertEquals(100f, second.valueOrNull!!, 0.01f)
    }

    @Test
    fun `an unreadable counter is passed through unchanged`() {
        // The restriction from /proc must reach the UI intact — it explains itself
        // better than any message this class could invent.
        val restricted = sampler.sampleSystem(
            Observed.platform("/proc/stat is not readable by this app"),
        )

        assertTrue(restricted is Observed.Restricted)
        assertContains((restricted as Observed.Restricted).detail, "/proc/stat")
    }

    @Test
    fun `a failed read is passed through as a failure`() {
        val failed = sampler.sampleSystem(Observed.Failed("Could not parse /proc/stat"))

        assertTrue(failed is Observed.Failed)
    }

    @Test
    fun `a restricted reading does not become the baseline`() {
        // Otherwise a single denied read would poison the next successful pair.
        sampler.sampleSystem(times(active = 100, idle = 100))
        sampler.sampleSystem(Observed.platform("denied"))
        val next = sampler.sampleSystem(times(active = 200, idle = 200))

        assertEquals(50f, next.valueOrNull!!, 0.01f)
    }

    @Test
    fun `resetting clears the baseline so the next sample waits again`() {
        sampler.sampleSystem(times(active = 100, idle = 100))
        sampler.reset()

        assertNull(sampler.sampleSystem(times(active = 200, idle = 200)).valueOrNull)
    }

    // ----------------------------------------------------------------- per core

    @Test
    fun `the first per core sample cannot produce percentages`() {
        val cores = Observed.of(
            listOf(
                CpuTimes("cpu0", 100, 0, 0, 100, 0, 0, 0, 0),
                CpuTimes("cpu1", 100, 0, 0, 100, 0, 0, 0, 0),
            ),
            DataSource.PROC_FS,
        )

        assertNull(sampler.samplePerCore(cores).valueOrNull)
    }

    @Test
    fun `per core utilisation is computed independently for each core`() {
        val first = Observed.of(
            listOf(
                CpuTimes("cpu0", 0, 0, 0, 0, 0, 0, 0, 0),
                CpuTimes("cpu1", 0, 0, 0, 0, 0, 0, 0, 0),
            ),
            DataSource.PROC_FS,
        )
        val second = Observed.of(
            listOf(
                // cpu0 spent all 100 jiffies busy; cpu1 spent a quarter of them busy.
                CpuTimes("cpu0", 100, 0, 0, 0, 0, 0, 0, 0),
                CpuTimes("cpu1", 25, 0, 0, 75, 0, 0, 0, 0),
            ),
            DataSource.PROC_FS,
        )

        sampler.samplePerCore(first)
        val values = sampler.samplePerCore(second).valueOrNull!!
        assertEquals(2, values.size)
        assertEquals(100f, values[0], 0.01f)
        assertEquals(25f, values[1], 0.01f)
    }

    @Test
    fun `a changed core count discards the baseline rather than mismatching cores`() {
        // Big-little SoCs bring cores online and offline, which shifts the list. Zipping
        // a 4-entry list against an 8-entry one would attribute one core's work to
        // another.
        val four = Observed.of(
            List(4) { CpuTimes("cpu$it", 100, 0, 0, 100, 0, 0, 0, 0) },
            DataSource.PROC_FS,
        )
        val eight = Observed.of(
            List(8) { CpuTimes("cpu$it", 200, 0, 0, 200, 0, 0, 0, 0) },
            DataSource.PROC_FS,
        )

        sampler.samplePerCore(four)
        assertNull(sampler.samplePerCore(eight).valueOrNull)
    }

    @Test
    fun `a set of cores that all stalled yields no figures at all`() {
        val same = Observed.of(
            List(2) { CpuTimes("cpu$it", 100, 0, 0, 100, 0, 0, 0, 0) },
            DataSource.PROC_FS,
        )

        sampler.samplePerCore(same)
        val second = sampler.samplePerCore(same)

        assertNull("a wholly stalled set must not become a row of zeros", second.valueOrNull)
    }

    @Test
    fun `one parked core among several does not suppress the others`() {
        val first = Observed.of(
            listOf(
                CpuTimes("cpu0", 0, 0, 0, 0, 0, 0, 0, 0),
                CpuTimes("cpu1", 50, 0, 0, 50, 0, 0, 0, 0),
            ),
            DataSource.PROC_FS,
        )
        val second = Observed.of(
            listOf(
                CpuTimes("cpu0", 50, 0, 0, 50, 0, 0, 0, 0),
                // cpu1 was offline for the interval: its counters are frozen.
                CpuTimes("cpu1", 50, 0, 0, 50, 0, 0, 0, 0),
            ),
            DataSource.PROC_FS,
        )

        sampler.samplePerCore(first)
        val values = sampler.samplePerCore(second).valueOrNull!!
        assertEquals(50f, values[0], 0.01f)
        assertEquals(0f, values[1], 0.01f)
    }

    // ---------------------------------------------------------------- per process

    @Test
    fun `the first process sample cannot produce a percentage`() {
        val first = sampler.sampleProcess(pid = 1234, cpuJiffies = 500L, nowElapsedMillis = 1_000L)

        assertNull(first.valueOrNull)
        assertEquals(
            RestrictionReason.NOT_PRESENT_ON_DEVICE,
            (first as Observed.Restricted).reason,
        )
    }

    @Test
    fun `process usage is a share of one core per wall second`() {
        // 100 jiffies is 1000 ms of CPU. Over 1000 ms of wall time that is 100% of one
        // core — the convention `top` uses, so a two-thread hot loop reads above 100%
        // instead of being flattened to "50% of the device".
        sampler.sampleProcess(pid = 1, cpuJiffies = 0L, nowElapsedMillis = 0L)
        val second = sampler.sampleProcess(pid = 1, cpuJiffies = 100L, nowElapsedMillis = 1_000L)

        assertEquals(100f, second.valueOrNull!!, 0.01f)
    }

    @Test
    fun `a half busy process reads as fifty percent`() {
        sampler.sampleProcess(pid = 1, cpuJiffies = 0L, nowElapsedMillis = 0L)
        val second = sampler.sampleProcess(pid = 1, cpuJiffies = 50L, nowElapsedMillis = 1_000L)

        assertEquals(50f, second.valueOrNull!!, 0.01f)
    }

    @Test
    fun `an idle process reads as a measured zero`() {
        // Distinct from "unavailable": the counter did advance in wall-clock terms and
        // charged this process nothing, which is a real measurement of 0%.
        sampler.sampleProcess(pid = 1, cpuJiffies = 400L, nowElapsedMillis = 0L)
        val second = sampler.sampleProcess(pid = 1, cpuJiffies = 400L, nowElapsedMillis = 2_000L)

        assertEquals(0f, second.valueOrNull!!, 0.01f)
    }

    @Test
    fun `no elapsed wall time yields no figure`() {
        sampler.sampleProcess(pid = 1, cpuJiffies = 0L, nowElapsedMillis = 1_000L)
        val second = sampler.sampleProcess(pid = 1, cpuJiffies = 100L, nowElapsedMillis = 1_000L)

        assertNull(second.valueOrNull)
    }

    @Test
    fun `a process whose counter went backwards is treated as pid reuse`() {
        // Linux recycles PIDs. A negative jiffy delta means this number now belongs to
        // a different process, and clamping it to zero would report a stranger's usage.
        sampler.sampleProcess(pid = 1, cpuJiffies = 5_000L, nowElapsedMillis = 0L)
        val second = sampler.sampleProcess(pid = 1, cpuJiffies = 10L, nowElapsedMillis = 1_000L)

        assertTrue(second is Observed.Failed)
        assertContains((second as Observed.Failed).detail, "reused")
    }

    @Test
    fun `each process keeps its own baseline`() {
        sampler.sampleProcess(pid = 1, cpuJiffies = 0L, nowElapsedMillis = 0L)
        sampler.sampleProcess(pid = 2, cpuJiffies = 0L, nowElapsedMillis = 0L)

        val one = sampler.sampleProcess(pid = 1, cpuJiffies = 100L, nowElapsedMillis = 1_000L)
        val two = sampler.sampleProcess(pid = 2, cpuJiffies = 25L, nowElapsedMillis = 1_000L)

        assertEquals(100f, one.valueOrNull!!, 0.01f)
        assertEquals(25f, two.valueOrNull!!, 0.01f)
    }

    @Test
    fun `baselines for processes that are gone are dropped`() {
        // Section 43: the tool must not become the problem it investigates. Without
        // this the map grows by every short-lived process for the life of the app.
        sampler.sampleProcess(pid = 1, cpuJiffies = 0L, nowElapsedMillis = 0L)
        sampler.sampleProcess(pid = 2, cpuJiffies = 0L, nowElapsedMillis = 0L)
        sampler.sampleProcess(pid = 3, cpuJiffies = 0L, nowElapsedMillis = 0L)
        assertEquals(3, sampler.trackedProcessCount)

        sampler.retainOnly(setOf(1, 3))

        assertEquals(2, sampler.trackedProcessCount)
        // pid 2 lost its baseline, so it has to wait for a second sample again.
        assertNull(sampler.sampleProcess(pid = 2, cpuJiffies = 100L, nowElapsedMillis = 1_000L).valueOrNull)
    }

    @Test
    fun `retaining nothing clears every baseline`() {
        sampler.sampleProcess(pid = 1, cpuJiffies = 0L, nowElapsedMillis = 0L)
        sampler.retainOnly(emptySet())

        assertEquals(0, sampler.trackedProcessCount)
    }
}
