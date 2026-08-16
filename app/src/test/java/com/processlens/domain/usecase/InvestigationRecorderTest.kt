package com.processlens.domain.usecase

import com.processlens.core.common.DataSource
import com.processlens.core.common.Observed
import com.processlens.domain.model.AppBatteryUsage
import com.processlens.domain.model.AppNetworkUsage
import com.processlens.domain.model.DeviceInfo
import com.processlens.domain.model.EventSeverity
import com.processlens.domain.model.EventType
import com.processlens.domain.model.Investigation
import com.processlens.domain.model.InvestigationEvent
import com.processlens.domain.model.InvestigationState
import com.processlens.domain.model.InvestigationSummary
import com.processlens.domain.model.ProcessInfo
import com.processlens.domain.model.ProcessSnapshot
import com.processlens.domain.model.SystemCapabilities
import com.processlens.domain.model.ThreadInfo
import com.processlens.domain.model.UserSettings
import com.processlens.domain.model.WakeLockInfo
import com.processlens.domain.repository.InvestigationRepository
import com.processlens.domain.repository.ProcessListResult
import com.processlens.domain.repository.ProcessRepository
import com.processlens.domain.repository.ProcessTreeNode
import com.processlens.domain.repository.SeriesPoint
import com.processlens.domain.repository.SettingsRepository
import com.processlens.domain.repository.SystemRepository
import com.processlens.domain.repository.SystemState
import com.processlens.testing.Fixtures
import com.processlens.testing.assertContains
import com.processlens.testing.assertOrder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The recording engine (Sections 14, 15, 42, 43, 56).
 *
 * The recorder is the one place where an unfaithful implementation would be hardest to
 * spot from the UI: a recording is only inspected after the fact, so a sample that was
 * silently dropped, an event derived from a single reading, or a gap written as a zero
 * would all look like evidence later. These tests pin the four properties that make a
 * recording trustworthy:
 *
 *  - **A sample is written before anything is derived from it.** Measurements cannot be
 *    recomputed; events can.
 *  - **A failed read is recorded, not swallowed.** And it does not end the recording.
 *  - **Limitations are announced once.** A hundred identical "CPU unreadable" entries
 *    would bury the findings; none at all would make an absence look like idleness.
 *  - **Nothing is invented.** An unreadable metric is stored as null, and a recording
 *    stopped by the platform is marked INTERRUPTED rather than COMPLETED.
 *
 * All five collaborators are interfaces, and the sampling scope is injected, so the whole
 * engine runs on a plain JDK with a [TestScope] driving virtual time — no device, no
 * Android framework, and no real two-second waits.
 */
class InvestigationRecorderTest {

    private val settings = FakeSettingsRepository()
    private val system = FakeSystemRepository()
    private val processes = FakeProcessRepository()
    private val store = FakeInvestigationRepository()

    /** Three readable processes, so nothing here trips a visibility limitation. */
    private val threeProcesses = listOf(
        Fixtures.process(
            name = "com.example",
            pid = Fixtures.value(100),
            cpu = Fixtures.value(4f),
            memory = Fixtures.value(100_000_000L),
        ),
        Fixtures.process(
            name = "com.example:remote",
            pid = Fixtures.value(101),
            cpu = Fixtures.value(2f),
            memory = Fixtures.value(50_000_000L),
        ),
        Fixtures.process(
            name = "com.other",
            pid = Fixtures.value(102),
            cpu = Fixtures.value(1f),
            memory = Fixtures.value(20_000_000L),
        ),
    )

    private fun TestScope.newRecorder() = InvestigationRecorder(
        systemRepository = system,
        processRepository = processes,
        investigations = store,
        settings = settings,
        detector = SpikeDetector(),
        scope = this,
    )

    private fun eventsOfType(type: EventType) = store.events.filter { it.type == type }

    private fun limitations() = eventsOfType(EventType.OBSERVATION_LIMITED).map { it.detail }

