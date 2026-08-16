package com.processlens.domain.usecase

import com.processlens.domain.model.EventGroup
import com.processlens.domain.model.EventType
import com.processlens.domain.model.InvestigationEvent
import com.processlens.domain.model.Observed3State
import com.processlens.domain.model.ProcessSnapshot
import com.processlens.testing.Fixtures
import com.processlens.testing.assertContains
import com.processlens.testing.assertDoesNotContain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Summary derivation (Sections 14, 15, 17, 42, 56).
 *
 * This is the test class that matters most in the project. [InvestigationSummarizer] is
 * where sampled numbers become sentences a person will act on, so a bug here does not
 * crash anything — it quietly sends someone to uninstall the wrong app. The cases below
 * are therefore weighted towards what the summariser must *refuse* to say:
 *
 *  - a figure the samples cannot support is absent, not zero (Section 42);
 *  - a co-occurrence is reported as a co-occurrence, never as a cause (Section 15);
 *  - a metric that was unreadable is named in the limitations, so an empty finding is
 *    never mistaken for an absence of activity.
 *
 * The internal helpers are exercised directly as well as through [summarize], because
 * each encodes an independent judgement (is this drain real? did the counters move?)
 * and reaching them only through the aggregate would leave the boundaries untested.
 */
class InvestigationSummarizerTest {

    private fun summarize(
        snapshots: List<ProcessSnapshot>,
        events: List<InvestigationEvent> = emptyList(),
    ) = InvestigationSummarizer.summarize(Fixtures.investigation(), snapshots, events)

    // ------------------------------------------------------------ nothing sampled

    @Test
    fun `a recording with no samples concludes nothing at all`() {
        val summary = summarize(emptyList())

        // Not a summary of zeroes. Zeroes would read as "nothing happened"; the truth is
        // "nothing was observed", and those are different findings.
        assertNull(summary.highestCpuProcess)
        assertNull(summary.largestMemoryIncrease)
        assertNull(summary.mostFrequentlyRestarted)
        assertNull(summary.batteryDrainPercent)
        assertNull(summary.batteryTemperatureRiseDeciCelsius)
        assertFalse(summary.networkActivityDetected)
        assertTrue(summary.correlations.isEmpty())
        assertContains(summary.limitations.single(), "No samples were captured")
    }

    @Test
    fun `with no samples wake locks are not observable rather than absent`() {
        // "Not detected" would be a claim about the device. Nothing was sampled, so the
        // only honest answer is that the question was never asked.
        assertEquals(
            Observed3State.NOT_OBSERVABLE,
            summarize(emptyList()).wakeLockActivityDetected,
        )
    }

    @Test
    fun `the investigation being summarised is carried through`() {
        val investigation = Fixtures.investigation(id = 7L, name = "Overnight drain")
        val summary = InvestigationSummarizer.summarize(investigation, emptyList(), emptyList())
        assertEquals(investigation, summary.investigation)
    }

    @Test
    fun `duration is measured between the first and last sample`() {
        // The samples, not the requested duration: a recording cut short by process death
        // covers the window it actually observed.
        val summary = summarize(
            listOf(
                Fixtures.snapshot(timestamp = Fixtures.T0),
                Fixtures.snapshot(timestamp = Fixtures.T0 + 8_000L),
            ),
        )
        assertEquals(8_000L, summary.durationMillis)
    }

    // -------------------------------------------------------------------- cpu peak

    @Test
    fun `peak cpu is attributed to the process that held it`() {
        val summary = summarize(
            listOf(
                Fixtures.snapshot(
                    timestamp = Fixtures.T0,
                    entries = listOf(
                        Fixtures.entry("com.a", cpuPercent = 12f),
                        Fixtures.entry("com.b", cpuPercent = 4f),
                    ),
                ),
                Fixtures.snapshot(
                    timestamp = Fixtures.T0 + 2_000L,
                    entries = listOf(
                        Fixtures.entry("com.a", cpuPercent = 9f),
                        Fixtures.entry("com.b", cpuPercent = 71f),
                    ),
                ),
            ),
        )

        val peak = summary.highestCpuProcess!!
        assertEquals("com.b", peak.processName)
        assertEquals(71.0, peak.value, 0.001)
        assertEquals("%", peak.unit)
        // The label has to say "observed", so the reader does not take a sampled peak
        // for a sustained rate.
        assertContains(peak.label, "Highest observed")
    }

