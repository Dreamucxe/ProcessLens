package com.processlens.core.system

import android.util.Log
import com.processlens.core.common.AccessLevel
import com.processlens.core.common.DataSource
import com.processlens.core.common.Observed
import com.processlens.core.common.Precision
import com.processlens.domain.model.CoreFrequency
import com.processlens.domain.model.LoadAverage
import com.processlens.domain.model.ProcessState
import java.io.File

/**
 * Low-level `/proc` and `/sys` reader.
 *
 * Everything here is best-effort by design. On API 26–27 an app can usually walk
 * `/proc` and read other processes' `stat`; from API 29 the kernel is mounted
 * with `hidepid=2` so only our own PID is visible; and SELinux policy on many
 * devices denies `/proc/stat` outright even where the DAC mode permits it. Each
 * method therefore returns [Observed] and the failure is reported, never
 * substituted with a zero.
 *
 * All calls are blocking file I/O and must run on an IO dispatcher — the caller
 * is responsible for that, which keeps this class a pure, testable adapter with
 * an injectable [root] for unit tests.
 *
 * ## Device-wide metrics are read once per refusal, not once per sample
 *
 * A refused read is already terminal here — [readFile] maps a denial to an
 * [Observed.Restricted] and nothing in this class retries — but *terminal* and
 * *remembered* are different things, and only the first was true. The sampler
 * re-asks every one to ten seconds, so a device whose policy denies `/proc/stat`
 * or `/sys/class/thermal` answered the same denial thousands of times per session
 * and wrote an audit line for each.
 *
 * The six device-wide metrics below therefore go through [restrictions], which
 * hands back the first refusal without touching the filesystem again. Per-process
 * reads deliberately do **not**: their keys would be paths containing a PID, the
 * map would grow without bound on a busy device, and — worse — a PID the kernel
 * recycles would inherit the verdict passed on the process that used to own it.
 */