    // --------------------------------------------------------------------- prepare

    @Test
    fun `prepare creates the row as recording with no end timestamp`() = runTest {
        processes.result = Fixtures.processListResult(threeProcesses)
        val recorder = newRecorder()

        val id = recorder.prepare("Battery drain", null, null)

        assertEquals(7L, id)
        val created = store.created.single()
        assertEquals("Battery drain", created.name)
        assertEquals(InvestigationState.RECORDING, created.state)
        // Left open on purpose: only `finish` closes a recording, so a process death
        // leaves a row that reconciliation can mark INTERRUPTED rather than one that
        // claims to have ended cleanly.
        assertNull(created.endedAt)
        assertEquals(0, created.snapshotCount)
        assertEquals(0, created.eventCount)
    }

    @Test
    fun `prepare records exactly one started event`() = runTest {
        val recorder = newRecorder()

        val id = recorder.prepare("Test", null, null)

        val event = store.events.single()
        assertEquals(EventType.INVESTIGATION_STARTED, event.type)
        assertEquals(EventSeverity.INFO, event.severity)
        assertEquals(id, event.investigationId)
        assertTrue("every event carries its evidence", event.evidence.isNotBlank())
    }

    @Test
    fun `the started event states the observed platform rather than a build constant`() = runTest {
        system.device = Fixtures.deviceInfo(androidRelease = "9", apiLevel = 28)
        val recorder = newRecorder()

        recorder.prepare("Test", null, null)

        // The release comes from the device that was read, not from a compile-time
        // assumption (Section 47) — an investigation recorded on Android 9 has to say so.
        assertContains(store.events.single().detail, "Android 9 (API ")
    }

    @Test
    fun `prepare records the sampling interval it will actually use`() = runTest {
        settings.current = UserSettings(investigationSampleIntervalMillis = 5_000L)
        val recorder = newRecorder()

        recorder.prepare("Test", null, null)

        assertEquals(5_000L, store.created.single().sampleIntervalMillis)
        assertContains(store.events.single().detail, "Sampling every 5 s")
    }

    @Test
    fun `an unscoped recording says it covers every observable process`() = runTest {
        val recorder = newRecorder()

        recorder.prepare("Test", null, null)

        // "Observable", not "all": on a restricted device the list is not the process
        // table, and the recording should not imply otherwise (Section 42).
        assertContains(store.events.single().detail, "across all observable processes")
        assertNull(store.created.single().targetPackage)
    }

    @Test
    fun `a scoped recording records which package it was scoped to`() = runTest {
        val recorder = newRecorder()

        recorder.prepare("Test", "com.example", null)

        assertEquals("com.example", store.created.single().targetPackage)
        val event = store.events.single()
        assertContains(event.detail, "scoped to com.example")
        assertEquals("com.example", event.packageName)
    }

    @Test
    fun `a blank name becomes a generated one rather than an empty title`() = runTest {
        val recorder = newRecorder()

        recorder.prepare("   ", null, null)

        val name = store.created.single().name
        assertTrue("expected a generated name, got <$name>", name.startsWith("Investigation "))
    }

    @Test
    fun `prepare records the access level and device the recording was made under`() = runTest {
        system.state = Fixtures.systemState(accessLevelName = "Shizuku")
        system.device = Fixtures.deviceInfo(manufacturer = "TestCo", model = "Model X")
        val recorder = newRecorder()

        recorder.prepare("Test", null, null)

        // Section 24: a recording taken under Shizuku and one taken under normal access
        // are not the same evidence, so the row remembers which it was.
        val created = store.created.single()
        assertEquals("Shizuku", created.accessLevelName)
        assertEquals("TestCo Model X", created.deviceLabel)
        assertEquals("one read for the access level, not one per field", 1, system.readCount)
    }