    @Test
    fun `a brief spike outranks a higher average`() {
        // "steady" averages 30% and "spiky" averages 19%, but an investigation is looking
        // for what spiked. Averaging would hide the 90% moment entirely.
        val snapshots = (0..4).map { i ->
            Fixtures.snapshot(
                timestamp = Fixtures.T0 + i * 2_000L,
                entries = listOf(
                    Fixtures.entry("steady", cpuPercent = 30f),
                    Fixtures.entry("spiky", cpuPercent = if (i == 2) 90f else 1f),
                ),
            )
        }

        assertEquals("spiky", summarize(snapshots).highestCpuProcess?.processName)
    }

    @Test
    fun `no peak is invented when per-process cpu was unreadable`() {
        val snapshots = listOf(
            Fixtures.snapshot(entries = listOf(Fixtures.entry("com.a", cpuPercent = null))),
        )
        assertNull(summarize(snapshots).highestCpuProcess)
    }

    // ----------------------------------------------------------------- memory growth

    @Test
    fun `memory growth is tracked across a restart`() {
        // Same process, new PID: keyed by name so growth spanning a restart is not lost.
        val snapshots = listOf(
            Fixtures.snapshot(
                timestamp = Fixtures.T0,
                entries = listOf(Fixtures.entry("com.leak", pid = 100, memoryBytes = 100_000_000L)),
            ),
            Fixtures.snapshot(
                timestamp = Fixtures.T0 + 2_000L,
                entries = listOf(Fixtures.entry("com.leak", pid = 900, memoryBytes = 400_000_000L)),
            ),
        )

        val growth = summarize(snapshots).largestMemoryIncrease!!
        assertEquals("com.leak", growth.processName)
        assertEquals(300_000_000.0, growth.value, 0.0)
        assertEquals("bytes", growth.unit)
    }

    @Test
    fun `the largest grower is the one reported`() {
        val snapshots = listOf(
            Fixtures.snapshot(
                timestamp = Fixtures.T0,
                entries = listOf(
                    Fixtures.entry("small", memoryBytes = 10_000_000L),
                    Fixtures.entry("large", memoryBytes = 50_000_000L),
                ),
            ),
            Fixtures.snapshot(
                timestamp = Fixtures.T0 + 2_000L,
                entries = listOf(
                    Fixtures.entry("small", memoryBytes = 30_000_000L),
                    Fixtures.entry("large", memoryBytes = 300_000_000L),
                ),
            ),
        )

        assertEquals("large", summarize(snapshots).largestMemoryIncrease?.processName)
    }

    @Test
    fun `a process that only released memory is not reported as growth`() {
        val snapshots = listOf(
            Fixtures.snapshot(
                timestamp = Fixtures.T0,
                entries = listOf(Fixtures.entry("com.tidy", memoryBytes = 400_000_000L)),
            ),
            Fixtures.snapshot(
                timestamp = Fixtures.T0 + 2_000L,
                entries = listOf(Fixtures.entry("com.tidy", memoryBytes = 100_000_000L)),
            ),
        )
        assertNull(summarize(snapshots).largestMemoryIncrease)
    }

    @Test
    fun `unreadable memory produces no growth figure`() {
        val snapshots = List(2) {
            Fixtures.snapshot(
                timestamp = Fixtures.T0 + it * 2_000L,
                entries = listOf(Fixtures.entry("com.a", memoryBytes = null)),
            )
        }
        assertNull(summarize(snapshots).largestMemoryIncrease)
    }

    // ---------------------------------------------------------------------- restarts

    @Test
    fun `restarts are counted from recorded events and never inferred from pid changes`() {
        // Three samples showing one name at three PIDs looks exactly like two restarts —
        // and is not evidence of any, because a PID can change for reasons this recording
        // did not witness. Section 42 forbids inventing the relationship.
        val snapshots = listOf(100, 200, 300).mapIndexed { i, pid ->
            Fixtures.snapshot(
                timestamp = Fixtures.T0 + i * 2_000L,
                entries = listOf(Fixtures.entry("com.restarter", pid = pid)),
            )
        }
        assertNull(summarize(snapshots).mostFrequentlyRestarted)
    }

