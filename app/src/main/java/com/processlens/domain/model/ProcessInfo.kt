package com.processlens.domain.model

import com.processlens.core.common.AccessLevel
import com.processlens.core.common.Observed

/**
 * One observed process.
 *
 * Every field that Android may withhold is an [Observed], so a screen physically
 * cannot render a fabricated PID or CPU figure — there is no plain `Int` to fall
 * back to. What is *always* knowable is only the identity the platform gave us.
 */
data class ProcessInfo(
    /**
     * Stable key for diffing and navigation. Prefers "pid:<n>" when a real PID is
     * known, otherwise the process name, which is unique among running processes.
     */
    val id: String,
    val processName: String,
    val packageName: String?,
    val appLabel: String?,
    val pid: Observed<Int>,
    val uid: Observed<Int>,
    val state: ProcessState,
    val importance: ProcessImportance,
    /** Share of one-CPU-second-per-wall-second, 0..100 scale, sampled. */
    val cpuPercent: Observed<Float>,
    val memoryBytes: Observed<Long>,
    val threadCount: Observed<Int>,
    /** Wall-clock millis when the process started, if derivable. */
    val startTimeMillis: Observed<Long>,
    val isSystem: Boolean,
    val isOwnProcess: Boolean = false,
    /** Set only when the platform actually told us the parent (never guessed). */
    val parentPid: Observed<Int> = Observed.platform("Parent PID is not exposed to apps"),
    val discoveredVia: DiscoveryMethod,
) {
    val displayName: String get() = appLabel ?: packageName ?: processName

    /** True when the row has enough real data to be worth sorting numerically. */
    val hasMetrics: Boolean
        get() = cpuPercent is Observed.Value || memoryBytes is Observed.Value

    companion object {
        fun idForPid(pid: Int): String = "pid:$pid"
        fun idForName(name: String): String = "name:$name"
    }
}

/**
 * How a process row was found. This is shown in the UI because it determines how
 * complete the list can be: on API 28+ a normal app cannot enumerate other
 * processes at all, so a list built from [DiscoveryMethod.USAGE_STATS] is a list
 * of recently-active *packages*, not a true process table, and saying otherwise
 * would be the exact fabrication Section 42 forbids.
 */
enum class DiscoveryMethod(val label: String, val requires: AccessLevel, val isCompleteList: Boolean) {
    /** /proc walk. Complete only where hidepid does not apply (API 26–27). */
    PROC_WALK("/proc scan", AccessLevel.NORMAL, true),

    /** ActivityManager.getRunningAppProcesses() — own process only on API 28+. */
    ACTIVITY_MANAGER("ActivityManager", AccessLevel.NORMAL, false),

    /** Recently-active packages inferred from usage stats. Not a process table. */
    USAGE_STATS("Usage access", AccessLevel.NORMAL, false),

    /** Our own process, always readable. */
    SELF("Own process", AccessLevel.NORMAL, true),

    SHELL_PS("ps via Shizuku", AccessLevel.SHIZUKU, true),
    ROOT_PS("ps via root", AccessLevel.ROOT, true),
    ;
}

/**
 * Process state. Maps the Linux `/proc/<pid>/stat` state character where a real
 * /proc read succeeded, and the ActivityManager importance bands otherwise.
 */
enum class ProcessState(val label: String) {
    RUNNING("Running"),
    SLEEPING("Sleeping"),
    DISK_SLEEP("Uninterruptible"),
    STOPPED("Stopped"),
    ZOMBIE("Zombie"),
    TRACED("Traced"),
    IDLE("Idle"),
    /** Live per ActivityManager, but the kernel-level state is not readable. */
    ACTIVE("Active"),
    /** Known to have run recently; current liveness not observable. */
    RECENTLY_ACTIVE("Recently active"),
    UNKNOWN("Unknown"),
    ;

    companion object {
        fun fromProcChar(c: Char): ProcessState = when (c) {
            'R' -> RUNNING
            'S' -> SLEEPING
            'D' -> DISK_SLEEP
            'T' -> STOPPED
            't' -> TRACED
            'Z' -> ZOMBIE
            'I' -> IDLE
            else -> UNKNOWN
        }
    }
}

/**
 * ActivityManager importance bands, which are the platform's own notion of how
 * alive a process is. Kept as a separate axis from [ProcessState] because they
 * answer different questions and come from different sources.
 */
enum class ProcessImportance(val label: String, val order: Int) {
    FOREGROUND("Foreground", 0),
    FOREGROUND_SERVICE("Foreground service", 1),
    VISIBLE("Visible", 2),
    PERCEPTIBLE("Perceptible", 3),
    SERVICE("Service", 4),
    CACHED("Cached", 5),
    GONE("Gone", 6),
    UNKNOWN("Unknown", 7),
    ;

    val isBackground: Boolean get() = order >= SERVICE.order
    val isForeground: Boolean get() = order <= VISIBLE.order
}