    @Test
    fun `prepare does not start sampling`() = runTest {
        val recorder = newRecorder()

        recorder.prepare("Test", null, null)
        testScheduler.advanceUntilIdle()

        // The foreground service starts the loop, so sampling never runs without the
        // notification Android requires to justify it.
        assertFalse(recorder.state.value.isRecording)
        assertEquals(7L, recorder.state.value.investigationId)
        assertTrue(store.snapshots.isEmpty())
    }

    @Test
    fun `a planned duration becomes a real progress target`() = runTest {
        val recorder = newRecorder()

        recorder.prepare("Test", null, 5)

        assertEquals(300_000L, recorder.state.value.plannedDurationMillis)
        assertNotNull(recorder.state.value.progressFraction)
    }

    @Test
    fun `an open ended recording reports no progress fraction`() = runTest {
        val recorder = newRecorder()

        recorder.prepare("Test", null, null)

        // No end time means no honest percentage, so the UI gets null and shows elapsed
        // time instead of a progress bar that would have to be invented.
        assertNull(recorder.state.value.plannedDurationMillis)
        assertNull(recorder.state.value.progressFraction)
    }

    // ------------------------------------------------------------------------ loop

    @Test
    fun `the sample is written before any event is derived from it`() = runTest {
        processes.result = Fixtures.processListResult(threeProcesses)
        val recorder = newRecorder()
        val id = recorder.prepare("Test", null, null)

        recorder.start(id)
        testScheduler.runCurrent()
        recorder.stop()

        // The exact interleaving: the measurement reaches storage before anything is
        // inferred from it, and the first sample derives nothing at all because a spike
        // is a change and there is nothing yet to have changed from.
        assertOrder(
            listOf(
                "create",
                "events:INVESTIGATION_STARTED",
                "snapshot",
                "events:",
                "events:OBSERVATION_LIMITED",
                "events:INVESTIGATION_STOPPED",
                "finish",
            ),
            store.callOrder,
        )
    }

    @Test
    fun `the snapshot carries the figures that were actually read`() = runTest {
        processes.result = Fixtures.processListResult(threeProcesses)
        val recorder = newRecorder()
        val id = recorder.prepare("Test", null, null)

        recorder.start(id)
        testScheduler.runCurrent()
        recorder.stop()

        val snapshot = store.snapshots.single()
        assertEquals(18f, snapshot.cpuPercent!!, 0.001f)
        // 8 GB total minus 3 GB available, computed rather than reported.
        assertEquals(5_000_000_000L, snapshot.memoryUsedBytes)
        assertEquals(3_000_000_000L, snapshot.memoryAvailableBytes)
        assertEquals(80, snapshot.batteryLevel)
        assertEquals(305L, snapshot.batteryTemperatureDeciCelsius!!.toLong())
        assertEquals(1_024L, snapshot.networkRxBytes!!)
        assertEquals(512L, snapshot.networkTxBytes!!)
        assertEquals(3, snapshot.processCount)
        assertEquals(1.5f, snapshot.ownCpuPercent!!, 0.001f)
        assertEquals(40_000_000L, snapshot.ownMemoryBytes!!)
        assertFalse(snapshot.isCharging)
        assertTrue(snapshot.isScreenOn)
    }

    @Test
    fun `an unreadable metric is stored as null and never as zero`() = runTest {
        // The whole point of Section 42: a gap in the chart must be a gap. A zero here
        // would render as an idle device, which is a claim the recording cannot support.
        system.state = Fixtures.systemState(
            cpuPercent = Observed.platform("/proc/stat is not readable"),
            batteryTemperature = Observed.platform("not reported by this device"),
            rxBytes = Observed.platform("counters unavailable"),
        )
        processes.result = Fixtures.processListResult(threeProcesses)
        val recorder = newRecorder()
        val id = recorder.prepare("Test", null, null)

        recorder.start(id)
        testScheduler.runCurrent()
        recorder.stop()

        val snapshot = store.snapshots.single()
        assertNull(snapshot.cpuPercent)
        assertNull(snapshot.batteryTemperatureDeciCelsius)
        assertNull(snapshot.networkRxBytes)
    }