class ProcFsReader(
    private val root: File = File("/proc"),
    private val sysRoot: File = File("/sys"),
    private val restrictions: RestrictionCache = RestrictionCache(),
) {

    /**
     * USER_HZ. Linux fixes this at 100 on every Android ABI, and it is the
     * divisor for every jiffy count in the per-process `stat` files.
     * `sysconf(_SC_CLK_TCK)` is not reachable without JNI, and the NDK is
     * unavailable on this build host, so the constant is used with the
     * assumption documented rather than hidden.
     */
    private val clockTicksPerSecond = 100L

    // ---------------------------------------------------------------- system CPU

    /**
     * Aggregate jiffy counters from the first line of `/proc/stat`.
     * Returns [Observed.Restricted] on the very common SELinux denial.
     */
    fun readSystemCpuTimes(): Observed<CpuTimes> = sessionMemo(METRIC_PROC_STAT) {
        when (val line = readFirstLine(File(root, "stat"))) {
            is Observed.Value -> parseCpuLine(line.value)
                ?.let { Observed.of(it, DataSource.PROC_FS) }
                ?: Observed.Failed("Could not parse /proc/stat")
            is Observed.Restricted -> line
            is Observed.Failed -> line
        }
    }

    /**
     * Per-core lines (`cpu0`, `cpu1`, …) from `/proc/stat`.
     *
     * Shares [METRIC_PROC_STAT] with [readSystemCpuTimes]: the two parse different
     * things out of the same file, and a policy that refuses the file refuses it for
     * both, so one memo and one log line cover the pair.
     */
    fun readPerCoreCpuTimes(): Observed<List<CpuTimes>> = sessionMemo(METRIC_PROC_STAT) {
        when (val text = readFile(File(root, "stat"))) {
            is Observed.Value -> {
                val cores = text.value.lineSequence()
                    .filter { it.startsWith("cpu") && it.length > 3 && it[3].isDigit() }
                    .mapNotNull { parseCpuLine(it) }
                    .toList()
                if (cores.isEmpty()) {
                    Observed.Failed("/proc/stat exposed no per-core lines")
                } else {
                    Observed.of(cores, DataSource.PROC_FS)
                }
            }
            is Observed.Restricted -> text
            is Observed.Failed -> text
        }
    }

    /**
     * `cpu  user nice system idle iowait irq softirq steal guest guest_nice`
     * Fields after `steal` are absent on older kernels, so parsing tolerates a
     * short line rather than rejecting it.
     */
    private fun parseCpuLine(line: String): CpuTimes? {
        val parts = line.trim().split(WHITESPACE)
        if (parts.size < 5) return null
        val name = parts[0]
        val nums = parts.drop(1).mapNotNull { it.toLongOrNull() }
        if (nums.size < 4) return null
        return CpuTimes(
            name = name,
            user = nums.getOrElse(0) { 0 },
            nice = nums.getOrElse(1) { 0 },
            system = nums.getOrElse(2) { 0 },
            idle = nums.getOrElse(3) { 0 },
            iowait = nums.getOrElse(4) { 0 },
            irq = nums.getOrElse(5) { 0 },
            softirq = nums.getOrElse(6) { 0 },
            steal = nums.getOrElse(7) { 0 },
        )
    }

    fun readLoadAverage(): Observed<LoadAverage> = sessionMemo(METRIC_LOADAVG) {
        when (val line = readFirstLine(File(root, "loadavg"))) {
            is Observed.Value -> {
                val p = line.value.trim().split(WHITESPACE)
                val one = p.getOrNull(0)?.toFloatOrNull()
                val five = p.getOrNull(1)?.toFloatOrNull()
                val fifteen = p.getOrNull(2)?.toFloatOrNull()
                if (one == null || five == null || fifteen == null) {
                    Observed.Failed("Could not parse /proc/loadavg")
                } else {
                    Observed.of(LoadAverage(one, five, fifteen), DataSource.PROC_FS)
                }
            }
            is Observed.Restricted -> line
            is Observed.Failed -> line
        }
    }

    // ------------------------------------------------------------------- memory

    /** Parses `/proc/meminfo` into its KiB key/value pairs. */
    fun readMemInfo(): Observed<Map<String, Long>> = sessionMemo(METRIC_MEMINFO) {
        when (val text = readFile(File(root, "meminfo"))) {
            is Observed.Value -> {
                val map = HashMap<String, Long>(64)
                text.value.lineSequence().forEach { line ->
                    val colon = line.indexOf(':')
                    if (colon > 0) {
                        val key = line.substring(0, colon)
                        val value = line.substring(colon + 1)
                            .trim()
                            .removeSuffix(" kB")
                            .trim()
                            .toLongOrNull()
                        if (value != null) map[key] = value
                    }
                }
                if (map.isEmpty()) Observed.Failed("/proc/meminfo was empty")
                else Observed.of(map, DataSource.PROC_FS)
            }
            is Observed.Restricted -> text
            is Observed.Failed -> text
        }
    }

    // ------------------------------------------------------------------ process

    /**
     * PIDs currently visible in `/proc`. Under `hidepid=2` (API 29+) this is just
     * our own process; the caller compares the result against the expected count
     * to decide whether a full process list is possible at all.
     *
     * Note what is and is not memoised: a refusal to list `/proc` at all is a
     * device-wide fact worth remembering, while a *short* list is a successful read
     * and is taken again every sample, because processes start and stop.
     */
    fun listVisiblePids(): Observed<List<Int>> = sessionMemo(METRIC_PROC_LISTING) {
        Observed.catching(DataSource.PROC_FS) {
            root.list()?.mapNotNull { it.toIntOrNull() }?.sorted() ?: emptyList()
        }
    }

    /**
     * `/proc/<pid>/stat`. The comm field is wrapped in parentheses and may itself
     * contain spaces and parentheses, so it is extracted by finding the *last*
     * `)` rather than splitting on whitespace — the classic parsing trap.
     */
    fun readProcessStat(pid: Int): Observed<ProcStat> {
        val file = File(root, "$pid/stat")
        return when (val text = readFile(file)) {
            is Observed.Value -> parseProcStat(pid, text.value)
                ?.let { Observed.of(it, DataSource.PROC_FS) }
                ?: Observed.Failed("Could not parse /proc/$pid/stat")
            is Observed.Restricted -> text
            is Observed.Failed -> text
        }
    }

    private fun parseProcStat(pid: Int, raw: String): ProcStat? = parseStatLine(raw, pid)

    companion object {
        private val WHITESPACE = Regex("\\s+")

        private const val TAG = "ProcessLens"

        /**
         * Metric names for the session memo (see [RestrictionCache]).
         *
         * Deliberately the canonical device paths rather than the injected [root] and
         * [sysRoot]: a test pointing the reader at a temporary directory memoises and
         * logs under the same names a phone does, and the log line stays readable by
         * someone who has only the bug report in front of them.
         *
         * One key per *metric*, which is not always one key per file — the two
         * `/proc/stat` readers share theirs — and never a key containing a PID.
         */
        const val METRIC_PROC_STAT = "/proc/stat"
        const val METRIC_LOADAVG = "/proc/loadavg"
        const val METRIC_MEMINFO = "/proc/meminfo"
        const val METRIC_PROC_LISTING = "/proc process listing"
        const val METRIC_CPUFREQ = "/sys cpufreq nodes"
        const val METRIC_THERMAL = "/sys thermal zones"

        /**
         * Parses one `/proc/<pid>/stat` line. Exposed on the companion because the
         * elevated observer reads the same text through a shell, and two copies of
         * this parser would be two chances to get the comm-field trap wrong.
         *
         * When [pidOverride] is null the PID is taken from field 1 of the line
         * itself, which is what a shell `cat` gives us.
         */
        fun parseStatLine(raw: String, pidOverride: Int? = null): ProcStat? {
            val text = raw.trim()
            val open = text.indexOf('(')
            val close = text.lastIndexOf(')')
            if (open < 0 || close < open) return null

            val pid = pidOverride
                ?: text.substring(0, open).trim().toIntOrNull()
                ?: return null

            val comm = text.substring(open + 1, close)
            val rest = text.substring(close + 1).trim().split(WHITESPACE)
            // rest[0] is field 3 (state); indices below are offset accordingly.
            val state = rest.getOrNull(0)?.firstOrNull() ?: return null
            // A real stat line carries 52 fields, so `rest` holds about 50. Anything
            // shorter than the highest index read below is a truncated or unexpected
            // line, and the fields would fall back to 0 — which would put a fabricated
            // 0 jiffies and 0 RSS in front of the user (Section 42). Nothing is safer
            // to report than a line we could not fully read, so it is rejected: both
            // callers already treat null as "skip this process".
            if (rest.size < 22) return null
            return ProcStat(
                pid = pid,
                comm = comm,
                state = ProcessState.fromProcChar(state),
                ppid = rest.getOrNull(1)?.toIntOrNull() ?: -1,
                utime = rest.getOrNull(11)?.toLongOrNull() ?: 0L,
                stime = rest.getOrNull(12)?.toLongOrNull() ?: 0L,
                cutime = rest.getOrNull(13)?.toLongOrNull() ?: 0L,
                cstime = rest.getOrNull(14)?.toLongOrNull() ?: 0L,
                priority = rest.getOrNull(15)?.toIntOrNull() ?: 0,
                nice = rest.getOrNull(16)?.toIntOrNull() ?: 0,
                numThreads = rest.getOrNull(17)?.toIntOrNull() ?: 0,
                // Jiffies since boot; converted to wall-clock by the caller.
                startTimeJiffies = rest.getOrNull(19)?.toLongOrNull() ?: 0L,
                vsizeBytes = rest.getOrNull(20)?.toLongOrNull() ?: 0L,
                rssPages = rest.getOrNull(21)?.toLongOrNull() ?: 0L,
            )
        }
    }

    /** `/proc/<pid>/cmdline` — NUL-separated argv; first entry is the process name. */
    fun readCmdline(pid: Int): Observed<String> {
        return when (val text = readFile(File(root, "$pid/cmdline"))) {
            is Observed.Value -> {
                val name = text.value.split('\u0000').firstOrNull()?.trim().orEmpty()
                if (name.isEmpty()) Observed.Failed("Empty cmdline for pid $pid")
                else Observed.of(name, DataSource.PROC_FS)
            }
            is Observed.Restricted -> text
            is Observed.Failed -> text
        }
    }

    /** `/proc/<pid>/status`, which carries Uid/Gid and the VmRSS figures. */
    fun readProcessStatus(pid: Int): Observed<Map<String, String>> {
        return when (val text = readFile(File(root, "$pid/status"))) {
            is Observed.Value -> {
                val map = HashMap<String, String>(48)
                text.value.lineSequence().forEach { line ->
                    val colon = line.indexOf(':')
                    if (colon > 0) {
                        map[line.substring(0, colon)] = line.substring(colon + 1).trim()
                    }
                }
                if (map.isEmpty()) Observed.Failed("/proc/$pid/status was empty")
                else Observed.of(map, DataSource.PROC_FS)
            }
            is Observed.Restricted -> text
            is Observed.Failed -> text
        }
    }

    /** Real UID from a parsed `status` map (`Uid: real effective saved fs`). */
    fun uidFromStatus(status: Map<String, String>): Int? =
        status["Uid"]?.trim()?.split(WHITESPACE)?.firstOrNull()?.toIntOrNull()

    /** Threads of a process, from the entries under `/proc/<pid>/task` (Section 11). */
    fun listThreadIds(pid: Int): Observed<List<Int>> = Observed.catching(DataSource.PROC_FS) {
        File(root, "$pid/task").list()?.mapNotNull { it.toIntOrNull() }?.sorted() ?: emptyList()
    }

    fun readThreadStat(pid: Int, tid: Int): Observed<ProcStat> {
        val file = File(root, "$pid/task/$tid/stat")
        return when (val text = readFile(file)) {
            is Observed.Value -> parseProcStat(tid, text.value)
                ?.let { Observed.of(it, DataSource.PROC_FS) }
                ?: Observed.Failed("Could not parse thread stat $pid/$tid")
            is Observed.Restricted -> text
            is Observed.Failed -> text
        }
    }

    // ------------------------------------------------------------------ cpufreq

    /**
     * Per-core scaling frequencies from `/sys/devices/system/cpu/cpuN/cpufreq`.
     * An offline core has no readable `scaling_cur_freq`, which is reported as
     * `isOnline = false` rather than 0 Hz.
     *
     * Three files per core, so an eight-core device that hides cpufreq was paying
     * twenty-four refused opens every sample. There is no public API for core
     * frequency on any API level, so sysfs is the only path — which makes
     * remembering the refusal the only saving available.
     */
    fun readCoreFrequencies(coreCount: Int): Observed<List<CoreFrequency>> =
        sessionMemo(METRIC_CPUFREQ) { scanCoreFrequencies(coreCount) }

    private fun scanCoreFrequencies(coreCount: Int): Observed<List<CoreFrequency>> {
        val out = ArrayList<CoreFrequency>(coreCount)
        var anyReadable = false
        for (i in 0 until coreCount) {
            val base = File(sysRoot, "devices/system/cpu/cpu$i/cpufreq")
            val cur = readLongOrNull(File(base, "scaling_cur_freq"))
            val min = readLongOrNull(File(base, "cpuinfo_min_freq"))
            val max = readLongOrNull(File(base, "cpuinfo_max_freq"))
            if (cur != null || min != null || max != null) anyReadable = true
            out += CoreFrequency(
                coreIndex = i,
                currentKHz = cur ?: 0L,
                minKHz = min ?: 0L,
                maxKHz = max ?: 0L,
                isOnline = cur != null,
            )
        }
        return if (!anyReadable) {
            Observed.platform("cpufreq nodes are not readable by apps on this device")
        } else {
            Observed.of(out, DataSource.SYS_FS)
        }
    }

    /**
     * First plausible thermal zone reading. Zone naming is entirely OEM-specific,
     * so zones are filtered by type name and the value is treated as milli-degrees
     * when it is implausibly large for deci-degrees.
     *
     * This is the walk the bug report caught denying itself fifty minutes of audit
     * lines, and it is the one metric here with a public alternative — except that
     * `PowerManager`'s thermal API reports a throttling severity rather than a
     * temperature, so it cannot answer this question. [StandardAndroidObserver]
     * consults it for what it *can* honestly say when this comes back refused.
     */
    fun readCpuTemperature(): Observed<Int> = sessionMemo(METRIC_THERMAL) { scanThermalZones() }

    private fun scanThermalZones(): Observed<Int> {
        val thermalRoot = File(sysRoot, "class/thermal")
        val zones = thermalRoot.listFiles()
            ?.filter { it.name.startsWith("thermal_zone") }
            ?: return if (thermalRoot.exists()) {
                // `listFiles()` answers null for "there is no such directory" and for
                // "you may not look in it" alike. Where the directory is visible but
                // unlistable the second is what happened, and that is a restriction an
                // elevated shell lifts — reporting it as "this device has no thermal
                // zones" would describe the wrong device.
                Observed.platform(
                    "${thermalRoot.path} exists but cannot be listed by this app",
                    AccessLevel.SHIZUKU,
                )
            } else {
                Observed.notPresent("No thermal zones exposed")
            }

        for (zone in zones) {
            val type = readFirstLine(File(zone, "type")).let {
                (it as? Observed.Value)?.value?.lowercase().orEmpty()
            }
            val isCpuZone = type.contains("cpu") || type.contains("soc") ||
                type.contains("tsens") || type.contains("apc") || type.contains("big") ||
                type.contains("little") || type.contains("mtktscpu")
            if (!isCpuZone) continue

            val raw = readLongOrNull(File(zone, "temp")) ?: continue
            // Kernels report either milli-degrees (48000) or deci-degrees (480).
            val deci = when {
                raw > 10_000 -> (raw / 100).toInt()
                raw > 1_000 -> (raw / 10).toInt()
                else -> raw.toInt()
            }
            if (deci in 0..1500) {
                return Observed.of(deci, DataSource.SYS_FS, Precision.EXACT)
            }
        }
        return Observed.notPresent("No CPU thermal zone could be read")
    }

    // -------------------------------------------------------- terminal refusals

    /**
     * Runs [read] unless [key] is already known to be refused, and spends that
     * metric's single log line the first time it is.
     *
     * The log line is composed here rather than relayed: `detail` is text this class
     * wrote, so no kernel audit string, shell output or raw `errno` message reaches
     * logcat through it (Section 48). `Log.i` rather than `Log.w` because a
     * restriction is documented platform behaviour, not a fault — the same
     * distinction the UI draws with a padlock instead of a warning triangle.
     */
    private fun <T> sessionMemo(key: String, read: () -> Observed<T>): Observed<T> {
        val outcome = restrictions.attempt(key, read)
        if (outcome is Observed.Restricted && restrictions.shouldAnnounce(key)) {
            Log.i(
                TAG,
                "$key is not readable at this access level and will not be re-read " +
                    "this session: ${outcome.detail}",
            )
        }
        return outcome
    }

    /**
     * Forgets every remembered refusal.
     *
     * Must be called whenever the app's access level may have changed, because a
     * denial is permanent only for the privileges that earned it: the same
     * `/proc/stat` that is refused to a normal app is read freely through a root or
     * Shizuku shell (Sections 27, 28). [StandardAndroidObserver.getCapabilities]
     * calls this before it probes, which covers both the first evaluation and every
     * re-evaluation forced by a grant, because a capability refresh is the only
     * thing that ever asks what this app can read *now*.
     */
    fun invalidateRestrictions() = restrictions.clear()

    // -------------------------------------------------------------------- utils

    /**
     * Reads a whole file, mapping the two distinct failure modes apart:
     * `EACCES`/`EPERM` means the sandbox refused (a [Observed.Restricted] fact
     * about Android), while a missing file means this kernel does not have it.
     */
    private fun readFile(file: File): Observed<String> = try {
        if (!file.exists()) {
            Observed.notPresent("${file.path} does not exist on this device")
        } else {
            val text = file.readText()
            if (text.isBlank()) {
                Observed.Failed("${file.path} was empty")
            } else {
                Observed.of(text, DataSource.PROC_FS)
            }
        }
    } catch (se: SecurityException) {
        Observed.platform("${file.path} is not readable by this app")
    } catch (io: java.io.IOException) {
        // Android surfaces EACCES from a sandboxed read as a plain IOException
        // (FileNotFoundException: … Permission denied), so the message is the
        // only signal available to tell refusal apart from absence.
        val msg = io.message.orEmpty()
        if (msg.contains("Permission denied", ignoreCase = true) ||
            msg.contains("EACCES", ignoreCase = true)
        ) {
            Observed.platform("${file.path} is not readable by this app")
        } else {
            Observed.Failed("Could not read ${file.path}", msg.ifBlank { null })
        }
    } catch (t: Throwable) {
        Observed.Failed("Could not read ${file.path}", t.message)
    }

    private fun readFirstLine(file: File): Observed<String> = when (val text = readFile(file)) {
        is Observed.Value -> {
            val line = text.value.lineSequence().firstOrNull()?.trim()
            if (line.isNullOrEmpty()) Observed.Failed("${file.path} had no first line")
            else Observed.of(line, text.source)
        }
        is Observed.Restricted -> text
        is Observed.Failed -> text
    }

    private fun readLongOrNull(file: File): Long? =
        (readFirstLine(file) as? Observed.Value)?.value?.trim()?.toLongOrNull()

    /** Jiffies → milliseconds, using the fixed USER_HZ documented above. */
    fun jiffiesToMillis(jiffies: Long): Long = jiffies * 1000L / clockTicksPerSecond
}

