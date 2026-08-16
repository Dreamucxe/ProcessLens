package com.processlens.core.system

import android.content.Context
import android.os.Build
import com.processlens.core.common.AccessLevel
import com.processlens.core.common.IoDispatcher
import com.processlens.core.common.Observed
import com.processlens.core.permissions.PermissionChecker
import com.processlens.domain.model.Availability
import com.processlens.domain.model.Capability
import com.processlens.domain.model.CapabilityStatus
import com.processlens.domain.model.RootState
import com.processlens.domain.model.ShizukuState
import com.processlens.domain.model.SystemCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Computes the capability matrix (Section 47).
 *
 * The rule this class exists to enforce: **never assume a capability from the
 * build-time SDK**. Two devices on the same API level disagree constantly —
 * SELinux policy differs by OEM, `/proc/stat` is readable on some API 29 devices
 * and denied on others, cpufreq nodes are world-readable on most Qualcomm kernels
 * and hidden on some MediaTek ones. So where a version check cannot settle the
 * question, this performs a **real probe**: it attempts the actual read and
 * records what happened, including the failure text, in
 * [CapabilityStatus.probeDetail].
 *
 * API-level transitions that genuinely matter here, and why:
 *
 *  - **API 24**: `/proc` mounted `hidepid=2` for third-party apps on many OEM
 *    kernels; AOSP made it universal later. Probed, not assumed.
 *  - **API 26 (O)**: background execution limits; `getRunningServices` starts
 *    returning only the caller's own services.
 *  - **API 28 (P)**: `getRunningAppProcesses()` returns only the caller's own
 *    process. This is the single biggest loss of observability in Android's
 *    history for a tool like this, and it is why usage access matters so much.
 *  - **API 29 (Q)**: `/proc` hidepid enforced in AOSP; per-app network stats
 *    require usage access; `NetworkStatsManager` bucket UIDs get coarser.
 *  - **API 30 (R)**: package visibility filtering — `getInstalledPackages`
 *    returns a curated subset without QUERY_ALL_PACKAGES.
 *  - **API 31 (S)**: foreground-service launch restrictions; `dumpsys` reachable
 *    only with elevated access.
 *  - **API 33 (T)**: POST_NOTIFICATIONS becomes a runtime permission, which the
 *    investigation service needs for its ongoing notification.
 */