    @Test
    fun `per process rows carry the observed values and nulls where unreadable`() = runTest {
        processes.result = Fixtures.processListResult(
            listOf(
                Fixtures.process(
                    name = "com.readable",
                    pid = Fixtures.value(100),
                    cpu = Fixtures.value(7.5f),
                    memory = Fixtures.value(123_456_789L),
                ),
                Fixtures.process(name = "com.hidden"),
            ),
        )
        val recorder = newRecorder()
        val id = recorder.prepare("Test", null, null)

        recorder.start(id)
        testScheduler.runCurrent()
        recorder.stop()

        val entries = store.snapshots.single().entries.associateBy { it.processName }
        assertEquals(100L, entries.getValue("com.readable").pid!!.toLong())
        assertEquals(7.5f, entries.getValue("com.readable").cpuPercent!!, 0.001f)
        assertEquals(123_456_789L, entries.getValue("com.readable").memoryBytes!!)
        // The restricted process is still recorded — it exists, and dropping it would
        // understate what was running.
        assertNull(entries.getValue("com.hidden").pid)
        assertNull(entries.getValue("com.hidden").cpuPercent)
        assertNull(entries.getValue("com.hidden").memoryBytes)
    }

    @Test
    fun `provenance is stored beside every sample`() = runTest {
        processes.result = Fixtures.processListResult(threeProcesses)
        val recorder = newRecorder()
        val id = recorder.prepare("Test", null, null)

        recorder.start(id)
        testScheduler.runCurrent()
        recorder.stop()

        // Where the CPU figure came from, so an export can say how it was measured.
        assertContains(store.provenance.single(), DataSource.PROC_FS.label)
        assertEquals(Fixtures.processListResult().discoveryMethods.single(), store.discoveryMethods.single())
    }

    @Test
    fun `an unreadable cpu figure records why rather than a source`() = runTest {
        system.state = Fixtures.systemState(cpuPercent = Observed.platform("/proc/stat denied"))
        processes.result = Fixtures.processListResult(threeProcesses)
        val recorder = newRecorder()
        val id = recorder.prepare("Test", null, null)

        recorder.start(id)
        testScheduler.runCurrent()
        recorder.stop()

        assertContains(store.provenance.single(), "unavailable")
    }

    @Test
    fun `a sample with no discovery method says so instead of leaving it blank`() = runTest {
        processes.result = Fixtures.processListResult(threeProcesses, discoveryMethods = emptyList())
        val recorder = newRecorder()
        val id = recorder.prepare("Test", null, null)

        recorder.start(id)
        testScheduler.runCurrent()
        recorder.stop()

        assertEquals("none", store.discoveryMethods.single())
    }

    @Test
    fun `the second sample derives events from the pair`() = runTest {
        processes.result = Fixtures.processListResult(threeProcesses)
        val recorder = newRecorder()
        val id = recorder.prepare("Test", null, null)

        recorder.start(id)
        testScheduler.runCurrent()
        // A real change between two real readings — the only thing that can justify an event.
        system.state = Fixtures.systemState(cpuPercent = Fixtures.value(90f))
        testScheduler.advanceTimeBy(2_000)
        testScheduler.runCurrent()
        recorder.stop()

        assertEquals(2, store.snapshots.size)
        val spike = eventsOfType(EventType.CPU_SPIKE).single()
        assertEquals(EventSeverity.CRITICAL, spike.severity)
        assertEquals(90.0, spike.value!!, 0.001)
        assertEquals(18.0, spike.previousValue!!, 0.001)
    }

