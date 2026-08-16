package com.processlens.domain.model

import com.processlens.core.common.AccessLevel

/**
 * The capability matrix (Section 47).
 *
 * Each [Capability] is a thing a user might want to observe. Its availability is
 * *computed at runtime* per device — never assumed from the build-time target —
 * because the answer depends on the API level, the permissions actually granted,
 * whether Shizuku is running, whether root exists, and in several cases on
 * whether a probe read of a specific /proc path succeeded on this particular
 * kernel.
 *
 * Section 47 also requires the matrix to vary by API level in both directions: a
 * capability available on API 26 may be restricted on 29+, which is exactly what
 * happens to /proc process enumeration (hidepid from API 29) and to
 * `getRunningAppProcesses` (own-process-only from API 28).
 */
enum class Capability(
    val displayName: String,
    val group: CapabilityGroup,
    val description: String,
) {
    CPU_OVERALL("CPU", CapabilityGroup.CPU, "Overall CPU utilisation across all cores"),
    CPU_PER_CORE("Per-core CPU", CapabilityGroup.CPU, "Utilisation of each individual core"),
    CPU_FREQUENCY("CPU frequency", CapabilityGroup.CPU, "Current clock speed of each core"),
    LOAD_AVERAGE("Load average", CapabilityGroup.CPU, "1/5/15-minute run-queue averages"),
    CPU_TEMPERATURE("CPU temperature", CapabilityGroup.CPU, "Thermal zone readings"),

    MEMORY_TOTAL("Memory", CapabilityGroup.MEMORY, "Total and available system memory"),
    MEMORY_DETAIL("Memory breakdown", CapabilityGroup.MEMORY, "Cached, buffers, free and swap"),
    MEMORY_PER_PROCESS("Per-process memory", CapabilityGroup.MEMORY, "PSS of individual processes"),

    PROCESS_LIST("Process list", CapabilityGroup.PROCESS, "Enumerate running processes"),
    PROCESS_PID("PID", CapabilityGroup.PROCESS, "Process identifiers"),
    PROCESS_UID("UID", CapabilityGroup.PROCESS, "Owning user identifier"),
    PROCESS_STATE("Process state", CapabilityGroup.PROCESS, "Kernel scheduling state"),
    PROCESS_START_TIME("Process start time", CapabilityGroup.PROCESS, "When a process began"),
    PROCESS_CPU("Per-process CPU", CapabilityGroup.PROCESS, "CPU time consumed per process"),
    THREADS("Threads", CapabilityGroup.PROCESS, "Threads belonging to a process"),
    PROCESS_TREE("Process tree", CapabilityGroup.PROCESS, "Parent/child process relationships"),

    APP_LIST("Installed apps", CapabilityGroup.APPLICATION, "Enumerate installed packages"),
    APP_COMPONENTS("App components", CapabilityGroup.APPLICATION, "Activities, services, receivers, providers"),
    APP_PERMISSIONS("App permissions", CapabilityGroup.APPLICATION, "Requested permissions and grant state"),
    RUNNING_SERVICES("Running services", CapabilityGroup.APPLICATION, "Which services are currently active"),
    APP_USAGE("App usage", CapabilityGroup.APPLICATION, "Foreground time and last-used timestamps"),

    BATTERY_BASIC("Battery", CapabilityGroup.BATTERY, "Level and charging state"),
    BATTERY_TEMPERATURE("Battery temperature", CapabilityGroup.BATTERY, "Reported cell temperature"),
    BATTERY_VOLTAGE("Battery voltage", CapabilityGroup.BATTERY, "Reported cell voltage"),
    BATTERY_CURRENT("Battery current", CapabilityGroup.BATTERY, "Instantaneous current draw"),
    BATTERY_HEALTH("Battery health", CapabilityGroup.BATTERY, "Platform-reported health state"),
    BATTERY_PER_APP("Per-app battery", CapabilityGroup.BATTERY, "Battery attributed to each app"),

    WAKELOCKS("WakeLocks", CapabilityGroup.POWER, "Held wake locks and their owners"),
    ALARMS("Alarms", CapabilityGroup.POWER, "Scheduled alarms and wakeup counts"),

    NETWORK_STATE("Network state", CapabilityGroup.NETWORK, "Transport, metered and VPN status"),
    NETWORK_INTERFACE_STATS("Interface statistics", CapabilityGroup.NETWORK, "Device-wide RX/TX counters"),
    NETWORK_PER_APP("Per-app network", CapabilityGroup.NETWORK, "Bytes sent and received per app"),

    STORAGE("Storage", CapabilityGroup.SYSTEM, "Volume capacity and free space"),
    UPTIME("Uptime", CapabilityGroup.SYSTEM, "Time since boot"),
    KERNEL_INFO("Kernel version", CapabilityGroup.SYSTEM, "Running kernel build string"),

    ROOT_DIAGNOSTICS("Root diagnostics", CapabilityGroup.ELEVATED, "Full dumpsys and unrestricted /proc"),
    SHIZUKU_DIAGNOSTICS("Shizuku diagnostics", CapabilityGroup.ELEVATED, "ADB-level shell observation"),
    ;
}

