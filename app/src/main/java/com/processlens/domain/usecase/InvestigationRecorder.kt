package com.processlens.domain.usecase

import android.os.Build
import com.processlens.core.common.ApplicationScope
import com.processlens.core.common.Observed
import com.processlens.core.common.valueOrNull
import com.processlens.domain.model.EventSeverity
import com.processlens.domain.model.EventType
import com.processlens.domain.model.Investigation
import com.processlens.domain.model.InvestigationEvent
import com.processlens.domain.model.InvestigationState
import com.processlens.domain.model.ProcessSnapshot
import com.processlens.domain.model.SnapshotEntry
import com.processlens.domain.repository.InvestigationRepository
import com.processlens.domain.repository.ProcessRepository
import com.processlens.domain.repository.SettingsRepository
import com.processlens.domain.repository.SystemRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The recording engine (Sections 14, 24).
 *
 * Runs one sampling loop in the application scope, driven by
 * `InvestigationService` so the platform keeps the process alive while the user is
 * elsewhere. State is exposed as a [StateFlow] so any screen can show progress
 * without owning the loop.
 *
 * Two properties matter more than anything else here:
 *
 * 1. **A sample is written before events are derived from it.** If the process dies
 *    mid-recording, the snapshots already on disk are still real data, and the
 *    investigation is marked INTERRUPTED rather than deleted.
 * 2. **Capability losses are recorded as events.** If CPU becomes unreadable
 *    halfway through, that fact goes on the timeline — so a gap in the chart has a
 *    visible explanation instead of looking like a period of zero activity.
 */