    @Test
    fun `sampling progress reflects what was captured`() = runTest {
        processes.result = Fixtures.processListResult(threeProcesses)
        val recorder = newRecorder()
        val id = recorder.prepare("Test", null, null)

        recorder.start(id)
        testScheduler.runCurrent()
        system.state = Fixtures.systemState(cpuPercent = Fixtures.value(90f))
        testScheduler.advanceTimeBy(2_000)
        testScheduler.runCurrent()

        val state = recorder.state.value
        assertTrue(state.isRecording)
        assertEquals(2, state.sampleCount)
        assertEquals(1, state.eventCount)
        assertEquals(2L, state.lastSnapshotRowId!!)
        assertNotNull(state.lastSampleAt)

        recorder.stop()
    }

    @Test
    fun `the sampling interval comes from settings and is honoured between samples`() = runTest {
        settings.current = UserSettings(investigationSampleIntervalMillis = 10_000L)
        processes.result = Fixtures.processListResult(threeProcesses)
        val recorder = newRecorder()
        val id = recorder.prepare("Test", null, null)

        recorder.start(id)
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(9_000)
        testScheduler.runCurrent()
        assertEquals("no second sample before the interval elapsed", 1, store.snapshots.size)

        testScheduler.advanceTimeBy(1_000)
        testScheduler.runCurrent()
        assertEquals(2, store.snapshots.size)

        recorder.stop()
    }

    @Test
    fun `an absurd sampling interval is clamped rather than obeyed`() = runTest {
        // Section 43: ProcessLens must not become the load it measures. A 1 ms interval
        // from a corrupted setting is clamped to one second, not honoured.
        settings.current = UserSettings(investigationSampleIntervalMillis = 1L)
        processes.result = Fixtures.processListResult(threeProcesses)
        val recorder = newRecorder()
        val id = recorder.prepare("Test", null, null)

        recorder.start(id)
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(500)
        testScheduler.runCurrent()
        assertEquals(1, store.snapshots.size)

        testScheduler.advanceTimeBy(500)
        testScheduler.runCurrent()
        assertEquals(2, store.snapshots.size)

        recorder.stop()
    }

    @Test
    fun `start is ignored while a recording is already sampling`() = runTest {
        processes.result = Fixtures.processListResult(threeProcesses)
        val recorder = newRecorder()
        val id = recorder.prepare("Test", null, null)

        recorder.start(id)
        recorder.start(id)
        testScheduler.runCurrent()
        recorder.stop()

        // Two loops would double the app's own cost and write two samples per instant.
        assertEquals(1, store.snapshots.size)
    }

    @Test
    fun `a target package narrows the rows but keeps the system wide figures`() = runTest {
        processes.result = Fixtures.processListResult(threeProcesses)
        val recorder = newRecorder()
        val id = recorder.prepare("Test", "com.example", null)

        recorder.start(id)
        testScheduler.runCurrent()
        recorder.stop()

        val snapshot = store.snapshots.single()
        assertOrder(
            listOf("com.example", "com.example:remote"),
            snapshot.entries.map { it.processName },
        )
        // Section 14: a target app's spike is only interpretable against what the rest of
        // the device was doing, so the whole-device figures are recorded regardless.
        assertEquals(3, snapshot.processCount)
        assertEquals(18f, snapshot.cpuPercent!!, 0.001f)
    }

    // ----------------------------------------------------------------- limitations

    @Test
    fun `every recording notes that wake locks need elevated access`() = runTest {
        processes.result = Fixtures.processListResult(threeProcesses)
        val recorder = newRecorder()
        val id = recorder.prepare("Test", null, null)

        recorder.start(id)
        testScheduler.runCurrent()
        recorder.stop()

        // Without this, "no wake locks" in the summary would be indistinguishable from
        // "wake locks were never observable" (Sections 17 and 42).
        assertEquals(
            listOf("Wake lock observation requires Shizuku or root access."),
            limitations(),
        )
    }

