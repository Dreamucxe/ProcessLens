package com.processlens.core.system

import com.processlens.core.common.AccessLevel

/**
 * Result of running a command through an elevated shell.
 */
data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val accessLevel: AccessLevel,
) {
    val isSuccess: Boolean get() = exitCode == 0

    /** Non-blank stdout lines, which is what every parser here actually wants. */
    fun lines(): List<String> = stdout.lineSequence().filter { it.isNotBlank() }.toList()

    companion object {
        fun failure(detail: String, level: AccessLevel) =
            ShellResult(-1, "", detail, level)
    }
}

/**
 * An elevated shell. Two implementations exist — Shizuku (ADB-level) and root —
 * and both are optional: the app is fully functional without either.
 *
 * Deliberately narrow. The UI never reaches this interface (Section 46: "The UI
 * should never directly call shell commands"), only observers do, and the
 * commands they issue are read-only diagnostics. There is no generic
 * "execute whatever the user typed" path, and nothing here can kill a process or
 * change system state (Sections 19, 28).
 */
interface ElevatedShell {
    val accessLevel: AccessLevel

    /** Cheap liveness check; must not prompt the user. */
    suspend fun isAvailable(): Boolean

    /**
     * Runs a read-only diagnostic command. [argv] is passed as a real argument
     * vector rather than a string so nothing can be word-split or injected.
     */
    suspend fun execute(argv: List<String>, timeoutMillis: Long = DEFAULT_TIMEOUT): ShellResult

    companion object {
        const val DEFAULT_TIMEOUT = 5_000L
    }
}

/**
 * Guard list for elevated commands.
 *
 * Every command the app can issue is enumerated here. An observer cannot
 * construct an arbitrary command: it picks one of these and supplies only the
 * arguments the constructor allows. That makes "never automatically execute
 * destructive root commands" (Section 28) a structural property rather than a
 * code-review promise — there is no `rm`, no `kill`, no `setprop`, no `pm`
 * mutation verb reachable from anywhere in the app.
 */
sealed class DiagnosticCommand(val argv: List<String>, val description: String) {

    /** Full process table with CPU/RSS. `-A` for all, `-o` for a stable format. */
    data object ProcessTable : DiagnosticCommand(
        listOf("ps", "-A", "-o", "PID,PPID,USER,RSS,VSZ,STAT,TIME,NAME"),
        "Read the full process table",
    )

    /** `/proc/stat` via the shell, for devices where SELinux denies the app read. */
    data object SystemCpuStat : DiagnosticCommand(
        listOf("cat", "/proc/stat"),
        "Read system CPU counters",
    )

    class ProcessStat(pid: Int) : DiagnosticCommand(
        listOf("cat", "/proc/$pid/stat"),
        "Read CPU counters for PID $pid",
    )

    class ProcessStatus(pid: Int) : DiagnosticCommand(
        listOf("cat", "/proc/$pid/status"),
        "Read process status for PID $pid",
    )

    class ThreadList(pid: Int) : DiagnosticCommand(
        listOf("ls", "/proc/$pid/task"),
        "List threads of PID $pid",
    )

    class ThreadStat(pid: Int, tid: Int) : DiagnosticCommand(
        listOf("cat", "/proc/$pid/task/$tid/stat"),
        "Read thread counters for $pid/$tid",
    )

    /** WakeLock section of the power service (Section 16). Read-only. */
    data object PowerWakeLocks : DiagnosticCommand(
        listOf("dumpsys", "power"),
        "Read held wake locks",
    )

    /** Battery stats history, for per-app battery attribution (Section 17). */
    data object BatteryStats : DiagnosticCommand(
        listOf("dumpsys", "batterystats", "--charged"),
        "Read battery statistics",
    )

    /** Running services across all packages (Section 19). */
    data object RunningServices : DiagnosticCommand(
        listOf("dumpsys", "activity", "services"),
        "List running services",
    )

    /** Alarm schedule, for correlating periodic wakeups. */
    data object AlarmDump : DiagnosticCommand(
        listOf("dumpsys", "alarm"),
        "Read scheduled alarms",
    )

    /** Per-process PSS as the platform itself computes it. */
    data object MemInfoDump : DiagnosticCommand(
        listOf("dumpsys", "meminfo"),
        "Read per-process memory",
    )

    class PackageMemInfo(packageName: String) : DiagnosticCommand(
        listOf("dumpsys", "meminfo", packageName),
        "Read memory detail for $packageName",
    )

    /** Probe used to verify a shell actually works before advertising it. */
    data object Probe : DiagnosticCommand(
        listOf("id"),
        "Verify shell access",
    )
}