enum class CapabilityGroup(val displayName: String) {
    CPU("Processor"),
    MEMORY("Memory"),
    PROCESS("Processes"),
    APPLICATION("Applications"),
    BATTERY("Battery"),
    POWER("Power management"),
    NETWORK("Network"),
    SYSTEM("System"),
    ELEVATED("Elevated access"),
}

/**
 * Availability of one capability. [Availability.LIMITED] is the honest middle
 * ground the spec's example matrix calls for: the data exists but is partial,
 * derived, or covers only a subset (e.g. PID is knowable for our own process but
 * not for others on API 28+).
 */
enum class Availability(val label: String, val symbol: String) {
    FULL("Available", "✓"),
    LIMITED("Limited", "◐"),
    UNAVAILABLE("Unavailable", "✕"),
    ;

    val isUsable: Boolean get() = this != UNAVAILABLE
}

/**
 * One row of the matrix. [reason] is user-facing prose explaining *why* the
 * capability is limited or unavailable on this device, and [unlockedBy] names the
 * access level that would improve it — which is what Section 48's "Try:"
 * suggestions and Section 42's "Access level:" footer are built from.
 */
data class CapabilityStatus(
    val capability: Capability,
    val availability: Availability,
    val reason: String,
    val unlockedBy: AccessLevel? = null,
    /** Present when a real probe (rather than a version check) decided this. */
    val probeDetail: String? = null,
) {
    val isUsable: Boolean get() = availability.isUsable
}

/**
 * The full runtime matrix, plus the context that produced it. Kept together so
 * the Settings > Diagnostics screen can explain the whole picture, and so
 * exported investigations record the exact access conditions they were taken
 * under (a recording made without usage access means something different from one
 * made with Shizuku running).
 */
data class SystemCapabilities(
    val apiLevel: Int,
    val accessLevel: AccessLevel,
    val shizukuState: ShizukuState,
    val rootState: RootState,
    val hasUsageAccess: Boolean,
    val hasPhoneStatePermission: Boolean,
    val hasNotificationPermission: Boolean,
    val isBatteryOptimisationIgnored: Boolean,
    val statuses: Map<Capability, CapabilityStatus>,
) {
    operator fun get(capability: Capability): CapabilityStatus =
        statuses[capability] ?: CapabilityStatus(
            capability,
            Availability.UNAVAILABLE,
            "Not evaluated on this device.",
        )

    fun availability(capability: Capability): Availability = get(capability).availability

    fun isUsable(capability: Capability): Boolean = get(capability).isUsable

    fun isFull(capability: Capability): Boolean = availability(capability) == Availability.FULL

    /** Only capabilities worth showing a row for (Section 47: hide unsupported). */
    fun usable(): List<CapabilityStatus> = statuses.values.filter { it.isUsable }

    fun byGroup(): Map<CapabilityGroup, List<CapabilityStatus>> =
        statuses.values.sortedBy { it.capability.ordinal }.groupBy { it.capability.group }

    val fullCount: Int get() = statuses.values.count { it.availability == Availability.FULL }
    val limitedCount: Int get() = statuses.values.count { it.availability == Availability.LIMITED }
    val unavailableCount: Int get() = statuses.values.count { it.availability == Availability.UNAVAILABLE }

    companion object {
        /** Used only as an initial UI state before the first real evaluation. */
        fun unknown(apiLevel: Int): SystemCapabilities = SystemCapabilities(
            apiLevel = apiLevel,
            accessLevel = AccessLevel.NORMAL,
            shizukuState = ShizukuState.NOT_INSTALLED,
            rootState = RootState.UNAVAILABLE,
            hasUsageAccess = false,
            hasPhoneStatePermission = false,
            hasNotificationPermission = false,
            isBatteryOptimisationIgnored = false,
            statuses = emptyMap(),
        )
    }
}

/** Section 27 requires all five of these states to be distinguished. */
enum class ShizukuState(val label: String, val isUsable: Boolean) {
    NOT_INSTALLED("Not installed", false),
    INSTALLED_NOT_RUNNING("Installed but not running", false),
    RUNNING_PERMISSION_UNKNOWN("Running, permission not requested", false),
    RUNNING_PERMISSION_DENIED("Running, permission denied", false),
    RUNNING_PERMISSION_GRANTED("Running, permission granted", true),
    /** Older Shizuku that pre-dates the binder API this app uses. */
    VERSION_UNSUPPORTED("Version not supported", false),
}

enum class RootState(val label: String, val isUsable: Boolean) {
    UNAVAILABLE("Not available", false),
    /** An `su` binary exists but has not been exercised. */
    BINARY_PRESENT("Binary present, not granted", false),
    DENIED("Denied by superuser manager", false),
    GRANTED("Granted", true),
}