    @Test
    fun `a limitation is announced once and not on every sample`() = runTest {
        system.state = Fixtures.systemState(cpuPercent = Observed.platform("/proc/stat denied"))
        processes.result = Fixtures.processListResult(threeProcesses)
        val recorder = newRecorder()
        val id = recorder.prepare("Test", null, null)

        recorder.start(id)
        testScheduler.runCurrent()
        repeat(4) {
            testScheduler.advanceTimeBy(2_000)
            testScheduler.runCurrent()
        }
        recorder.stop()

        assertEquals("five samples were taken", 5, store.snapshots.size)
        // One entry, not five: a timeline with 150 identical notices would bury the
        // findings it exists to surface.
        assertEquals(1, limitations().count { it.contains("System-wide CPU usage") })
        assertEquals(1, limitations().count { it.contains("Wake lock observation") })
    }

    @Test
    fun `losing process visibility is announced`() = runTest {
        processes.result = Fixtures.processListResult(
            listOf(Fixtures.process(name = "com.processlens", pid = Fixtures.value(100), cpu = Fixtures.value(1f))),
        )
        val recorder = newRecorder()
        val id = recorder.prepare("Test", null, null)

        recorder.start(id)
        testScheduler.runCurrent()
        recorder.stop()

        assertEquals(
            1,
            limitations().count { it.contains("Android is limiting process visibility") },
        )
    }

    @Test
    fun `unreadable per process cpu is announced`() = runTest {
        processes.result = Fixtures.processListResult(
            listOf(
                Fixtures.process(name = "com.a", pid = Fixtures.value(100)),
                Fixtures.process(name = "com.b", pid = Fixtures.value(101)),
            ),
        )
        val recorder = newRecorder()
        val id = recorder.prepare("Test", null, null)

        recorder.start(id)
        testScheduler.runCurrent()
        recorder.stop()

        // Rows exist but none carries a CPU figure, so activity cannot be attributed —
        // which the recording says rather than leaving the reader to guess.
        assertEquals(
            1,
            limitations().count { it.contains("Per-process CPU usage is not readable") },
        )
    }

    @Test
    fun `a failed sample is recorded and the recording continues`() = runTest {
        processes.result = Fixtures.processListResult(threeProcesses)
        val recorder = newRecorder()
        val id = recorder.prepare("Test", null, null)
        system.failNextRead = true

        recorder.start(id)
        testScheduler.runCurrent()
        assertTrue("a failed sample writes no snapshot", store.snapshots.isEmpty())

        testScheduler.advanceTimeBy(2_000)
        testScheduler.runCurrent()
        // Read progress before stopping: `stop` clears the live state, so the count is
        // only observable while the recording is still running.
        assertEquals("progress counts samples that succeeded", 1, recorder.state.value.sampleCount)
        recorder.stop()

        // The failure is on the timeline, and the next sample worked: one bad read must
        // not end a recording the user is relying on.
        val failure = limitations().single { it.contains("A sample could not be taken") }
        assertContains(failure, "/proc read denied")
        assertEquals(1, store.snapshots.size)
    }

    @Test
    fun `repeated identical failures are not repeated on the timeline`() = runTest {
        val recorder = newRecorder()
        val id = recorder.prepare("Test", null, null)
        system.failAllReads = true

        recorder.start(id)
        testScheduler.runCurrent()
        repeat(3) {
            testScheduler.advanceTimeBy(2_000)
            testScheduler.runCurrent()
        }
        recorder.stop()

        assertTrue(store.snapshots.isEmpty())
        assertEquals(1, limitations().size)
        // Started, the single failure notice, and stopped — nothing else was written.
        assertEquals(3, store.events.size)
    }

    // ------------------------------------------------------------------------ stop

    @Test
    fun `stop records a completion event and closes the row`() = runTest {
        val recorder = newRecorder()
        val id = recorder.prepare("Test", null, null)

        recorder.stop()

        val stopped = eventsOfType(EventType.INVESTIGATION_STOPPED).single()
        assertEquals("Investigation completed", stopped.title)
        assertEquals(listOf(id to InvestigationState.COMPLETED), store.finished)
    }

