package com.processlens.domain.model

/**
 * An investigation (Sections 14, 24) — a bounded recording session during which
 * ProcessLens samples the system and derives events.
 */
data class Investigation(
    val id: Long = 0,
    val name: String,
    val startedAt: Long,
    val endedAt: Long?,
    val state: InvestigationState,
    /** Set when the user scoped the recording to one package. */
    val targetPackage: String?,
    val sampleIntervalMillis: Long,
    val eventCount: Int,
    val snapshotCount: Int,
    val processesObserved: Int,
    /** Access level in force while recording — changes what the data can mean. */
    val accessLevelName: String,
    val apiLevel: Int,
    val deviceLabel: String,
    val notes: String?,
) {
    val durationMillis: Long
        get() = (endedAt ?: System.currentTimeMillis()) - startedAt

    val isRunning: Boolean get() = state == InvestigationState.RECORDING
}

enum class InvestigationState(val label: String) {
    RECORDING("Recording"),
    COMPLETED("Completed"),
    /** Ended abnormally (process death, service killed) — data is still valid. */
    INTERRUPTED("Interrupted"),
}

/**
 * A derived, human-readable event on the timeline (Section 13).
 *
 * Events are *derived from real samples*, never synthesised: a CPU_SPIKE exists
 * only because two consecutive snapshots showed a real delta crossing the user's
 * threshold. [evidence] carries the numbers behind it so the detail sheet can
 * show its work.
 */
data class InvestigationEvent(
    val id: Long = 0,
    val investigationId: Long,
    val timestamp: Long,
    val type: EventType,
    val severity: EventSeverity,
    val title: String,
    val detail: String,
    val packageName: String?,
    val processName: String?,
    /** The measured value that triggered this, in the type's natural unit. */
    val value: Double?,
    /** The previous value, where the event is a delta. */
    val previousValue: Double?,
    /** Human-readable provenance, e.g. "/proc sampled at 2 s intervals". */
    val evidence: String,
)

enum class EventType(val label: String, val group: EventGroup) {
    PROCESS_STARTED("Process started", EventGroup.PROCESS),
    PROCESS_STOPPED("Process stopped", EventGroup.PROCESS),
    PROCESS_RESTARTED("Process restarted", EventGroup.PROCESS),
    CPU_SPIKE("CPU spike", EventGroup.CPU),
    CPU_SUSTAINED("Sustained CPU", EventGroup.CPU),
    CPU_DROPPED("CPU activity decreased", EventGroup.CPU),
    MEMORY_INCREASE("Memory increase", EventGroup.MEMORY),
    MEMORY_DECREASE("Memory released", EventGroup.MEMORY),
    MEMORY_PRESSURE("System memory pressure", EventGroup.MEMORY),
    SERVICE_STARTED("Service started", EventGroup.SERVICE),
    SERVICE_STOPPED("Service stopped", EventGroup.SERVICE),
    BATTERY_LEVEL_CHANGE("Battery level changed", EventGroup.BATTERY),
    BATTERY_CHARGING_CHANGE("Charging state changed", EventGroup.BATTERY),
    BATTERY_TEMPERATURE_RISE("Battery temperature rose", EventGroup.BATTERY),
    NETWORK_ACTIVITY("Network activity", EventGroup.NETWORK),
    NETWORK_STATE_CHANGE("Network changed", EventGroup.NETWORK),
    WAKELOCK_ACQUIRED("WakeLock acquired", EventGroup.WAKELOCK),
    WAKELOCK_RELEASED("WakeLock released", EventGroup.WAKELOCK),
    SCREEN_STATE_CHANGE("Screen state changed", EventGroup.SYSTEM),
    THERMAL_CHANGE("Thermal state changed", EventGroup.SYSTEM),
    INVESTIGATION_STARTED("Investigation started", EventGroup.SYSTEM),
    INVESTIGATION_STOPPED("Investigation stopped", EventGroup.SYSTEM),
    /** Recorded when a capability was lost mid-recording, so gaps are explicable. */
    OBSERVATION_LIMITED("Observation limited", EventGroup.SYSTEM),
    ;
}

/** The filter axes Section 13 requires. */
enum class EventGroup(val label: String) {
    PROCESS("Process"),
    CPU("CPU"),
    MEMORY("Memory"),
    SERVICE("Services"),
    BATTERY("Battery"),
    NETWORK("Network"),
    WAKELOCK("WakeLocks"),
    SYSTEM("System"),
}

/**
 * Severity drives colour *and* an icon/label, never colour alone (Section 49).
 */
enum class EventSeverity(val label: String, val order: Int) {
    INFO("Info", 0),
    NORMAL("Normal", 1),
    WARNING("Warning", 2),
    CRITICAL("Critical", 3),
}

/**
 * One sampling tick: the system-wide figures plus the per-process rows that were
 * observable at that instant. Stored so the timeline can be replayed (Section 25)
 * and so summaries are recomputable rather than trusted.
 */
data class ProcessSnapshot(
    val id: Long = 0,
    val investigationId: Long,
    val timestamp: Long,
    val cpuPercent: Float?,
    val memoryUsedBytes: Long,
    val memoryAvailableBytes: Long,
    val batteryLevel: Int,
    val batteryTemperatureDeciCelsius: Int?,
    val isCharging: Boolean,
    val isScreenOn: Boolean,
    val networkRxBytes: Long?,
    val networkTxBytes: Long?,
    val processCount: Int,
    /** Own-process CPU/memory, so the tool's own cost is on the record (Section 43). */
    val ownCpuPercent: Float?,
    val ownMemoryBytes: Long?,
    val entries: List<SnapshotEntry>,
)

data class SnapshotEntry(
    val processName: String,
    val packageName: String?,
    val pid: Int?,
    val cpuPercent: Float?,
    val memoryBytes: Long?,
    val importance: ProcessImportance,
)

/**
 * The generated summary (Section 14). Every field is nullable because a summary
 * only states what the recording actually established; "Repeated activity: none
 * detected" is a real finding, but a fabricated headline is not.
 *
 * Wording is deliberately correlational — [correlations] entries are phrased as
 * "potential correlation", never causation.
 */
data class InvestigationSummary(
    val investigation: Investigation,
    val durationMillis: Long,
    val highestCpuProcess: RankedProcess?,
    val largestMemoryIncrease: RankedProcess?,
    val mostFrequentlyRestarted: RankedProcess?,
    val batteryDrainPercent: Int?,
    val batteryTemperatureRiseDeciCelsius: Int?,
    val networkActivityDetected: Boolean,
    val wakeLockActivityDetected: Observed3State,
    val eventCounts: Map<EventGroup, Int>,
    val correlations: List<String>,
    /** Capabilities that were unavailable, so absences are explained not implied. */
    val limitations: List<String>,
)

data class RankedProcess(
    val processName: String,
    val packageName: String?,
    val label: String,
    val value: Double,
    val unit: String,
)

/**
 * Three-state answer for "was X detected?". Distinguishes "no" from "we could not
 * have seen it either way", which for WakeLocks is the difference between a
 * finding and a blind spot.
 */
enum class Observed3State(val label: String) {
    DETECTED("Detected"),
    NOT_DETECTED("Not detected"),
    NOT_OBSERVABLE("Not observable at this access level"),
}