@Singleton
class CapabilityDetector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val procFs: ProcFsReader,
    private val permissions: PermissionChecker,
    private val shizuku: ShizukuShell,
    private val root: RootShell,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /**
     * Evaluates the full matrix. Runs on IO because the probes touch the
     * filesystem; typically completes in a few milliseconds because each probe
     * reads one small file.
     */
    suspend fun detect(): SystemCapabilities = withContext(io) {
        val api = Build.VERSION.SDK_INT
        val shizukuState = shizuku.state()
        val rootState = root.state()
        val access = when {
            rootState.isUsable -> AccessLevel.ROOT
            shizukuState.isUsable -> AccessLevel.SHIZUKU
            else -> AccessLevel.NORMAL
        }
        val hasUsage = permissions.hasUsageAccess()
        val hasPhone = permissions.hasPhoneStatePermission()

        // ---- Probes: read the real thing rather than trusting the API level ----
        val procStatProbe = procFs.readSystemCpuTimes()
        val perCoreProbe = procFs.readPerCoreCpuTimes()
        val memInfoProbe = procFs.readMemInfo()
        val visiblePidsProbe = procFs.listVisiblePids()
        val ownPid = android.os.Process.myPid()
        val ownStatProbe = procFs.readProcessStat(ownPid)
        val ownThreadsProbe = procFs.listThreadIds(ownPid)
        val freqProbe = procFs.readCoreFrequencies(Runtime.getRuntime().availableProcessors())
        val tempProbe = procFs.readCpuTemperature()
        val loadProbe = procFs.readLoadAverage()

        /**
         * The decisive question for the whole process feature set: can we see any
         * process other than our own? Under hidepid the answer is no, and a list
         * built anyway would be a list of one row labelled as a process table.
         */
        val visiblePids = (visiblePidsProbe as? Observed.Value)?.value ?: emptyList()
        val foreignPidCount = visiblePids.count { it != ownPid }
        val canWalkProc = foreignPidCount > 2

        val statuses = LinkedHashMap<Capability, CapabilityStatus>(Capability.entries.size)

        fun put(
            capability: Capability,
            availability: Availability,
            reason: String,
            unlockedBy: AccessLevel? = null,
            probeDetail: String? = null,
        ) {
            statuses[capability] = CapabilityStatus(capability, availability, reason, unlockedBy, probeDetail)
        }

        /** Shared helper: elevated shell can read anything /proc-based. */
        val elevatedFallback: AccessLevel? = when {
            access == AccessLevel.NORMAL -> AccessLevel.SHIZUKU
            else -> null
        }

        // ------------------------------------------------------------------ CPU
        when (procStatProbe) {
            is Observed.Value -> put(
                Capability.CPU_OVERALL, Availability.FULL,
                "Sampled from /proc/stat between refreshes.",
                probeDetail = "/proc/stat read succeeded",
            )
            else -> put(
                Capability.CPU_OVERALL,
                if (access != AccessLevel.NORMAL) Availability.FULL else Availability.LIMITED,
                if (access != AccessLevel.NORMAL) {
                    "Read through the elevated shell because the app sandbox cannot read /proc/stat."
                } else {
                    "SELinux denies this app /proc/stat on this device, so only ProcessLens' own " +
                        "CPU time can be measured directly."
                },
                unlockedBy = elevatedFallback,
                probeDetail = probeText(procStatProbe),
            )
        }

        when (perCoreProbe) {
            is Observed.Value -> put(
                Capability.CPU_PER_CORE, Availability.FULL,
                "Per-core counters read from /proc/stat.",
                probeDetail = "${perCoreProbe.value.size} core lines visible",
            )
            else -> put(
                Capability.CPU_PER_CORE,
                if (access != AccessLevel.NORMAL) Availability.FULL else Availability.UNAVAILABLE,
                if (access != AccessLevel.NORMAL) {
                    "Read through the elevated shell."
                } else {
                    "Per-core counters are not readable by apps on this device."
                },
                unlockedBy = elevatedFallback,
                probeDetail = probeText(perCoreProbe),
            )
        }

        when (freqProbe) {
            is Observed.Value -> {
                val online = freqProbe.value.count { it.isOnline }
                put(
                    Capability.CPU_FREQUENCY,
                    if (online == freqProbe.value.size) Availability.FULL else Availability.LIMITED,
                    if (online == freqProbe.value.size) {
                        "Read from the kernel's cpufreq nodes."
                    } else {
                        "$online of ${freqProbe.value.size} cores expose a current frequency; " +
                            "the rest are offline or hidden."
                    },
                    probeDetail = "cpufreq readable for $online cores",
                )
            }
            else -> put(
                Capability.CPU_FREQUENCY, Availability.UNAVAILABLE,
                "This kernel does not expose cpufreq nodes to applications.",
                unlockedBy = elevatedFallback,
                probeDetail = probeText(freqProbe),
            )
        }

        when (loadProbe) {
            is Observed.Value -> put(
                Capability.LOAD_AVERAGE, Availability.FULL,
                "Read from /proc/loadavg.",
            )
            else -> put(
                Capability.LOAD_AVERAGE, Availability.UNAVAILABLE,
                "/proc/loadavg is not readable on this device.",
                unlockedBy = elevatedFallback,
                probeDetail = probeText(loadProbe),
            )
        }

        when (tempProbe) {
            is Observed.Value -> put(
                Capability.CPU_TEMPERATURE, Availability.FULL,
                "Read from a kernel thermal zone.",
                probeDetail = "thermal zone readable",
            )
            else -> put(
                Capability.CPU_TEMPERATURE, Availability.UNAVAILABLE,
                "No CPU thermal zone is readable on this device. Battery temperature " +
                    "is reported separately and is usually available.",
                probeDetail = probeText(tempProbe),
            )
        }

        // --------------------------------------------------------------- memory
        put(
            Capability.MEMORY_TOTAL, Availability.FULL,
            "Reported by ActivityManager on every Android version.",
        )

        when (memInfoProbe) {
            is Observed.Value -> put(
                Capability.MEMORY_DETAIL, Availability.FULL,
                "Cached, buffers and swap read from /proc/meminfo.",
                probeDetail = "${memInfoProbe.value.size} meminfo keys visible",
            )
            else -> put(
                Capability.MEMORY_DETAIL,
                if (access != AccessLevel.NORMAL) Availability.FULL else Availability.LIMITED,
                if (access != AccessLevel.NORMAL) {
                    "Read through the elevated shell."
                } else {
                    "/proc/meminfo is not readable, so only the total and available " +
                        "figures from ActivityManager can be shown."
                },
                unlockedBy = elevatedFallback,
                probeDetail = probeText(memInfoProbe),
            )
        }

        put(
            Capability.MEMORY_PER_PROCESS,
            when {
                access != AccessLevel.NORMAL -> Availability.FULL
                canWalkProc -> Availability.LIMITED
                else -> Availability.LIMITED
            },
            when {
                access != AccessLevel.NORMAL ->
                    "Per-process PSS read through the elevated shell."
                canWalkProc ->
                    "RSS is readable from /proc for visible processes. PSS — the figure " +
                        "Android itself reports — needs elevated access."
                else ->
                    "Only ProcessLens' own memory can be measured. Android restricts " +
                        "other processes' memory to system apps since Android 8."
            },
            unlockedBy = elevatedFallback,
        )

        // -------------------------------------------------------------- process
        put(
            Capability.PROCESS_LIST,
            when {
                access != AccessLevel.NORMAL -> Availability.FULL
                canWalkProc -> Availability.FULL
                hasUsage -> Availability.LIMITED
                else -> Availability.LIMITED
            },
            when {
                access != AccessLevel.NORMAL ->
                    "Full process table read through the elevated shell."
                canWalkProc ->
                    "Full process table read by walking /proc ($foreignPidCount other " +
                        "processes visible)."
                hasUsage ->
                    "Android hides other processes from apps on this version, so the list " +
                        "shows recently-active packages from usage access rather than a " +
                        "true process table."
                else ->
                    "Android hides other processes from apps on this version. Grant usage " +
                        "access to list recently-active packages, or start Shizuku for the " +
                        "real process table."
            },
            unlockedBy = elevatedFallback,
            probeDetail = "$foreignPidCount foreign PIDs visible in /proc",
        )

        put(
            Capability.PROCESS_PID,
            when {
                access != AccessLevel.NORMAL || canWalkProc -> Availability.FULL
                else -> Availability.LIMITED
            },
            when {
                access != AccessLevel.NORMAL -> "Read through the elevated shell."
                canWalkProc -> "Read from /proc for every visible process."
                else -> "Only ProcessLens' own PID is visible. Android does not expose " +
                    "other processes' identifiers to normal apps on this version."
            },
            unlockedBy = elevatedFallback,
        )

        put(
            Capability.PROCESS_UID,
            when {
                access != AccessLevel.NORMAL || canWalkProc -> Availability.FULL
                else -> Availability.LIMITED
            },
            when {
                access != AccessLevel.NORMAL || canWalkProc ->
                    "Read from /proc/<pid>/status."
                else ->
                    "UIDs are derived from the package manager for installed apps; the " +
                        "owning UID of an arbitrary process is not visible."
            },
            unlockedBy = elevatedFallback,
        )

        put(
            Capability.PROCESS_STATE,
            when {
                access != AccessLevel.NORMAL || canWalkProc -> Availability.FULL
                else -> Availability.LIMITED
            },
            when {
                access != AccessLevel.NORMAL || canWalkProc ->
                    "Kernel scheduling state read from /proc/<pid>/stat."
                else ->
                    "Kernel state is not readable. ProcessLens shows the platform's own " +
                        "importance band instead, which is a coarser but genuine signal."
            },
            unlockedBy = elevatedFallback,
        )

        put(
            Capability.PROCESS_START_TIME,
            when {
                access != AccessLevel.NORMAL || canWalkProc -> Availability.FULL
                hasUsage -> Availability.LIMITED
                else -> Availability.UNAVAILABLE
            },
            when {
                access != AccessLevel.NORMAL || canWalkProc ->
                    "Derived from the process start jiffies in /proc/<pid>/stat."
                hasUsage ->
                    "Exact start time is not available; usage access provides the last " +
                        "time each package was used, which is shown instead and labelled."
                else ->
                    "Not observable without /proc access or usage access."
            },
            unlockedBy = elevatedFallback,
        )

        put(
            Capability.PROCESS_CPU,
            when {
                access != AccessLevel.NORMAL || canWalkProc -> Availability.FULL
                else -> Availability.LIMITED
            },
            when {
                access != AccessLevel.NORMAL || canWalkProc ->
                    "Sampled from per-process jiffy counters between refreshes."
                else ->
                    "Only ProcessLens' own CPU time can be sampled. Per-app CPU is not " +
                        "exposed to normal apps on this Android version."
            },
            unlockedBy = elevatedFallback,
        )

        val ownThreadsReadable = ownThreadsProbe is Observed.Value
        put(
            Capability.THREADS,
            when {
                access != AccessLevel.NORMAL || canWalkProc -> Availability.FULL
                ownThreadsReadable -> Availability.LIMITED
                else -> Availability.UNAVAILABLE
            },
            when {
                access != AccessLevel.NORMAL || canWalkProc ->
                    "Threads read from /proc/<pid>/task."
                ownThreadsReadable ->
                    "Threads can be listed for ProcessLens itself, but Android does not " +
                        "expose other processes' threads to normal apps."
                else ->
                    "Thread enumeration is not permitted on this device."
            },
            unlockedBy = elevatedFallback,
            probeDetail = probeText(ownThreadsProbe),
        )

        put(
            Capability.PROCESS_TREE,
            when {
                access != AccessLevel.NORMAL || canWalkProc -> Availability.FULL
                else -> Availability.LIMITED
            },
            when {
                access != AccessLevel.NORMAL || canWalkProc ->
                    "Parent PIDs read from /proc/<pid>/stat, so the real hierarchy is shown."
                else ->
                    "True parent/child links are not readable. ProcessLens groups processes " +
                        "by the package that owns them, which is a real relationship from the " +
                        "package manager — not an invented hierarchy."
            },
            unlockedBy = elevatedFallback,
        )

        // ---------------------------------------------------------- application
        put(
            Capability.APP_LIST,
            if (permissions.hasFullPackageVisibility()) Availability.FULL else Availability.LIMITED,
            if (permissions.hasFullPackageVisibility()) {
                "All installed packages are visible."
            } else {
                "Android ${Build.VERSION.RELEASE} filters package visibility, so only apps " +
                    "matching a declared query are listed."
            },
        )
        put(
            Capability.APP_COMPONENTS, Availability.FULL,
            "Activities, services, receivers and providers come from the package manager.",
        )
        put(
            Capability.APP_PERMISSIONS, Availability.FULL,
            "Requested permissions and their grant state come from the package manager.",
        )

        put(
            Capability.RUNNING_SERVICES,
            when {
                access != AccessLevel.NORMAL -> Availability.FULL
                api >= Build.VERSION_CODES.O -> Availability.LIMITED
                else -> Availability.FULL
            },
            when {
                access != AccessLevel.NORMAL ->
                    "Read from dumpsys through the elevated shell."
                api >= Build.VERSION_CODES.O ->
                    "Since Android 8, getRunningServices returns only this app's own " +
                        "services. Other apps' declared services are listed from their " +
                        "manifests, without live running state."
                else ->
                    "Running services are reported by ActivityManager."
            },
            unlockedBy = elevatedFallback,
        )

        put(
            Capability.APP_USAGE,
            if (hasUsage) Availability.FULL else Availability.UNAVAILABLE,
            if (hasUsage) {
                "Foreground time and last-used timestamps come from usage access."
            } else {
                "Usage access has not been granted."
            },
        )

        // -------------------------------------------------------------- battery
        put(Capability.BATTERY_BASIC, Availability.FULL, "Level and charging state are always available.")
        put(
            Capability.BATTERY_TEMPERATURE, Availability.FULL,
            "Reported by the battery service. Verified at read time; shown as " +
                "unavailable if this device omits it.",
        )
        put(
            Capability.BATTERY_VOLTAGE, Availability.FULL,
            "Reported by the battery service where the device supplies it.",
        )
        put(
            Capability.BATTERY_CURRENT,
            if (api >= Build.VERSION_CODES.LOLLIPOP) Availability.LIMITED else Availability.UNAVAILABLE,
            "BATTERY_PROPERTY_CURRENT_NOW is optional and many devices return 0 or an " +
                "OEM-specific sign convention. ProcessLens shows it only when the value " +
                "is plausible, and never derives a time-remaining figure from it.",
        )
        put(
            Capability.BATTERY_HEALTH, Availability.LIMITED,
            "Android reports a coarse health state (good, overheat, dead). A percentage " +
                "health figure is not exposed by any public API and is never estimated.",
        )
        put(
            Capability.BATTERY_PER_APP,
            when {
                access != AccessLevel.NORMAL -> Availability.LIMITED
                else -> Availability.UNAVAILABLE
            },
            when {
                access != AccessLevel.NORMAL ->
                    "Parsed from dumpsys batterystats. Attribution is the platform's own " +
                        "and is coarse."
                else ->
                    "Per-app battery attribution is not available to normal apps on any " +
                        "current Android version."
            },
            unlockedBy = elevatedFallback,
        )

        // ---------------------------------------------------------------- power
        put(
            Capability.WAKELOCKS,
            if (access != AccessLevel.NORMAL) Availability.FULL else Availability.UNAVAILABLE,
            if (access != AccessLevel.NORMAL) {
                "Read from dumpsys power through the elevated shell."
            } else {
                "Android has never exposed other apps' wake locks to normal applications. " +
                    "This requires Shizuku or root."
            },
            unlockedBy = elevatedFallback,
        )
        put(
            Capability.ALARMS,
            if (access != AccessLevel.NORMAL) Availability.FULL else Availability.UNAVAILABLE,
            if (access != AccessLevel.NORMAL) {
                "Read from dumpsys alarm through the elevated shell."
            } else {
                "Scheduled alarms of other apps are not visible to normal applications."
            },
            unlockedBy = elevatedFallback,
        )

        // -------------------------------------------------------------- network
        put(Capability.NETWORK_STATE, Availability.FULL, "Reported by ConnectivityManager.")
        put(
            Capability.NETWORK_INTERFACE_STATS, Availability.FULL,
            "Device-wide byte counters from TrafficStats. These reset at boot and cover " +
                "the whole device, not a single app.",
        )
        put(
            Capability.NETWORK_PER_APP,
            when {
                !hasUsage -> Availability.UNAVAILABLE
                api >= Build.VERSION_CODES.Q && !hasPhone -> Availability.LIMITED
                else -> Availability.FULL
            },
            when {
                !hasUsage ->
                    "Per-app network statistics require usage access."
                api >= Build.VERSION_CODES.Q && !hasPhone ->
                    "Wi-Fi statistics are available. Mobile-data attribution additionally " +
                        "needs the phone-state permission on Android 10 and above."
                else ->
                    "Read from NetworkStatsManager."
            },
        )

        // --------------------------------------------------------------- system
        put(Capability.STORAGE, Availability.FULL, "Volume capacity read with StatFs.")
        put(Capability.UPTIME, Availability.FULL, "Time since boot from SystemClock.")
        put(
            Capability.KERNEL_INFO,
            if (System.getProperty("os.version") != null) Availability.FULL else Availability.LIMITED,
            "Kernel build string from the JVM system properties.",
        )

        // ------------------------------------------------------------- elevated
        put(
            Capability.SHIZUKU_DIAGNOSTICS,
            if (shizukuState.isUsable) Availability.FULL else Availability.UNAVAILABLE,
            shizukuReason(shizukuState),
            unlockedBy = if (shizukuState.isUsable) null else AccessLevel.SHIZUKU,
        )
        put(
            Capability.ROOT_DIAGNOSTICS,
            if (rootState.isUsable) Availability.FULL else Availability.UNAVAILABLE,
            rootReason(rootState),
            unlockedBy = if (rootState.isUsable) null else AccessLevel.ROOT,
        )

        SystemCapabilities(
            apiLevel = api,
            accessLevel = access,
            shizukuState = shizukuState,
            rootState = rootState,
            hasUsageAccess = hasUsage,
            hasPhoneStatePermission = hasPhone,
            hasNotificationPermission = permissions.hasNotificationPermission(),
            isBatteryOptimisationIgnored = permissions.isIgnoringBatteryOptimisations(),
            statuses = statuses,
        )
    }

    private fun shizukuReason(state: ShizukuState): String = when (state) {
        ShizukuState.NOT_INSTALLED ->
            "Shizuku is not installed. It is a separate open-source app that grants " +
                "ADB-level access without root."
        ShizukuState.INSTALLED_NOT_RUNNING ->
            "Shizuku is installed but its service is not running. Start it from the " +
                "Shizuku app, then refresh."
        ShizukuState.RUNNING_PERMISSION_UNKNOWN ->
            "Shizuku is running. ProcessLens has not yet been granted permission to use it."
        ShizukuState.RUNNING_PERMISSION_DENIED ->
            "Permission to use Shizuku was denied."
        ShizukuState.RUNNING_PERMISSION_GRANTED ->
            "Shizuku is running with permission granted. This is ADB-level access, not root."
        ShizukuState.VERSION_UNSUPPORTED ->
            "The installed Shizuku version predates the API this app uses. Update Shizuku."
    }

    private fun rootReason(state: RootState): String = when (state) {
        RootState.UNAVAILABLE -> "No superuser binary was found on this device."
        RootState.BINARY_PRESENT ->
            "A superuser binary exists but access has not been requested. Enable root " +
                "support in Settings to request it."
        RootState.DENIED -> "The superuser manager denied ProcessLens."
        RootState.GRANTED -> "Root access granted."
    }

    /** Short technical string for the expandable details section (Section 48). */
    private fun probeText(observed: Observed<*>): String = when (observed) {
        is Observed.Value -> "read succeeded"
        is Observed.Restricted -> "restricted: ${observed.detail.ifBlank { observed.reason.name }}"
        is Observed.Failed -> "failed: ${observed.detail}"
    }
}
