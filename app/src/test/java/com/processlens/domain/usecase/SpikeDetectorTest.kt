package com.processlens.domain.usecase

import com.processlens.domain.model.EventSeverity
import com.processlens.domain.model.EventType
import com.processlens.domain.model.InvestigationEvent
import com.processlens.domain.model.UserSettings
import com.processlens.testing.Fixtures
import com.processlens.testing.assertContains
import com.processlens.testing.assertDoesNotContain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Event detection for Investigation Mode (Sections 14, 15, 17, 56).
 *
 * The detector is pure — two snapshots and the user's thresholds in, a list of events
 * out — so the entire rulebook is testable here. That matters because these events are
 * the app's only claims about *what happened*, and Section 17 forbids asserting a cause
 * the data does not prove.
 *
 * The recurring theme below is that an event is a **change**, not a state. A sustained
 * 90% load must produce one event at the moment it crossed, not an identical event on
 * every sample until the recording ends.
 */
class SpikeDetectorTest {

    private val detector = SpikeDetector()
    private val settings = UserSettings()

    private fun detect(
        previous: com.processlens.domain.model.ProcessSnapshot?,
        current: com.processlens.domain.model.ProcessSnapshot,
        settings: UserSettings = this.settings,
        targetPackage: String? = null,
    ): List<InvestigationEvent> = detector.detect(
        investigationId = 7L,
        previous = previous,
        current = current,
        settings = settings,
        targetPackage = targetPackage,
    )

    private fun List<InvestigationEvent>.ofType(type: EventType) = filter { it.type == type }

    /** Two snapshots that differ in nothing, so each test changes exactly one thing. */
    private fun pair(
        cpuBefore: Float? = 10f,
        cpuAfter: Float? = 10f,
    ) = Fixtures.snapshot(timestamp = Fixtures.T0, cpuPercent = cpuBefore) to
        Fixtures.snapshot(timestamp = Fixtures.T0 + 2_000L, cpuPercent = cpuAfter)

    // -------------------------------------------------------------- preconditions

    @Test
    fun `the first sample of a recording produces no events`() {
        // A spike is a change, and there is nothing yet to have changed from. Treating
        // the first reading as a spike would manufacture an event from one measurement.
        val events = detect(previous = null, current = Fixtures.snapshot(cpuPercent = 99f))

        assertTrue("the first sample must not raise events", events.isEmpty())
    }

    @Test
    fun `automatic detection can be switched off entirely`() {
        val (before, after) = pair(cpuBefore = 1f, cpuAfter = 99f)

        val events = detect(before, after, settings.copy(automaticEventDetection = false))

        assertTrue(events.isEmpty())
    }

    // ---------------------------------------------------------------- system CPU

    @Test
    fun `crossing the cpu warning threshold raises one warning`() {
        val (before, after) = pair(cpuBefore = 20f, cpuAfter = 60f)

        val spikes = detect(before, after).ofType(EventType.CPU_SPIKE)

        assertEquals(1, spikes.size)
        assertEquals(EventSeverity.WARNING, spikes.first().severity)
        assertEquals(60.0, spikes.first().value!!, 0.001)
        assertEquals(20.0, spikes.first().previousValue!!, 0.001)
    }

    @Test
    fun `crossing the cpu critical threshold raises a critical event`() {
        val (before, after) = pair(cpuBefore = 20f, cpuAfter = 90f)

        val spikes = detect(before, after).ofType(EventType.CPU_SPIKE)

        assertEquals(1, spikes.size)
        assertEquals(EventSeverity.CRITICAL, spikes.first().severity)
    }

    @Test
    fun `staying above the threshold does not repeat the event`() {
        // The single most important rule in this file. Without it a sustained load
        // emits one event per sample and buries the moment it actually began.
        val (before, after) = pair(cpuBefore = 85f, cpuAfter = 88f)

        assertTrue(detect(before, after).ofType(EventType.CPU_SPIKE).isEmpty())
    }

    @Test
    fun `exactly reaching the threshold counts as crossing it`() {
        val (before, after) = pair(cpuBefore = 49f, cpuAfter = 50f)

        assertEquals(1, detect(before, after).ofType(EventType.CPU_SPIKE).size)
    }