    @Test
    fun `a recording cut short is marked interrupted rather than completed`() = runTest {
        val recorder = newRecorder()
        val id = recorder.prepare("Test", null, null)

        recorder.stop(InvestigationState.INTERRUPTED)

        // The platform killing the service is not a completed investigation, and the row
        // must not claim otherwise — a partial recording read as complete is a wrong answer.
        val stopped = eventsOfType(EventType.INVESTIGATION_STOPPED).single()
        assertEquals("Investigation interrupted", stopped.title)
        assertEquals(listOf(id to InvestigationState.INTERRUPTED), store.finished)
    }

    @Test
    fun `stop reports how many samples were captured`() = runTest {
        processes.result = Fixtures.processListResult(threeProcesses)
        val recorder = newRecorder()
        val id = recorder.prepare("Test", null, null)

        recorder.start(id)
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(2_000)
        testScheduler.runCurrent()
        recorder.stop()

        val stopped = eventsOfType(EventType.INVESTIGATION_STOPPED).single()
        assertContains(stopped.detail, "2 samples captured")
        assertEquals(2.0, stopped.value!!, 0.001)
    }

    @Test
    fun `stop cancels the sampling loop`() = runTest {
        processes.result = Fixtures.processListResult(threeProcesses)
        val recorder = newRecorder()
        val id = recorder.prepare("Test", null, null)

        recorder.start(id)
        testScheduler.runCurrent()
        recorder.stop()
        testScheduler.advanceTimeBy(60_000)
        testScheduler.runCurrent()

        // A loop that outlived its recording would keep polling `/proc` forever — exactly
        // the behaviour Section 43 exists to prevent.
        assertEquals(1, store.snapshots.size)
    }

    @Test
    fun `stop clears the recording state`() = runTest {
        val recorder = newRecorder()
        val id = recorder.prepare("Test", null, null)

        recorder.start(id)
        testScheduler.runCurrent()
        recorder.stop()

        assertEquals(RecordingState(), recorder.state.value)
    }

    @Test
    fun `stopping a recorder that was never prepared does nothing`() = runTest {
        val recorder = newRecorder()

        recorder.stop()

        // No id means no row to close; writing an event against nothing would be a
        // dangling record.
        assertTrue(store.events.isEmpty())
        assertTrue(store.finished.isEmpty())
    }

    @Test
    fun `stopping twice does not write a second stopped event`() = runTest {
        val recorder = newRecorder()
        recorder.prepare("Test", null, null)

        recorder.stop()
        recorder.stop()

        assertEquals(1, eventsOfType(EventType.INVESTIGATION_STOPPED).size)
        assertEquals(1, store.finished.size)
    }
}

// ----------------------------------------------------------------------- the fakes
//
// Hand-written rather than generated: no mocking library resolves on this build host, and
// a fake that records its calls is what these tests actually need — the ordering of writes
// is the property under test. Methods the recorder does not touch fail loudly instead of
// returning a plausible empty value, so a future change that starts calling one of them
// cannot pass unnoticed.

private class FakeSettingsRepository(var current: UserSettings = UserSettings()) : SettingsRepository {
    override fun observe(): Flow<UserSettings> = error("the recorder reads settings once, it does not observe them")
    override suspend fun get(): UserSettings = current
    override suspend fun update(transform: (UserSettings) -> UserSettings) {
        current = transform(current)
    }

    override suspend fun markOnboardingComplete() = error("not used by the recorder")
}

private class FakeSystemRepository : SystemRepository {
    var state: SystemState = Fixtures.systemState()
    var device: DeviceInfo = Fixtures.deviceInfo()

    /** Fails the next read only, so a test can prove the loop survives one bad sample. */
    var failNextRead = false
    var failAllReads = false
    var readCount = 0

    override fun observeSystemState(): Flow<SystemState> =
        error("the recorder samples on its own schedule")

    override suspend fun readSystemState(): SystemState {
        readCount++
        if (failAllReads || failNextRead) {
            failNextRead = false
            throw IllegalStateException("/proc read denied")
        }
        return state
    }