    @Test
    fun `a single start is not a restart`() {
        val events = listOf(
            Fixtures.event(type = EventType.PROCESS_STARTED, processName = "com.a"),
        )
        assertNull(summarize(listOf(Fixtures.snapshot()), events).mostFrequentlyRestarted)
    }

    @Test
    fun `the most frequently restarted process is reported with its count`() {
        val events = listOf(
            Fixtures.event(type = EventType.PROCESS_STARTED, processName = "com.a", packageName = "com.a"),
            Fixtures.event(type = EventType.PROCESS_RESTARTED, processName = "com.a", packageName = "com.a"),
            Fixtures.event(type = EventType.PROCESS_RESTARTED, processName = "com.a", packageName = "com.a"),
            Fixtures.event(type = EventType.PROCESS_STARTED, processName = "com.b", packageName = "com.b"),
            Fixtures.event(type = EventType.PROCESS_RESTARTED, processName = "com.b", packageName = "com.b"),
        )

        val ranked = summarize(listOf(Fixtures.snapshot()), events).mostFrequentlyRestarted!!
        assertEquals("com.a", ranked.processName)
        assertEquals("com.a", ranked.packageName)
        assertEquals(3.0, ranked.value, 0.0)
        assertEquals("starts", ranked.unit)
    }

    // ----------------------------------------------------------------------- battery

    @Test
    fun `battery drain is the fall in level while discharging`() {
        val snapshots = listOf(
            Fixtures.snapshot(timestamp = Fixtures.T0, batteryLevel = 80),
            Fixtures.snapshot(timestamp = Fixtures.T0 + 2_000L, batteryLevel = 71),
        )
        assertEquals(9, InvestigationSummarizer.batteryDrain(snapshots))
    }

    @Test
    fun `no drain figure is produced when the device charged at any point`() {
        // 40 → 60 → 45 did not "drain 5%". Subtracting the endpoints across a charge
        // cycle is exactly the fabricated battery figure Section 17 prohibits, so the
        // answer is no figure rather than a wrong one.
        val snapshots = listOf(
            Fixtures.snapshot(timestamp = Fixtures.T0, batteryLevel = 40, isCharging = false),
            Fixtures.snapshot(timestamp = Fixtures.T0 + 2_000L, batteryLevel = 60, isCharging = true),
            Fixtures.snapshot(timestamp = Fixtures.T0 + 4_000L, batteryLevel = 45, isCharging = false),
        )
        assertNull(InvestigationSummarizer.batteryDrain(snapshots))
    }

    @Test
    fun `a level that rose while discharging is not reported as drain`() {
        // Levels do jump upwards after a calibration correction. A negative drain is not
        // a finding, so nothing is reported.
        val snapshots = listOf(
            Fixtures.snapshot(timestamp = Fixtures.T0, batteryLevel = 70),
            Fixtures.snapshot(timestamp = Fixtures.T0 + 2_000L, batteryLevel = 74),
        )
        assertNull(InvestigationSummarizer.batteryDrain(snapshots))
    }

    @Test
    fun `an unchanged level is absent rather than a zero percent drain`() {
        val snapshots = List(2) {
            Fixtures.snapshot(timestamp = Fixtures.T0 + it * 2_000L, batteryLevel = 55)
        }
        assertNull(InvestigationSummarizer.batteryDrain(snapshots))
    }

    @Test
    fun `battery drain of an empty recording is absent not zero`() {
        assertNull(InvestigationSummarizer.batteryDrain(emptyList()))
    }

    @Test
    fun `temperature rise is measured from the first sample to the peak`() {
        val snapshots = listOf(305, 348, 322).mapIndexed { i, temp ->
            Fixtures.snapshot(
                timestamp = Fixtures.T0 + i * 2_000L,
                batteryTemperatureDeciCelsius = temp,
            )
        }
        // The peak matters, not the endpoint: a device that reached 34.8 °C and then
        // cooled still got hot, and the endpoint difference would hide it.
        assertEquals(43, InvestigationSummarizer.temperatureRise(snapshots))
    }

    @Test
    fun `a single temperature reading cannot establish a rise`() {
        val snapshots = listOf(Fixtures.snapshot(batteryTemperatureDeciCelsius = 305))
        assertNull(InvestigationSummarizer.temperatureRise(snapshots))
    }