    @Test
    fun `falling back below the warning threshold is recorded as information`() {
        val (before, after) = pair(cpuBefore = 70f, cpuAfter = 10f)

        val dropped = detect(before, after).ofType(EventType.CPU_DROPPED)
        assertEquals(1, dropped.size)
        assertEquals(EventSeverity.INFO, dropped.first().severity)
    }

    @Test
    fun `a rise that stays below the threshold raises nothing`() {
        val (before, after) = pair(cpuBefore = 5f, cpuAfter = 40f)

        assertTrue(detect(before, after).ofType(EventType.CPU_SPIKE).isEmpty())
    }

    @Test
    fun `custom thresholds are honoured`() {
        val (before, after) = pair(cpuBefore = 5f, cpuAfter = 30f)
        val strict = settings.copy(cpuWarningThreshold = 25, cpuCriticalThreshold = 90)

        assertEquals(1, detect(before, after, strict).ofType(EventType.CPU_SPIKE).size)
    }

    @Test
    fun `an unreadable cpu figure in either sample raises no cpu event`() {
        // A null here means /proc was not readable. Comparing against an assumed 0
        // would invent a spike out of a restriction.
        val (before, after) = pair(cpuBefore = null, cpuAfter = 95f)
        assertTrue(detect(before, after).ofType(EventType.CPU_SPIKE).isEmpty())

        val (before2, after2) = pair(cpuBefore = 95f, cpuAfter = null)
        assertTrue(detect(before2, after2).ofType(EventType.CPU_SPIKE).isEmpty())
    }

    // --------------------------------------------------------------- process CPU

    @Test
    fun `a per process spike names the process and its readings`() {
        val before = Fixtures.snapshot(
            entries = listOf(Fixtures.entry("com.example.app", cpuPercent = 5f)),
        )
        val after = Fixtures.snapshot(
            timestamp = Fixtures.T0 + 2_000L,
            entries = listOf(Fixtures.entry("com.example.app", cpuPercent = 70f)),
        )

        val spikes = detect(before, after).ofType(EventType.CPU_SPIKE)
            .filter { it.processName != null }
        assertEquals(1, spikes.size)
        val spike = spikes.first()
        assertEquals("com.example.app", spike.processName)
        assertEquals("com.example.app", spike.packageName)
        assertEquals(EventSeverity.WARNING, spike.severity)
        assertContains(spike.detail, "5%")
        assertContains(spike.detail, "70%")
    }

    @Test
    fun `a per process spike above the critical threshold is critical`() {
        val before = Fixtures.snapshot(entries = listOf(Fixtures.entry("app", cpuPercent = 5f)))
        val after = Fixtures.snapshot(
            timestamp = Fixtures.T0 + 2_000L,
            entries = listOf(Fixtures.entry("app", cpuPercent = 95f)),
        )

        val spike = detect(before, after).ofType(EventType.CPU_SPIKE)
            .first { it.processName != null }
        assertEquals(EventSeverity.CRITICAL, spike.severity)
    }

    @Test
    fun `a targeted investigation ignores other processes`() {
        val before = Fixtures.snapshot(
            entries = listOf(
                Fixtures.entry("com.example.target", cpuPercent = 5f),
                Fixtures.entry("com.other.app", cpuPercent = 5f),
            ),
        )
        val after = Fixtures.snapshot(
            timestamp = Fixtures.T0 + 2_000L,
            entries = listOf(
                Fixtures.entry("com.example.target", cpuPercent = 70f),
                Fixtures.entry("com.other.app", cpuPercent = 90f),
            ),
        )

        val named = detect(before, after, targetPackage = "com.example.target")
            .ofType(EventType.CPU_SPIKE)
            .filter { it.processName != null }
        assertEquals(1, named.size)
        assertEquals("com.example.target", named.first().processName)
    }