/** Raw jiffy counters for one CPU line. */
data class CpuTimes(
    val name: String,
    val user: Long,
    val nice: Long,
    val system: Long,
    val idle: Long,
    val iowait: Long,
    val irq: Long,
    val softirq: Long,
    val steal: Long,
) {
    val total: Long get() = user + nice + system + idle + iowait + irq + softirq + steal

    /** Everything except idle and iowait — iowait is waiting, not computing. */
    val active: Long get() = user + nice + system + irq + softirq + steal

    /**
     * Utilisation between two samples, as a 0..100 percentage.
     * Returns null when the counters did not advance (interval too short, or a
     * counter reset), because a made-up 0% would be indistinguishable from a
     * genuinely idle CPU.
     */
    fun utilisationSince(previous: CpuTimes): Float? {
        val totalDelta = total - previous.total
        if (totalDelta <= 0) return null
        val activeDelta = (active - previous.active).coerceAtLeast(0)
        return (activeDelta.toFloat() / totalDelta.toFloat() * 100f).coerceIn(0f, 100f)
    }
}

/** Parsed `/proc/<pid>/stat`. */
data class ProcStat(
    val pid: Int,
    val comm: String,
    val state: ProcessState,
    val ppid: Int,
    val utime: Long,
    val stime: Long,
    val cutime: Long,
    val cstime: Long,
    val priority: Int,
    val nice: Int,
    val numThreads: Int,
    val startTimeJiffies: Long,
    val vsizeBytes: Long,
    val rssPages: Long,
) {
    /** Total CPU jiffies charged to this process (excluding reaped children). */
    val cpuJiffies: Long get() = utime + stime

    /** RSS in bytes. Page size is 4 KiB on every Android ABI in the 26–34 range. */
    val rssBytes: Long get() = rssPages * 4096L
}