    @Test
    fun `a device that does not report temperature yields no rise`() {
        val snapshots = List(3) {
            Fixtures.snapshot(
                timestamp = Fixtures.T0 + it * 2_000L,
                batteryTemperatureDeciCelsius = null,
            )
        }
        assertNull(InvestigationSummarizer.temperatureRise(snapshots))
    }

    @Test
    fun `a temperature that only fell is not reported as a rise`() {
        val snapshots = listOf(348, 320).mapIndexed { i, temp ->
            Fixtures.snapshot(
                timestamp = Fixtures.T0 + i * 2_000L,
                batteryTemperatureDeciCelsius = temp,
            )
        }
        assertNull(InvestigationSummarizer.temperatureRise(snapshots))
    }

    // ----------------------------------------------------------------------- network

    @Test
    fun `network activity is reported only when the counters actually moved`() {
        val moved = listOf(
            Fixtures.snapshot(timestamp = Fixtures.T0, networkRxBytes = 1_000L),
            Fixtures.snapshot(timestamp = Fixtures.T0 + 2_000L, networkRxBytes = 9_000L),
        )
        assertTrue(InvestigationSummarizer.networkActivity(moved))
    }

    @Test
    fun `counters that moved only on transmit still count as activity`() {
        val moved = listOf(
            Fixtures.snapshot(timestamp = Fixtures.T0, networkRxBytes = 1_000L, networkTxBytes = 10L),
            Fixtures.snapshot(timestamp = Fixtures.T0 + 2_000L, networkRxBytes = 1_000L, networkTxBytes = 900L),
        )
        assertTrue(InvestigationSummarizer.networkActivity(moved))
    }

    @Test
    fun `unchanged counters are not network activity`() {
        val still = List(3) {
            Fixtures.snapshot(
                timestamp = Fixtures.T0 + it * 2_000L,
                networkRxBytes = 4_096L,
                networkTxBytes = 2_048L,
            )
        }
        assertFalse(InvestigationSummarizer.networkActivity(still))
    }

    @Test
    fun `unreadable counters are false but explained rather than left as an absence`() {
        // Asserted together on purpose: `false` alone would read as "no traffic", which
        // is a claim this recording cannot make. The limitation is what makes the false
        // honest, so if either half regressed this test fails.
        val unreadable = List(3) {
            Fixtures.snapshot(
                timestamp = Fixtures.T0 + it * 2_000L,
                networkRxBytes = null,
                networkTxBytes = null,
            )
        }

        assertFalse(InvestigationSummarizer.networkActivity(unreadable))
        assertTrue(
            InvestigationSummarizer.limitations(unreadable, emptyList())
                .any { it.contains("Network byte counters were not readable") },
        )
    }

    // --------------------------------------------------------------------- wakelocks

    @Test
    fun `a recorded wakelock event is detected`() {
        val events = listOf(Fixtures.event(type = EventType.WAKELOCK_ACQUIRED))
        assertEquals(Observed3State.DETECTED, InvestigationSummarizer.wakeLockState(events))
    }

    @Test
    fun `no wakelock events and no stated limit reads as not detected`() {
        val events = listOf(Fixtures.event(type = EventType.CPU_SPIKE))
        assertEquals(Observed3State.NOT_DETECTED, InvestigationSummarizer.wakeLockState(events))
    }

    @Test
    fun `a declared wakelock limit reads as not observable`() {
        // The difference between "this app held no wake locks" and "we cannot see wake
        // locks on this device" decides whether a battery investigation is finished.
        val events = listOf(
            Fixtures.event(
                type = EventType.OBSERVATION_LIMITED,
                detail = "Wake lock observation requires Shizuku or root access.",
            ),
        )
        assertEquals(Observed3State.NOT_OBSERVABLE, InvestigationSummarizer.wakeLockState(events))
    }

    @Test
    fun `an unrelated limit does not make wakelocks unobservable`() {
        val events = listOf(
            Fixtures.event(
                type = EventType.OBSERVATION_LIMITED,
                detail = "Per-app network usage requires usage access.",
            ),
        )
        assertEquals(Observed3State.NOT_DETECTED, InvestigationSummarizer.wakeLockState(events))
    }