    @Test
    fun `a targeted investigation still matches a colon suffixed subprocess`() {
        // Android names extra processes "pkg:remote"; they are the same app.
        val before = Fixtures.snapshot(
            entries = listOf(
                Fixtures.entry("com.example.app:remote", packageName = null, cpuPercent = 5f),
                Fixtures.entry("com.other.app", cpuPercent = 5f),
            ),
        )
        val after = Fixtures.snapshot(
            timestamp = Fixtures.T0 + 2_000L,
            entries = listOf(
                Fixtures.entry("com.example.app:remote", packageName = null, cpuPercent = 70f),
                Fixtures.entry("com.other.app", cpuPercent = 5f),
            ),
        )

        val named = detect(before, after, targetPackage = "com.example.app")
            .ofType(EventType.CPU_SPIKE)
            .filter { it.processName != null }
        assertEquals(1, named.size)
        assertEquals("com.example.app:remote", named.first().processName)
    }

    @Test
    fun `a process with no earlier cpu reading raises no spike`() {
        val before = Fixtures.snapshot(
            entries = listOf(Fixtures.entry("app", cpuPercent = null)),
        )
        val after = Fixtures.snapshot(
            timestamp = Fixtures.T0 + 2_000L,
            entries = listOf(Fixtures.entry("app", cpuPercent = 95f)),
        )

        assertTrue(
            detect(before, after).ofType(EventType.CPU_SPIKE)
                .none { it.processName != null },
        )
    }

    // -------------------------------------------------------------------- memory

    @Test
    fun `growth beyond the memory threshold is reported`() {
        val before = Fixtures.snapshot(
            entries = listOf(Fixtures.entry("app", memoryBytes = 100L * 1024 * 1024)),
        )
        val after = Fixtures.snapshot(
            timestamp = Fixtures.T0 + 2_000L,
            entries = listOf(Fixtures.entry("app", memoryBytes = 700L * 1024 * 1024)),
        )

        val grew = detect(before, after).ofType(EventType.MEMORY_INCREASE)
        assertEquals(1, grew.size)
        assertEquals("app", grew.first().processName)
    }

    @Test
    fun `growth below the memory threshold is not reported`() {
        val before = Fixtures.snapshot(
            entries = listOf(Fixtures.entry("app", memoryBytes = 100L * 1024 * 1024)),
        )
        val after = Fixtures.snapshot(
            timestamp = Fixtures.T0 + 2_000L,
            entries = listOf(Fixtures.entry("app", memoryBytes = 200L * 1024 * 1024)),
        )

        assertTrue(detect(before, after).ofType(EventType.MEMORY_INCREASE).isEmpty())
    }

    @Test
    fun `releasing memory is recorded as information rather than a warning`() {
        val before = Fixtures.snapshot(
            entries = listOf(Fixtures.entry("app", memoryBytes = 900L * 1024 * 1024)),
        )
        val after = Fixtures.snapshot(
            timestamp = Fixtures.T0 + 2_000L,
            entries = listOf(Fixtures.entry("app", memoryBytes = 100L * 1024 * 1024)),
        )

        val released = detect(before, after).ofType(EventType.MEMORY_DECREASE)
        assertEquals(1, released.size)
        assertEquals(EventSeverity.INFO, released.first().severity)
    }

    @Test
    fun `an unreadable memory figure raises no memory event`() {
        val before = Fixtures.snapshot(
            entries = listOf(Fixtures.entry("app", memoryBytes = null)),
        )
        val after = Fixtures.snapshot(
            timestamp = Fixtures.T0 + 2_000L,
            entries = listOf(Fixtures.entry("app", memoryBytes = 900L * 1024 * 1024)),
        )

        val events = detect(before, after)
        assertTrue(events.ofType(EventType.MEMORY_INCREASE).isEmpty())
        assertTrue(events.ofType(EventType.MEMORY_DECREASE).isEmpty())
    }

    @Test
    fun `a fall in system available memory is reported as pressure`() {
        val before = Fixtures.snapshot(memoryAvailableBytes = 2_000L * 1024 * 1024)
        val after = Fixtures.snapshot(
            timestamp = Fixtures.T0 + 2_000L,
            memoryAvailableBytes = 900L * 1024 * 1024,
        )

        val pressure = detect(before, after).ofType(EventType.MEMORY_PRESSURE)
        assertEquals(1, pressure.size)
        assertEquals(EventSeverity.WARNING, pressure.first().severity)
    }

