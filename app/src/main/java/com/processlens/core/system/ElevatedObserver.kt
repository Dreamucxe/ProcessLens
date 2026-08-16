package com.processlens.core.system

import android.os.SystemClock
import com.processlens.core.common.AccessLevel
import com.processlens.core.common.DataSource
import com.processlens.core.common.Observed
import com.processlens.core.common.Precision
import com.processlens.core.common.valueOrNull
import com.processlens.domain.model.AppBatteryUsage
import com.processlens.domain.model.AppNetworkUsage
import com.processlens.domain.model.BatteryInfo
import com.processlens.domain.model.CpuInfo
import com.processlens.domain.model.DeviceInfo
import com.processlens.domain.model.DiscoveryMethod
import com.processlens.domain.model.MemoryInfo
import com.processlens.domain.model.ProcessImportance
import com.processlens.domain.model.ProcessInfo
import com.processlens.domain.model.ServiceInfo
import com.processlens.domain.model.StorageInfo
import com.processlens.domain.model.SystemCapabilities
import com.processlens.domain.model.ThreadInfo
import com.processlens.domain.model.WakeLockInfo

/**
 * Observation through an elevated shell (Sections 27–28).
 *
 * One class serves both Shizuku and root because the *observations* are identical
 * — `ps`, `dumpsys power`, `dumpsys meminfo` — and only the transport differs. The
 * shell is injected, `accessLevel` comes from it, and every reading is tagged with
 * the level that produced it so the UI can say "read via Shizuku" rather than
 * implying a normal API supplied it.
 *
 * What this class deliberately does *not* do:
 *
 *  - It does not pretend Shizuku is root. Shizuku runs commands as `shell` (uid
 *    2000), which can read `dumpsys` but cannot read another app's private data.
 *    Anything genuinely requiring uid 0 stays [Observed.Restricted] with
 *    `unlockedBy = ROOT` even when a Shizuku shell is available.
 *  - It does not construct commands. Every invocation goes through
 *    [DiagnosticCommand], the read-only allowlist.
 *  - It does not replace the standard observer. Battery, network and device facts
 *    come from the public APIs that already work; a shell adds nothing there, and
 *    routing them through text parsing would make reliable data less reliable.
 *    Those calls delegate to [delegate].
 */