    // ------------------------------------------------------------------ correlations

    @Test
    fun `a cpu spike near a temperature rise is a potential correlation only`() {
        val events = listOf(
            Fixtures.event(type = EventType.CPU_SPIKE, timestamp = Fixtures.T0 + 4_000L, title = "CPU spike"),
            Fixtures.event(
                type = EventType.BATTERY_TEMPERATURE_RISE,
                timestamp = Fixtures.T0 + 9_000L,
                title = "Battery temperature rose",
            ),
        )

        val text = InvestigationSummarizer.correlations(listOf(Fixtures.snapshot()), events).single()

        assertContains(text, "Potential correlation")
        assertContains(text, "does not establish that one caused the other")
        // The sentence Section 15 names explicitly as forbidden.
        assertDoesNotContain(text, "This caused")
    }

    @Test
    fun `a cpu spike far from a temperature rise is not correlated at all`() {
        // 40 seconds apart. Sampling at two-second intervals cannot link those, so
        // nothing is said rather than something hedged.
        val events = listOf(
            Fixtures.event(type = EventType.CPU_SPIKE, timestamp = Fixtures.T0),
            Fixtures.event(
                type = EventType.BATTERY_TEMPERATURE_RISE,
                timestamp = Fixtures.T0 + 40_000L,
            ),
        )
        assertTrue(InvestigationSummarizer.correlations(listOf(Fixtures.snapshot()), events).isEmpty())
    }

    @Test
    fun `the correlation window is exactly the documented ten seconds`() {
        fun correlationsWithGap(gap: Long) = InvestigationSummarizer.correlations(
            listOf(Fixtures.snapshot()),
            listOf(
                Fixtures.event(type = EventType.CPU_SPIKE, timestamp = Fixtures.T0),
                Fixtures.event(type = EventType.BATTERY_TEMPERATURE_RISE, timestamp = Fixtures.T0 + gap),
            ),
        )

        assertEquals(10_000L, InvestigationSummarizer.CORRELATION_WINDOW_MILLIS)
        assertEquals(1, correlationsWithGap(InvestigationSummarizer.CORRELATION_WINDOW_MILLIS).size)
        assertEquals(0, correlationsWithGap(InvestigationSummarizer.CORRELATION_WINDOW_MILLIS + 1).size)
    }

    @Test
    fun `repeated spikes produce one finding rather than a wall of identical sentences`() {
        val events = (0..9).flatMap { i ->
            listOf(
                Fixtures.event(type = EventType.CPU_SPIKE, timestamp = Fixtures.T0 + i * 20_000L),
                Fixtures.event(
                    type = EventType.BATTERY_TEMPERATURE_RISE,
                    timestamp = Fixtures.T0 + i * 20_000L + 1_000L,
                ),
            )
        }
        assertEquals(1, InvestigationSummarizer.correlations(listOf(Fixtures.snapshot()), events).size)
    }

    @Test
    fun `memory growth during discharge is correlational not causal`() {
        val snapshots = listOf(
            Fixtures.snapshot(timestamp = Fixtures.T0, batteryLevel = 80),
            Fixtures.snapshot(timestamp = Fixtures.T0 + 2_000L, batteryLevel = 74),
        )
        val events = listOf(Fixtures.event(type = EventType.MEMORY_INCREASE))

        val text = InvestigationSummarizer.correlations(snapshots, events)
            .first { it.contains("memory growth") }

        assertContains(text, "Potential correlation")
        assertContains(text, "no causal link is implied")
    }

    @Test
    fun `memory growth without a usable drain figure is not correlated with the battery`() {
        // The device charged, so there is no drain figure to correlate against, and the
        // summariser does not reach for the endpoints anyway.
        val snapshots = listOf(
            Fixtures.snapshot(timestamp = Fixtures.T0, batteryLevel = 80, isCharging = true),
            Fixtures.snapshot(timestamp = Fixtures.T0 + 2_000L, batteryLevel = 74, isCharging = true),
        )
        val events = listOf(Fixtures.event(type = EventType.MEMORY_INCREASE))

        assertTrue(
            InvestigationSummarizer.correlations(snapshots, events)
                .none { it.contains("memory growth") },
        )
    }