    @Test
    fun `available memory rising does not count as pressure`() {
        val before = Fixtures.snapshot(memoryAvailableBytes = 900L * 1024 * 1024)
        val after = Fixtures.snapshot(
            timestamp = Fixtures.T0 + 2_000L,
            memoryAvailableBytes = 2_000L * 1024 * 1024,
        )

        assertTrue(detect(before, after).ofType(EventType.MEMORY_PRESSURE).isEmpty())
    }

    // ----------------------------------------------------------------- lifecycle

    @Test
    fun `a newly appeared process is recorded as started`() {
        val before = Fixtures.snapshot(
            entries = listOf(Fixtures.entry("a"), Fixtures.entry("b")),
        )
        val after = Fixtures.snapshot(
            timestamp = Fixtures.T0 + 2_000L,
            entries = listOf(Fixtures.entry("a"), Fixtures.entry("b"), Fixtures.entry("c")),
        )

        val started = detect(before, after).ofType(EventType.PROCESS_STARTED)
        assertEquals(1, started.size)
        assertEquals("c", started.first().processName)
    }

    @Test
    fun `a vanished process is recorded as stopped`() {
        val before = Fixtures.snapshot(
            entries = listOf(Fixtures.entry("a"), Fixtures.entry("b"), Fixtures.entry("c")),
        )
        val after = Fixtures.snapshot(
            timestamp = Fixtures.T0 + 2_000L,
            entries = listOf(Fixtures.entry("a"), Fixtures.entry("b")),
        )

        val stopped = detect(before, after).ofType(EventType.PROCESS_STOPPED)
        assertEquals(1, stopped.size)
        assertEquals("c", stopped.first().processName)
    }

    @Test
    fun `a changed pid under the same name is a restart`() {
        val before = Fixtures.snapshot(
            entries = listOf(Fixtures.entry("a", pid = 100), Fixtures.entry("b", pid = 200)),
        )
        val after = Fixtures.snapshot(
            timestamp = Fixtures.T0 + 2_000L,
            entries = listOf(Fixtures.entry("a", pid = 100), Fixtures.entry("b", pid = 999)),
        )

        val restarted = detect(before, after).ofType(EventType.PROCESS_RESTARTED)
        assertEquals(1, restarted.size)
        assertEquals("b", restarted.first().processName)
        assertContains(restarted.first().detail, "200")
        assertContains(restarted.first().detail, "999")
    }

    @Test
    fun `a restart is not claimed when a pid was unreadable`() {
        // Without both PIDs there is no evidence of replacement, only of a name that
        // is still present. Section 42: no PID may be inferred.
        val before = Fixtures.snapshot(
            entries = listOf(Fixtures.entry("a", pid = null), Fixtures.entry("b", pid = 200)),
        )
        val after = Fixtures.snapshot(
            timestamp = Fixtures.T0 + 2_000L,
            entries = listOf(Fixtures.entry("a", pid = 555), Fixtures.entry("b", pid = 200)),
        )

        assertTrue(detect(before, after).ofType(EventType.PROCESS_RESTARTED).isEmpty())
    }

    @Test
    fun `lifecycle events are suppressed when only our own process was visible`() {
        // On API 28+ a normal app sees one row: itself. A list that "shrank" because
        // visibility was lost is not a process stopping, and saying so would invent a
        // relationship Android never exposed.
        val before = Fixtures.snapshot(entries = listOf(Fixtures.entry("self")))
        val after = Fixtures.snapshot(
            timestamp = Fixtures.T0 + 2_000L,
            entries = listOf(Fixtures.entry("other")),
        )

        val events = detect(before, after)
        assertTrue(events.ofType(EventType.PROCESS_STARTED).isEmpty())
        assertTrue(events.ofType(EventType.PROCESS_STOPPED).isEmpty())
    }

    // ------------------------------------------------------------------- battery

    @Test
    fun `a battery level change is recorded with both figures`() {
        val before = Fixtures.snapshot(batteryLevel = 80)
        val after = Fixtures.snapshot(timestamp = Fixtures.T0 + 2_000L, batteryLevel = 79)

        val changed = detect(before, after).ofType(EventType.BATTERY_LEVEL_CHANGE)
        assertEquals(1, changed.size)
        assertEquals(79.0, changed.first().value!!, 0.001)
        assertEquals(80.0, changed.first().previousValue!!, 0.001)
    }