    override fun observeCapabilities(): Flow<SystemCapabilities> = error("not used by the recorder")
    override suspend fun refreshCapabilities(): SystemCapabilities = error("not used by the recorder")
    override suspend fun getDeviceInfo(): DeviceInfo = device
    override suspend fun getWakeLocks(): Observed<List<WakeLockInfo>> = error("not used by the recorder")
    override suspend fun getBatteryUsage(): Observed<List<AppBatteryUsage>> = error("not used by the recorder")
    override suspend fun getPerAppNetworkUsage(sinceMillis: Long): Observed<List<AppNetworkUsage>> =
        error("not used by the recorder")

    override suspend fun invalidateAccess() = error("not used by the recorder")
}

private class FakeProcessRepository : ProcessRepository {
    var result: ProcessListResult = Fixtures.processListResult()

    override fun observeProcesses(): Flow<ProcessListResult> =
        error("the recorder samples on its own schedule")

    override suspend fun readProcesses(): ProcessListResult = result
    override suspend fun getProcess(id: String): ProcessInfo? = error("not used by the recorder")
    override suspend fun getThreads(pid: Int): Observed<List<ThreadInfo>> = error("not used by the recorder")
    override suspend fun getProcessTree(): Observed<List<ProcessTreeNode>> = error("not used by the recorder")
}

private class FakeInvestigationRepository : InvestigationRepository {
    val created = mutableListOf<Investigation>()
    val events = mutableListOf<InvestigationEvent>()
    val snapshots = mutableListOf<ProcessSnapshot>()
    val provenance = mutableListOf<String>()
    val discoveryMethods = mutableListOf<String>()
    val finished = mutableListOf<Pair<Long, InvestigationState>>()

    /** Every write in order, which is how the write-before-derive rule is checked. */
    val callOrder = mutableListOf<String>()

    override suspend fun create(investigation: Investigation): Long {
        created += investigation
        callOrder += "create"
        return 7L
    }

    override suspend fun appendSnapshot(
        snapshot: ProcessSnapshot,
        cpuProvenance: String,
        discoveryMethod: String,
    ): Long {
        snapshots += snapshot
        provenance += cpuProvenance
        discoveryMethods += discoveryMethod
        callOrder += "snapshot"
        return snapshots.size.toLong()
    }

    override suspend fun appendEvents(events: List<InvestigationEvent>) {
        this.events += events
        callOrder += "events:" + events.joinToString(",") { it.type.name }
    }

    override suspend fun finish(id: Long, state: InvestigationState) {
        finished += id to state
        callOrder += "finish"
    }

    override fun observeAll(): Flow<List<Investigation>> = error("read path, not used by the recorder")
    override fun observeActive(): Flow<Investigation?> = error("read path, not used by the recorder")
    override fun observeById(id: Long): Flow<Investigation?> = error("read path, not used by the recorder")
    override fun observeEvents(id: Long): Flow<List<InvestigationEvent>> =
        error("read path, not used by the recorder")

    override fun observeRecentEvents(limit: Int): Flow<List<InvestigationEvent>> =
        error("read path, not used by the recorder")

    override suspend fun get(id: Long): Investigation? = error("read path, not used by the recorder")
    override suspend fun getSnapshots(id: Long): List<ProcessSnapshot> =
        error("read path, not used by the recorder")

    override suspend fun getSeries(id: Long): List<SeriesPoint> = error("read path, not used by the recorder")
    override suspend fun getSnapshotAt(id: Long, timestamp: Long): ProcessSnapshot? =
        error("read path, not used by the recorder")

    override suspend fun buildSummary(id: Long): InvestigationSummary? =
        error("read path, not used by the recorder")

    override suspend fun delete(id: Long) = error("read path, not used by the recorder")
    override suspend fun rename(id: Long, name: String) = error("read path, not used by the recorder")
    override suspend fun reconcileStaleRecordings(): Int = error("read path, not used by the recorder")
}