@Singleton
class InvestigationRecorder @Inject constructor(
    private val systemRepository: SystemRepository,
    private val processRepository: ProcessRepository,
    private val investigations: InvestigationRepository,
    private val settings: SettingsRepository,
    private val detector: SpikeDetector,
    @ApplicationScope private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(RecordingState())
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    private val lock = Mutex()
    private var loop: Job? = null

    /**
     * Creates the investigation row and returns its id. Does not start sampling —
     * the service does that once it is in the foreground, so the loop never runs
     * without the notification that Android requires to justify it.
     */
    suspend fun prepare(
        name: String,
        targetPackage: String?,
        durationMinutes: Int?,
    ): Long = lock.withLock {
        val prefs = settings.get()
        val device = systemRepository.getDeviceInfo()
        val now = System.currentTimeMillis()

        val id = investigations.create(
            Investigation(
                name = name.trim().ifBlank { defaultName(now) },
                startedAt = now,
                endedAt = null,
                state = InvestigationState.RECORDING,
                targetPackage = targetPackage,
                sampleIntervalMillis = prefs.investigationSampleIntervalMillis,
                eventCount = 0,
                snapshotCount = 0,
                processesObserved = 0,
                accessLevelName = systemRepository.readSystemState().accessLevelName,
                apiLevel = Build.VERSION.SDK_INT,
                deviceLabel = "${device.manufacturer} ${device.model}",
                notes = null,
            ),
        )

        investigations.appendEvents(
            listOf(
                InvestigationEvent(
                    investigationId = id,
                    timestamp = now,
                    type = EventType.INVESTIGATION_STARTED,
                    severity = EventSeverity.INFO,
                    title = "Investigation started",
                    detail = "Sampling every ${prefs.investigationSampleIntervalMillis / 1000} s" +
                        (targetPackage?.let { ", scoped to $it" } ?: ", across all observable processes") +
                        ". Android ${device.androidRelease} (API ${Build.VERSION.SDK_INT}).",
                    packageName = targetPackage,
                    processName = null,
                    value = null,
                    previousValue = null,
                    evidence = "Recorded at start of session",
                ),
            ),
        )

        _state.value = RecordingState(
            investigationId = id,
            isRecording = false,
            startedAt = now,
            targetPackage = targetPackage,
            plannedDurationMillis = durationMinutes?.let { it * 60_000L },
        )
        id
    }

    /** Starts the sampling loop for an already-prepared investigation. */
    fun start(investigationId: Long) {
        if (loop?.isActive == true) return
        loop = scope.launch { runLoop(investigationId) }
    }

    /**
     * Stops sampling and closes the row.
     *
     * [InvestigationState.COMPLETED] only when the loop ended on purpose; a caller
     * that stops because the platform is shutting the service down passes
     * [InvestigationState.INTERRUPTED] so the record reflects what happened.
     */
    suspend fun stop(finalState: InvestigationState = InvestigationState.COMPLETED) {
        val id = _state.value.investigationId ?: return
        loop?.cancel()
        loop = null

        investigations.appendEvents(
            listOf(
                InvestigationEvent(
                    investigationId = id,
                    timestamp = System.currentTimeMillis(),
                    type = EventType.INVESTIGATION_STOPPED,
                    severity = EventSeverity.INFO,
                    title = if (finalState == InvestigationState.COMPLETED) {
                        "Investigation completed"
                    } else {
                        "Investigation interrupted"
                    },
                    detail = "${_state.value.sampleCount} samples captured over " +
                        "${(System.currentTimeMillis() - (_state.value.startedAt ?: 0L)) / 1000} s.",
                    packageName = null,
                    processName = null,
                    value = _state.value.sampleCount.toDouble(),
                    previousValue = null,
                    evidence = "Recorded at end of session",
                ),
            ),
        )
        investigations.finish(id, finalState)
        _state.value = RecordingState()
    }

    /**
     * The sampling loop.
     *
     * Interval is honoured as *time between the end of one sample and the start of
     * the next*, not a fixed wall-clock schedule. A sample that takes 400 ms on a
     * slow device therefore does not fall behind and start queueing — it just
     * samples slightly less often, which is the correct degradation for a tool that
     * must not become the load it measures (Section 43).
     */
    private suspend fun runLoop(investigationId: Long) {
        val prefs = settings.get()
        val interval = prefs.investigationSampleIntervalMillis.coerceIn(1_000L, 60_000L)
        val target = _state.value.targetPackage
        val deadline = _state.value.plannedDurationMillis?.let { System.currentTimeMillis() + it }

        var previous: ProcessSnapshot? = null
        // Capability losses are announced once, not on every sample — a timeline with
        // 150 identical "CPU unreadable" entries would bury the real findings.
        val announcedLimitations = HashSet<String>()

        _state.value = _state.value.copy(isRecording = true)

        while (scope.isActive && loop?.isActive != false) {
            val sampledAt = System.currentTimeMillis()

            val sampled = try {
                sample(investigationId, sampledAt, target)
            } catch (t: Throwable) {
                // A failed sample must not end the recording: the next one may work.
                recordLimitation(
                    investigationId, sampledAt, announcedLimitations,
                    "A sample could not be taken: ${t.message ?: t::class.java.simpleName}",
                )
                null
            }

            if (sampled != null) {
                val snapshot = sampled.snapshot
                // Write first, derive second. If the process is killed between these
                // two steps, the sample survives — the event can be recomputed, the
                // measurement cannot.
                val rowId = investigations.appendSnapshot(
                    snapshot,
                    cpuProvenance = sampled.cpuProvenance,
                    discoveryMethod = sampled.discoveryMethod,
                )

                val derived = detector.detect(
                    investigationId = investigationId,
                    previous = previous,
                    current = snapshot,
                    settings = prefs,
                    targetPackage = target,
                )
                investigations.appendEvents(derived)

                announceLimitations(investigationId, snapshot, announcedLimitations)

                previous = snapshot
                _state.value = _state.value.copy(
                    sampleCount = _state.value.sampleCount + 1,
                    eventCount = _state.value.eventCount + derived.size,
                    lastSampleAt = sampledAt,
                    lastSnapshotRowId = rowId,
                )
            }

            if (deadline != null && System.currentTimeMillis() >= deadline) {
                stop(InvestigationState.COMPLETED)
                return
            }
            delay(interval)
        }
    }

    /** Builds one snapshot from the current system and process state. */
    private suspend fun sample(
        investigationId: Long,
        timestamp: Long,
        targetPackage: String?,
    ): Sampled {
        val system = systemRepository.readSystemState()
        val processes = processRepository.readProcesses()

        // Scoping to a package narrows the per-process rows, but the system-wide
        // figures are still recorded: a target app's spike is only interpretable
        // against what the rest of the device was doing.
        val entries = processes.processes
            .filter { targetPackage == null || it.belongsToPackage(targetPackage) }
            .map { process ->
                SnapshotEntry(
                    processName = process.processName,
                    packageName = process.packageName,
                    pid = process.pid.valueOrNull,
                    cpuPercent = process.cpuPercent.valueOrNull,
                    memoryBytes = process.memoryBytes.valueOrNull,
                    importance = process.importance,
                )
            }

        val snapshot = ProcessSnapshot(
            investigationId = investigationId,
            timestamp = timestamp,
            cpuPercent = system.cpu.overallPercent.valueOrNull,
            memoryUsedBytes = system.memory.usedBytes,
            memoryAvailableBytes = system.memory.availableBytes,
            batteryLevel = system.battery.levelPercent,
            batteryTemperatureDeciCelsius = system.battery.temperatureDeciCelsius.valueOrNull,
            isCharging = system.battery.isCharging,
            isScreenOn = system.battery.isScreenOn,
            networkRxBytes = system.network.totalRxBytes.valueOrNull,
            networkTxBytes = system.network.totalTxBytes.valueOrNull,
            processCount = processes.processes.size,
            ownCpuPercent = system.ownUsage.cpuPercent.valueOrNull,
            ownMemoryBytes = system.ownUsage.memoryBytes.valueOrNull,
            entries = entries,
        )

        // Provenance travels alongside the snapshot rather than inside it: the
        // timeline cares whether a value exists, the export cares where it came
        // from, and the domain model should not carry a storage concern.
        return Sampled(
            snapshot = snapshot,
            cpuProvenance = describeProvenance(system.cpu.overallPercent),
            discoveryMethod = processes.discoveryMethods.joinToString("+").ifBlank { "none" },
        )
    }

    private fun describeProvenance(observed: Observed<*>): String = when (observed) {
        is Observed.Value -> "${observed.source.label} (${observed.precision.label})"
        is Observed.Restricted -> "unavailable: ${observed.reason.name}"
        is Observed.Failed -> "read failed: ${observed.detail}"
    }

    /**
     * Records, once per recording, each thing this session could not observe.
     *
     * This is what stops an absence being read as a finding. A timeline that simply
     * has no CPU line looks like idleness; a timeline with one "CPU usage is not
     * readable on this device" marker at the point it was discovered does not.
     */
    private suspend fun announceLimitations(
        investigationId: Long,
        snapshot: ProcessSnapshot,
        announced: MutableSet<String>,
    ) {
        val limitations = buildList {
            if (snapshot.cpuPercent == null) {
                add("System-wide CPU usage is not readable on this device, so CPU figures are absent from this recording.")
            }
            if (snapshot.networkRxBytes == null) {
                add("Network byte counters are not readable, so network activity is not being observed.")
            }
            if (snapshot.batteryTemperatureDeciCelsius == null) {
                add("This device does not report battery temperature.")
            }
            if (snapshot.processCount <= 1) {
                add("Android is limiting process visibility to this application's own process, so other applications are not being observed.")
            }
            if (snapshot.entries.none { it.cpuPercent != null } && snapshot.entries.isNotEmpty()) {
                add("Per-process CPU usage is not readable, so activity cannot be attributed to individual processes.")
            }
            // Wake locks are never observable at normal access; recorded once so the
            // summary can distinguish "none held" from "could not have seen them".
            add("Wake lock observation requires Shizuku or root access.")
        }

        for (limitation in limitations) {
            if (announced.add(limitation)) {
                recordLimitation(investigationId, snapshot.timestamp, announced, limitation, alreadyAdded = true)
            }
        }
    }

    private suspend fun recordLimitation(
        investigationId: Long,
        timestamp: Long,
        announced: MutableSet<String>,
        detail: String,
        alreadyAdded: Boolean = false,
    ) {
        if (!alreadyAdded && !announced.add(detail)) return
        investigations.appendEvents(
            listOf(
                InvestigationEvent(
                    investigationId = investigationId,
                    timestamp = timestamp,
                    type = EventType.OBSERVATION_LIMITED,
                    severity = EventSeverity.INFO,
                    title = "Observation limited",
                    detail = detail,
                    packageName = null,
                    processName = null,
                    value = null,
                    previousValue = null,
                    evidence = "Detected while sampling",
                ),
            ),
        )
    }

    private fun defaultName(timestamp: Long): String {
        val stamp = java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(timestamp))
        return "Investigation $stamp"
    }
}

private data class Sampled(
    val snapshot: ProcessSnapshot,
    val cpuProvenance: String,
    val discoveryMethod: String,
)

/** Live recording progress, for the investigate screen and the notification. */
data class RecordingState(
    val investigationId: Long? = null,
    val isRecording: Boolean = false,
    val startedAt: Long? = null,
    val targetPackage: String? = null,
    val plannedDurationMillis: Long? = null,
    val sampleCount: Int = 0,
    val eventCount: Int = 0,
    val lastSampleAt: Long? = null,
    val lastSnapshotRowId: Long? = null,
) {
    val elapsedMillis: Long
        get() = startedAt?.let { System.currentTimeMillis() - it } ?: 0L

    /** Null when the user chose an open-ended recording. */
    val progressFraction: Float?
        get() = plannedDurationMillis?.let {
            if (it <= 0) null else (elapsedMillis.toFloat() / it).coerceIn(0f, 1f)
        }
}

private fun com.processlens.domain.model.ProcessInfo.belongsToPackage(pkg: String): Boolean =
    packageName == pkg || processName == pkg || processName.startsWith("$pkg:")