    @Test
    fun `screen-off traffic is never attributed to a process`() {
        // Section 42: Android's device-wide counters do not name a responsible app, and
        // the one thing a user in this situation most wants is a name. Saying "we cannot
        // tell you which" is the whole point of the sentence.
        val snapshots = listOf(
            Fixtures.snapshot(timestamp = Fixtures.T0, isScreenOn = false, networkRxBytes = 1_000L),
            Fixtures.snapshot(timestamp = Fixtures.T0 + 2_000L, isScreenOn = false, networkRxBytes = 80_000L),
        )

        val text = InvestigationSummarizer.correlations(snapshots, emptyList())
            .first { it.contains("screen was off") }

        assertContains(text, "Potential correlation")
        assertContains(text, "does not attribute device-wide traffic counters to a specific process")
    }

    @Test
    fun `screen-on traffic is not reported as a screen-off correlation`() {
        val snapshots = listOf(
            Fixtures.snapshot(timestamp = Fixtures.T0, isScreenOn = true, networkRxBytes = 1_000L),
            Fixtures.snapshot(timestamp = Fixtures.T0 + 2_000L, isScreenOn = true, networkRxBytes = 80_000L),
        )
        assertTrue(
            InvestigationSummarizer.correlations(snapshots, emptyList())
                .none { it.contains("screen was off") },
        )
    }

    @Test
    fun `every correlation is hedged as potential or as observed repetition`() {
        // The regression guard for Section 15 as a whole. Whatever a future edit adds to
        // this list, it has to open with a hedge and must not contain a causal claim.
        val snapshots = listOf(
            Fixtures.snapshot(
                timestamp = Fixtures.T0,
                batteryLevel = 80,
                isScreenOn = false,
                networkRxBytes = 100L,
            ),
            Fixtures.snapshot(
                timestamp = Fixtures.T0 + 2_000L,
                batteryLevel = 70,
                isScreenOn = false,
                networkRxBytes = 9_000L,
            ),
        )
        val events = listOf(
            Fixtures.event(type = EventType.CPU_SPIKE, timestamp = Fixtures.T0),
            Fixtures.event(type = EventType.BATTERY_TEMPERATURE_RISE, timestamp = Fixtures.T0 + 1_000L),
            Fixtures.event(type = EventType.MEMORY_INCREASE, timestamp = Fixtures.T0 + 2_000L),
            Fixtures.event(type = EventType.PROCESS_STARTED, processName = "com.a", timestamp = Fixtures.T0),
            Fixtures.event(
                type = EventType.PROCESS_RESTARTED,
                processName = "com.a",
                timestamp = Fixtures.T0 + 2_000L,
            ),
        )

        val all = InvestigationSummarizer.correlations(snapshots, events)

        assertEquals(4, all.size)
        for (line in all) {
            assertTrue(
                "unhedged correlation: $line",
                line.startsWith("Potential correlation:") || line.startsWith("Repeated activity:"),
            )
            assertDoesNotContain(line, "This caused")
            assertDoesNotContain(line, "was caused by")
            assertDoesNotContain(line, "because of")
            assertDoesNotContain(line, "responsible for")
        }
    }

    @Test
    fun `repeated starts are described as observed activity not as a fault`() {
        val events = listOf(
            Fixtures.event(type = EventType.PROCESS_STARTED, processName = "com.a"),
            Fixtures.event(type = EventType.PROCESS_RESTARTED, processName = "com.a"),
        )

        val text = InvestigationSummarizer.correlations(listOf(Fixtures.snapshot()), events)
            .first { it.startsWith("Repeated activity:") }

        assertContains(text, "com.a")
        assertContains(text, "was observed starting 2 times")
    }

    // ------------------------------------------------------------------- limitations

    @Test
    fun `each unreadable metric is named in the limitations`() {
        val blind = List(2) {
            Fixtures.snapshot(
                timestamp = Fixtures.T0 + it * 2_000L,
                cpuPercent = null,
                batteryTemperatureDeciCelsius = null,
                networkRxBytes = null,
                networkTxBytes = null,
                entries = listOf(Fixtures.entry("self", cpuPercent = null)),
            )
        }

        val limits = InvestigationSummarizer.limitations(blind, emptyList())

        assertTrue(limits.any { it.contains("System-wide CPU usage") })
        assertTrue(limits.any { it.contains("Network byte counters") })
        assertTrue(limits.any { it.contains("Battery temperature") })
        assertTrue(limits.any { it.contains("Wake locks require") })
        assertTrue(limits.any { it.contains("Per-process CPU usage") })
    }