    @Test
    fun `an unchanged battery level raises nothing`() {
        val before = Fixtures.snapshot(batteryLevel = 80)
        val after = Fixtures.snapshot(timestamp = Fixtures.T0 + 2_000L, batteryLevel = 80)

        assertTrue(detect(before, after).ofType(EventType.BATTERY_LEVEL_CHANGE).isEmpty())
    }

    @Test
    fun `a charging change warns that figures either side are not comparable`() {
        val before = Fixtures.snapshot(isCharging = false)
        val after = Fixtures.snapshot(timestamp = Fixtures.T0 + 2_000L, isCharging = true)

        val changed = detect(before, after).ofType(EventType.BATTERY_CHARGING_CHANGE)
        assertEquals(1, changed.size)
        assertContains(changed.first().detail, "not comparable")
    }

    @Test
    fun `battery temperature crossing the threshold is reported once`() {
        val before = Fixtures.snapshot(batteryTemperatureDeciCelsius = 380)
        val after = Fixtures.snapshot(
            timestamp = Fixtures.T0 + 2_000L,
            batteryTemperatureDeciCelsius = 420,
        )

        val rose = detect(before, after).ofType(EventType.BATTERY_TEMPERATURE_RISE)
        assertEquals(1, rose.size)
        assertEquals(EventSeverity.WARNING, rose.first().severity)
    }

    @Test
    fun `a battery temperature already above the threshold does not re-report`() {
        val before = Fixtures.snapshot(batteryTemperatureDeciCelsius = 420)
        val after = Fixtures.snapshot(
            timestamp = Fixtures.T0 + 2_000L,
            batteryTemperatureDeciCelsius = 430,
        )

        assertTrue(detect(before, after).ofType(EventType.BATTERY_TEMPERATURE_RISE).isEmpty())
    }

    @Test
    fun `an unreadable battery temperature raises no temperature event`() {
        val before = Fixtures.snapshot(batteryTemperatureDeciCelsius = null)
        val after = Fixtures.snapshot(
            timestamp = Fixtures.T0 + 2_000L,
            batteryTemperatureDeciCelsius = 500,
        )

        assertTrue(detect(before, after).ofType(EventType.BATTERY_TEMPERATURE_RISE).isEmpty())
    }

    // ------------------------------------------------------------------- network

    @Test
    fun `substantial network traffic is reported as a device wide total`() {
        val before = Fixtures.snapshot(networkRxBytes = 0L, networkTxBytes = 0L)
        val after = Fixtures.snapshot(
            timestamp = Fixtures.T0 + 2_000L,
            networkRxBytes = 4L * 1024 * 1024,
            networkTxBytes = 1L * 1024 * 1024,
        )

        val traffic = detect(before, after).ofType(EventType.NETWORK_ACTIVITY)
        assertEquals(1, traffic.size)
        // Section 42: Android does not attribute device counters to a process, and the
        // event has to say so rather than let the reader assume otherwise.
        assertContains(traffic.first().detail, "does not attribute them to a specific process")
        assertNull(traffic.first().processName)
    }

    @Test
    fun `routine keep alive traffic is below the reporting threshold`() {
        val before = Fixtures.snapshot(networkRxBytes = 0L, networkTxBytes = 0L)
        val after = Fixtures.snapshot(
            timestamp = Fixtures.T0 + 2_000L,
            networkRxBytes = 2_048L,
            networkTxBytes = 512L,
        )

        assertTrue(detect(before, after).ofType(EventType.NETWORK_ACTIVITY).isEmpty())
    }

    @Test
    fun `a network counter that went backwards is discarded rather than reported as huge`() {
        // TrafficStats resets at boot. A negative delta read as unsigned would appear
        // as terabytes of traffic that never happened.
        val before = Fixtures.snapshot(networkRxBytes = 900_000_000L, networkTxBytes = 900_000_000L)
        val after = Fixtures.snapshot(
            timestamp = Fixtures.T0 + 2_000L,
            networkRxBytes = 1_000L,
            networkTxBytes = 1_000L,
        )

        assertTrue(detect(before, after).ofType(EventType.NETWORK_ACTIVITY).isEmpty())
    }