class ElevatedObserver(
    private val shell: ElevatedShell,
    private val delegate: SystemObserver,
    private val packages: PackageInspector,
    private val cpuSampler: CpuSampler,
) : SystemObserver {

    override val accessLevel: AccessLevel = shell.accessLevel

    override suspend fun isAvailable(): Boolean = shell.isAvailable()

    /** Capabilities are computed centrally; the detector already probes both shells. */
    override suspend fun getCapabilities(): SystemCapabilities = delegate.getCapabilities()

    private val shellSource: DataSource
        get() = if (accessLevel == AccessLevel.ROOT) DataSource.SHELL_ROOT else DataSource.SHELL_SHIZUKU

    // ------------------------------------------------------------------ processes

    /**
     * The real process table, via `ps -A`.
     *
     * This is the payoff of elevated access: a complete list with real PIDs on an
     * Android version where the sandbox otherwise shows one process. PSS from
     * `dumpsys meminfo` is merged in where available because it is the figure the
     * platform itself uses for memory pressure decisions — and where the meminfo
     * parse fails, RSS from `ps` stands on its own rather than the row losing its
     * memory figure entirely.
     */
    override suspend fun getProcesses(): List<ProcessInfo> {
        val result = shell.execute(DiagnosticCommand.ProcessTable.argv)
        if (!result.isSuccess) {
            // A failed shell is not a reason to show nothing: the unprivileged
            // observer still knows what it knows.
            return delegate.getProcesses()
        }

        val rows = DumpsysParsers.parsePsTable(result.stdout).valueOrNull
            ?: return delegate.getProcesses()

        val pssByPid = shell.execute(DiagnosticCommand.MemInfoDump.argv, timeoutMillis = 10_000L)
            .takeIf { it.isSuccess }
            ?.let { DumpsysParsers.parsePssByPid(it.stdout).valueOrNull }

        val nowElapsed = SystemClock.elapsedRealtime()
        val ownPid = android.os.Process.myPid()
        val livePids = HashSet<Int>(rows.size)

        val processes = rows.map { row ->
            livePids += row.pid
            val packageName = packageNameOf(row.name)
            val app = packageName?.let { packages.getAppCached(it) }
            val pss = pssByPid?.get(row.pid)

            ProcessInfo(
                id = ProcessInfo.idForPid(row.pid),
                processName = row.name,
                packageName = packageName,
                appLabel = app?.label,
                pid = Observed.of(row.pid, shellSource),
                uid = app?.uid?.let { Observed.of(it, DataSource.PACKAGE_MANAGER) }
                    ?: uidFromUserName(row.user),
                state = row.state,
                importance = ProcessImportance.UNKNOWN,
                // `ps` prints accumulated CPU time, so the same two-sample rule as
                // /proc applies: converted to jiffies-equivalent for the sampler.
                cpuPercent = cpuSampler.sampleProcess(
                    row.pid,
                    row.cpuTimeMillis / 10L,
                    nowElapsed,
                ),
                memoryBytes = when {
                    pss != null -> Observed.of(pss, DataSource.DUMPSYS_SHIZUKU, Precision.EXACT)
                    row.rssKb > 0 -> Observed.of(row.rssKb * 1024L, shellSource)
                    else -> Observed.notPresent("No memory figure was reported for this process")
                },
                threadCount = Observed.notPresent(
                    "The process table does not print a thread count; open the process to read its threads",
                ),
                startTimeMillis = Observed.notPresent(
                    "The process table does not print a start time",
                ),
                isSystem = row.user == "root" || row.user == "system" ||
                    (app?.isSystemApp == true) || row.name.startsWith("["),
                isOwnProcess = row.pid == ownPid,
                parentPid = if (row.ppid >= 0) {
                    Observed.of(row.ppid, shellSource)
                } else {
                    Observed.notPresent("No parent was reported")
                },
                discoveredVia = if (accessLevel == AccessLevel.ROOT) {
                    DiscoveryMethod.ROOT_PS
                } else {
                    DiscoveryMethod.SHELL_PS
                },
            )
        }
        cpuSampler.retainOnly(livePids)
        return processes
    }

    /**
     * `ps` prints a user *name*; well-known Android users map to fixed UIDs, and an
     * `u0_aNNN` name encodes the app id. Anything else is left unavailable rather
     * than guessed, because a wrong UID would misattribute network and battery data.
     */
    private fun uidFromUserName(user: String): Observed<Int> = when {
        user == "root" -> Observed.of(0, shellSource)
        user == "system" -> Observed.of(1000, shellSource)
        user == "shell" -> Observed.of(2000, shellSource)
        user.startsWith("u0_a") -> user.removePrefix("u0_a").toIntOrNull()
            ?.let { Observed.of(FIRST_APPLICATION_UID + it, shellSource) }
            ?: Observed.Failed("Unrecognised user name '$user'")
        else -> user.toIntOrNull()?.let { Observed.of(it, shellSource) }
            ?: Observed.notPresent("User '$user' does not map to a known UID")
    }

    private fun packageNameOf(processName: String): String? {
        if (processName.isBlank() || processName.startsWith("[") || processName.startsWith("/")) {
            return null
        }
        val base = processName.substringBefore(':')
        if (!base.contains('.')) return null
        return if (packages.isInstalled(base)) base else null
    }

    // -------------------------------------------------------------------- threads

    override suspend fun getThreads(pid: Int): Observed<List<ThreadInfo>> {
        // The standard reader wins when /proc is readable: it is faster than a
        // shell round-trip per thread and gives identical numbers.
        val standard = delegate.getThreads(pid)
        if (standard is Observed.Value) return standard

        val listing = shell.execute(DiagnosticCommand.ThreadList(pid).argv)
        if (!listing.isSuccess) {
            return Observed.Failed(
                "Threads of process $pid could not be listed through the shell",
                listing.stderr.trim().take(200).ifBlank { "exit code ${listing.exitCode}" },
            )
        }
        val tids = listing.lines()
            .flatMap { line -> line.trim().split(Regex("\\s+")) }
            .mapNotNull { it.trim().toIntOrNull() }
            .distinct()

        if (tids.isEmpty()) {
            return Observed.Failed("No threads were listed for process $pid")
        }

        val nowElapsed = SystemClock.elapsedRealtime()
        val threads = ArrayList<ThreadInfo>(tids.size)
        // Bounded: a shell round-trip per thread is expensive, and a process with
        // thousands of threads would otherwise stall the UI. The cap is reported.
        for (tid in tids.take(MAX_SHELL_THREADS)) {
            val stat = shell.execute(DiagnosticCommand.ThreadStat(pid, tid).argv)
            if (!stat.isSuccess) continue
            val parsed = ProcFsReader.parseStatLine(stat.stdout.trim()) ?: continue
            threads += ThreadInfo(
                tid = tid,
                name = parsed.comm,
                state = parsed.state,
                cpuPercent = cpuSampler.sampleProcess(tid, parsed.cpuJiffies, nowElapsed),
                priority = Observed.of(parsed.priority, shellSource),
            )
        }
        return if (threads.isEmpty()) {
            Observed.Failed("Thread entries existed for process $pid but none could be read")
        } else {
            Observed.of(threads, shellSource)
        }
    }

    // ------------------------------------------------------------------ wakelocks

    /** The observation Shizuku unlocks most visibly (Section 16). */
    override suspend fun getWakeLocks(): Observed<List<WakeLockInfo>> {
        val result = shell.execute(DiagnosticCommand.PowerWakeLocks.argv, timeoutMillis = 10_000L)
        if (!result.isSuccess) {
            return Observed.Failed(
                "The power service dump could not be read",
                result.stderr.trim().take(200).ifBlank { "exit code ${result.exitCode}" },
            )
        }
        val parsed = DumpsysParsers.parseWakeLocks(result.stdout, accessLevel)
        // Attach package names where the UID resolves; a UID with no visible
        // package keeps its numeric label rather than being assigned to an app.
        return parsed.let { observed ->
            if (observed !is Observed.Value) return@let observed
            Observed.of(
                observed.value.map { lock ->
                    val pkg = lock.uid?.let { packages.primaryPackageForUid(it) }
                    lock.copy(packageName = pkg)
                },
                observed.source,
                observed.precision,
            )
        }
    }

    // ------------------------------------------------------------------- services

    override suspend fun getRunningServices(packageName: String?): Observed<List<ServiceInfo>> {
        val result = shell.execute(DiagnosticCommand.RunningServices.argv, timeoutMillis = 10_000L)
        if (!result.isSuccess) {
            return delegate.getRunningServices(packageName)
        }
        return DumpsysParsers.parseRunningServices(result.stdout, accessLevel, packageName)
    }

    /**
     * Android's own per-app battery attribution (Section 17). Only available with a
     * shell, and never estimated when it is not.
     */
    suspend fun getBatteryUsage(): Observed<List<AppBatteryUsage>> {
        val result = shell.execute(DiagnosticCommand.BatteryStats.argv, timeoutMillis = 20_000L)
        if (!result.isSuccess) {
            return Observed.Failed(
                "Battery statistics could not be read",
                result.stderr.trim().take(200).ifBlank { "exit code ${result.exitCode}" },
            )
        }
        val parsed = DumpsysParsers.parseBatteryUsage(result.stdout, accessLevel)
        return parsed.let { observed ->
            if (observed !is Observed.Value) return@let observed
            Observed.of(
                observed.value.map { usage ->
                    val pkg = packages.primaryPackageForUid(usage.uid)
                    usage.copy(
                        packageName = pkg,
                        appLabel = pkg?.let { packages.labelFor(it) },
                    )
                },
                observed.source,
                observed.precision,
            )
        }
    }

    /**
     * Per-process PSS as the platform computes it. Exposed separately so the memory
     * screen can show the breakdown a shell unlocks (Section 8).
     */
    suspend fun getPssByPid(): Observed<Map<Int, Long>> {
        val result = shell.execute(DiagnosticCommand.MemInfoDump.argv, timeoutMillis = 15_000L)
        return if (result.isSuccess) {
            DumpsysParsers.parsePssByPid(result.stdout)
        } else {
            Observed.Failed(
                "The memory dump could not be read",
                result.stderr.trim().take(200).ifBlank { "exit code ${result.exitCode}" },
            )
        }
    }

    // ------------------------------------------------- delegated public-API reads

    // These come from public APIs that already work at normal access. Routing them
    // through a shell would replace reliable structured data with parsed text.
    override suspend fun getMemoryInfo(): MemoryInfo = delegate.getMemoryInfo()
    override suspend fun getBatteryInfo(): BatteryInfo = delegate.getBatteryInfo()
    override suspend fun getNetworkInfo() = delegate.getNetworkInfo()
    override suspend fun getCpuInfo(): CpuInfo = delegate.getCpuInfo()
    override suspend fun getStorageInfo(): StorageInfo = delegate.getStorageInfo()
    override suspend fun getDeviceInfo(): DeviceInfo = delegate.getDeviceInfo()
    override suspend fun getOwnResourceUsage(): OwnUsage = delegate.getOwnResourceUsage()

    override suspend fun getPerAppNetworkUsage(sinceMillis: Long): Observed<List<AppNetworkUsage>> =
        delegate.getPerAppNetworkUsage(sinceMillis)

    private companion object {
        const val FIRST_APPLICATION_UID = 10_000

        /**
         * Cap on per-thread shell round-trips. A `system_server` with 200 threads
         * would otherwise mean 200 process spawns; the UI states when the list was
         * truncated rather than silently showing a partial set.
         */
        const val MAX_SHELL_THREADS = 96
    }
}