    @Test
    fun `a fully observable recording claims no limitations`() {
        // Proof that the list is derived rather than boilerplate. If it were hardcoded
        // warnings, a clean recording would still carry them and every real limitation
        // would lose its meaning.
        val snapshots = List(2) {
            Fixtures.snapshot(
                timestamp = Fixtures.T0 + it * 2_000L,
                entries = listOf(Fixtures.entry("com.a")),
            )
        }
        val events = listOf(Fixtures.event(type = EventType.WAKELOCK_ACQUIRED))

        assertTrue(InvestigationSummarizer.limitations(snapshots, events).isEmpty())
    }

    @Test
    fun `a detected wakelock removes the wakelock caveat`() {
        val snapshots = listOf(Fixtures.snapshot(entries = listOf(Fixtures.entry("com.a"))))

        val withoutEvents = InvestigationSummarizer.limitations(snapshots, emptyList())
        val withEvent = InvestigationSummarizer.limitations(
            snapshots,
            listOf(Fixtures.event(type = EventType.WAKELOCK_ACQUIRED)),
        )

        assertTrue(withoutEvents.any { it.contains("Wake locks require") })
        assertTrue(withEvent.none { it.contains("Wake locks require") })
    }

    @Test
    fun `own-process-only visibility is stated outright`() {
        // The restricted-device case: one process in every sample means Android hid the
        // rest, and a short list must not be mistaken for a quiet device.
        val snapshots = List(2) {
            Fixtures.snapshot(
                timestamp = Fixtures.T0 + it * 2_000L,
                processCount = 1,
                entries = listOf(Fixtures.entry("com.processlens")),
            )
        }

        assertTrue(
            InvestigationSummarizer.limitations(snapshots, emptyList())
                .any { it.contains("limited process visibility") },
        )
    }

    @Test
    fun `a device that showed many processes gets no visibility caveat`() {
        val snapshots = List(2) {
            Fixtures.snapshot(
                timestamp = Fixtures.T0 + it * 2_000L,
                processCount = 180,
                entries = listOf(Fixtures.entry("com.a")),
            )
        }

        assertTrue(
            InvestigationSummarizer.limitations(snapshots, emptyList())
                .none { it.contains("limited process visibility") },
        )
    }

    @Test
    fun `capability losses recorded mid-recording are carried through verbatim and once`() {
        val detail = "Usage access was revoked during this recording, so later app activity was not observed."
        val events = List(3) {
            Fixtures.event(
                type = EventType.OBSERVATION_LIMITED,
                timestamp = Fixtures.T0 + it * 2_000L,
                detail = detail,
            )
        }
        val snapshots = listOf(Fixtures.snapshot(entries = listOf(Fixtures.entry("com.a"))))

        val limits = InvestigationSummarizer.limitations(snapshots, events)

        // Verbatim, because the recorder wrote the sentence that names what was lost and
        // when — and exactly once, because three identical warnings tell no one anything.
        assertEquals(1, limits.count { it == detail })
    }

    // ---------------------------------------------------------------- event counting

    @Test
    fun `events are counted by group with absent groups left absent`() {
        val events = listOf(
            Fixtures.event(type = EventType.CPU_SPIKE),
            Fixtures.event(type = EventType.CPU_SUSTAINED),
            Fixtures.event(type = EventType.MEMORY_INCREASE),
        )

        val counts = summarize(listOf(Fixtures.snapshot()), events).eventCounts

        assertEquals(2, counts[EventGroup.CPU])
        assertEquals(1, counts[EventGroup.MEMORY])
        // Absent rather than zero: the timeline filter shows only groups that occurred.
        assertNull(counts[EventGroup.NETWORK])
    }

    @Test
    fun `events are counted even when no samples were captured`() {
        // A recording can hold events without snapshots if sampling failed immediately;
        // the count still reflects what was written.
        val events = listOf(Fixtures.event(type = EventType.INVESTIGATION_STARTED))
        assertEquals(1, summarize(emptyList(), events).eventCounts[EventGroup.SYSTEM])
    }
}