    @Test
    fun `unreadable network counters raise no network event`() {
        val before = Fixtures.snapshot(networkRxBytes = null, networkTxBytes = null)
        val after = Fixtures.snapshot(
            timestamp = Fixtures.T0 + 2_000L,
            networkRxBytes = 90L * 1024 * 1024,
            networkTxBytes = 90L * 1024 * 1024,
        )

        assertTrue(detect(before, after).ofType(EventType.NETWORK_ACTIVITY).isEmpty())
    }

    // -------------------------------------------------------------------- screen

    @Test
    fun `a screen state change is recorded`() {
        val before = Fixtures.snapshot(isScreenOn = true)
        val after = Fixtures.snapshot(timestamp = Fixtures.T0 + 2_000L, isScreenOn = false)

        val changed = detect(before, after).ofType(EventType.SCREEN_STATE_CHANGE)
        assertEquals(1, changed.size)
        assertEquals("Screen turned off", changed.first().title)
    }

    // ---------------------------------------------------------------- provenance

    @Test
    fun `every event carries evidence, a title and the sampling interval`() {
        val before = Fixtures.snapshot(cpuPercent = 5f, batteryLevel = 80, isScreenOn = true)
        val after = Fixtures.snapshot(
            timestamp = Fixtures.T0 + 2_000L,
            cpuPercent = 90f,
            batteryLevel = 79,
            isScreenOn = false,
        )

        val events = detect(before, after)
        assertTrue("expected several events", events.size >= 3)
        events.forEach { event ->
            assertTrue("empty title on ${event.type}", event.title.isNotBlank())
            assertTrue("empty detail on ${event.type}", event.detail.isNotBlank())
            assertTrue("empty evidence on ${event.type}", event.evidence.isNotBlank())
            // Every event says how far apart the two samples were, because a "spike"
            // over 10 s means something different from one over 1 s.
            assertContains(event.evidence, "2.0 s apart")
        }
    }

    @Test
    fun `every event is stamped with the recording and the later sample time`() {
        val before = Fixtures.snapshot(cpuPercent = 5f)
        val after = Fixtures.snapshot(timestamp = Fixtures.T0 + 2_000L, cpuPercent = 90f)

        detect(before, after).forEach {
            assertEquals(7L, it.investigationId)
            assertEquals(Fixtures.T0 + 2_000L, it.timestamp)
        }
    }

    @Test
    fun `no event claims to know a cause`() {
        // Section 17: correlation may be stated, causation may not. The detector only
        // ever compares two samples, so it is never in a position to say why.
        val before = Fixtures.snapshot(
            cpuPercent = 5f,
            batteryLevel = 80,
            memoryAvailableBytes = 2_000L * 1024 * 1024,
            entries = listOf(Fixtures.entry("a", cpuPercent = 5f), Fixtures.entry("b")),
        )
        val after = Fixtures.snapshot(
            timestamp = Fixtures.T0 + 2_000L,
            cpuPercent = 95f,
            batteryLevel = 78,
            memoryAvailableBytes = 400L * 1024 * 1024,
            entries = listOf(Fixtures.entry("a", cpuPercent = 95f), Fixtures.entry("b")),
        )

        val prose = detect(before, after).joinToString(" ") { "${it.title} ${it.detail}" }
        assertDoesNotContain(prose.lowercase(), "caused")
        assertDoesNotContain(prose.lowercase(), "because of")
        assertDoesNotContain(prose.lowercase(), "responsible for")
        assertDoesNotContain(prose.lowercase(), "to blame")
    }

    @Test
    fun `identical consecutive samples produce no events at all`() {
        val snapshot = Fixtures.snapshot(entries = listOf(Fixtures.entry("a"), Fixtures.entry("b")))
        val same = Fixtures.snapshot(
            timestamp = Fixtures.T0 + 2_000L,
            entries = listOf(Fixtures.entry("a"), Fixtures.entry("b")),
        )

        assertTrue(detect(snapshot, same).isEmpty())
    }
}
