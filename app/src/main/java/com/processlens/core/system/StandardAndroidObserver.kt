package com.processlens.core.system

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import com.processlens.core.common.AccessLevel
import com.processlens.core.common.DataSource
import com.processlens.core.common.IoDispatcher
import com.processlens.core.common.Observed
import com.processlens.core.common.Precision
import com.processlens.core.common.valueOrNull
import com.processlens.core.permissions.PermissionChecker
import com.processlens.domain.model.AppNetworkUsage
import com.processlens.domain.model.BatteryHealth
import com.processlens.domain.model.BatteryInfo
import com.processlens.domain.model.BatteryStatus
import com.processlens.domain.model.ChargingSource
import com.processlens.domain.model.CpuInfo
import com.processlens.domain.model.DeviceInfo
import com.processlens.domain.model.DiscoveryMethod
import com.processlens.domain.model.MemoryInfo
import com.processlens.domain.model.NetworkInfo
import com.processlens.domain.model.NetworkTransport
import com.processlens.domain.model.ProcessImportance
import com.processlens.domain.model.ProcessInfo
import com.processlens.domain.model.ProcessState
import com.processlens.domain.model.ServiceInfo
import com.processlens.domain.model.StorageInfo
import com.processlens.domain.model.SystemCapabilities
import com.processlens.domain.model.ThreadInfo
import com.processlens.domain.model.WakeLockInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The unprivileged observer — everything ProcessLens can learn using only public
 * APIs and the parts of `/proc` the sandbox still allows (Section 46).
 *
 * This is the implementation that has to work on every device, and it is where the
 * honesty rules bite hardest. Android has spent a decade closing the doors a tool
 * like this used to walk through:
 *
 *  - `getRunningAppProcesses()` returns only our own process from API 28.
 *  - `/proc` is mounted `hidepid=2` from API 29, so other PIDs are simply absent.
 *  - `getRunningServices()` returns only our own services from API 26.
 *  - Per-app network and usage data need the usage-access appop.
 *
 * So rather than pretend, this class assembles the process list from every source
 * that legitimately still works, tags every row with the [DiscoveryMethod] that
 * found it, and leaves fields it could not read as [Observed.Restricted]. A row
 * discovered through usage stats has no PID, and that is exactly what the UI shows.
 */
@Singleton
class StandardAndroidObserver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val procFs: ProcFsReader,
    cpuSamplerFactory: CpuSamplerFactory,
    private val packages: PackageInspector,
    private val usageStats: UsageStatsReader,
    private val permissions: PermissionChecker,
    private val capabilityDetector: CapabilityDetector,
    private val samplingPolicy: SamplingPolicy,
    @IoDispatcher private val io: CoroutineDispatcher,
) : SystemObserver {

    /**
     * This observer's own CPU baselines. It comes from [CpuSamplerFactory] rather
     * than the graph because a sampler is stateful: it holds the previous jiffy
     * reading per PID, and those readings are only comparable against later ones
     * from the same source. Sharing one instance with an elevated observer — whose
     * figures come from `ps` rather than `/proc` — would subtract two counters that
     * do not measure the same thing and produce a plausible-looking wrong number.
     */
    private val cpuSampler: CpuSampler = cpuSamplerFactory.create()

    override val accessLevel: AccessLevel = AccessLevel.NORMAL

    /** Always available: this is the baseline every device supports. */
    override suspend fun isAvailable(): Boolean = true

    private val activityManager: ActivityManager?
        get() = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager

    private val connectivityManager: ConnectivityManager?
        get() = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val powerManager: PowerManager?
        get() = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    private val batteryManager: BatteryManager?
        get() = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

    private val ownPid: Int = android.os.Process.myPid()

    /**
     * Wall-clock time of boot, used to convert a process's start jiffies into a
     * real timestamp. Computed once: `currentTimeMillis - elapsedRealtime` drifts
     * slightly with clock adjustments, and re-deriving it per process would make
     * two processes started at the same moment appear to differ.
     */
    private val bootWallClockMillis: Long =
        System.currentTimeMillis() - SystemClock.elapsedRealtime()

    /** Last TrafficStats reading, for rate derivation. */
    private var lastTraffic: TrafficSample? = null

    override suspend fun getCapabilities(): SystemCapabilities = capabilityDetector.detect()

    // ------------------------------------------------------------------ processes

    override suspend fun getProcesses(): List<ProcessInfo> = withContext(io) {
        val nowElapsed = SystemClock.elapsedRealtime()

        // Source 1: the /proc walk. Complete where the kernel allows it, and the
        // only source that yields real PIDs, states and parent links.
        val fromProc = walkProc(nowElapsed)

        // Source 2: ActivityManager. On API 28+ this is our own process only, but
        // below that it is a genuine list, and on every version it is the only
        // source of the platform's own importance band.
        val fromActivityManager = readActivityManager()

        // Source 3: recently-active packages. Not a process table — labelled as
        // such — but on a modern device it is the only way to see anything at all
        // beyond our own process.
        val fromUsage = if (fromProc.size <= 2) readRecentPackages() else emptyList()

        merge(fromProc, fromActivityManager, fromUsage, nowElapsed)
    }

    /**
     * Reads every PID `/proc` will show us. Under hidepid this returns one entry
     * (ours), which the caller detects and supplements — it never pretends the
     * single row is the whole system.
     */
    private fun walkProc(nowElapsed: Long): List<ProcessInfo> {
        val pids = procFs.listVisiblePids().valueOrNull ?: return emptyList()
        val out = ArrayList<ProcessInfo>(pids.size)
        val livePids = HashSet<Int>(pids.size)

        for (pid in pids) {
            val stat = procFs.readProcessStat(pid).valueOrNull ?: continue
            livePids += pid

            val status = procFs.readProcessStatus(pid).valueOrNull
            val uid = status?.let { procFs.uidFromStatus(it) }
            val cmdline = procFs.readCmdline(pid).valueOrNull
            // cmdline is the package/process name Android uses; comm is truncated
            // to 15 characters by the kernel, so it is only a fallback.
            val name = cmdline?.takeIf { it.isNotBlank() } ?: stat.comm
            val packageName = packageNameOf(name)

            out += ProcessInfo(
                id = ProcessInfo.idForPid(pid),
                processName = name,
                packageName = packageName,
                appLabel = packageName?.let { packages.labelFor(it) },
                pid = Observed.of(pid, DataSource.PROC_FS),
                uid = uid?.let { Observed.of(it, DataSource.PROC_FS) }
                    ?: Observed.platform("/proc/$pid/status is not readable"),
                state = stat.state,
                importance = ProcessImportance.UNKNOWN,
                cpuPercent = cpuSampler.sampleProcess(pid, stat.cpuJiffies, nowElapsed),
                memoryBytes = Observed.of(stat.rssBytes, DataSource.PROC_FS),
                threadCount = Observed.of(stat.numThreads, DataSource.PROC_FS),
                startTimeMillis = Observed.of(
                    bootWallClockMillis + procFs.jiffiesToMillis(stat.startTimeJiffies),
                    DataSource.PROC_FS,
                ),
                isSystem = (uid ?: 9999) < FIRST_APPLICATION_UID,
                isOwnProcess = pid == ownPid,
                parentPid = if (stat.ppid >= 0) {
                    Observed.of(stat.ppid, DataSource.PROC_FS)
                } else {
                    Observed.platform("Parent PID is not exposed for this process")
                },
                discoveredVia = if (pid == ownPid && pids.size <= 2) {
                    DiscoveryMethod.SELF
                } else {
                    DiscoveryMethod.PROC_WALK
                },
            )
        }
        // Keep the sampler's baseline map bounded to processes that still exist.
        cpuSampler.retainOnly(livePids)
        return out
    }

    /**
     * `getRunningAppProcesses()`. Returns own-process-only from API 28 — that is
     * not a bug to work around but a fact to report, so rows from here are tagged
     * [DiscoveryMethod.ACTIVITY_MANAGER] with `isCompleteList = false`.
     */
    private fun readActivityManager(): List<ProcessInfo> {
        val am = activityManager ?: return emptyList()
        val running = try {
            am.runningAppProcesses
        } catch (t: Throwable) {
            null
        } ?: return emptyList()

        return running.mapNotNull { info ->
            val name = info.processName ?: return@mapNotNull null
            val packageName = info.pkgList?.firstOrNull() ?: packageNameOf(name)
            ProcessInfo(
                id = ProcessInfo.idForPid(info.pid),
                processName = name,
                packageName = packageName,
                appLabel = packageName?.let { packages.labelFor(it) },
                pid = Observed.of(info.pid, DataSource.ACTIVITY_MANAGER),
                uid = Observed.of(info.uid, DataSource.ACTIVITY_MANAGER),
                state = ProcessState.ACTIVE,
                importance = importanceOf(info.importance),
                cpuPercent = Observed.platform(
                    "Per-process CPU requires /proc access, which Android restricts on this version",
                    AccessLevel.SHIZUKU,
                ),
                memoryBytes = Observed.platform(
                    "Per-process memory requires /proc access or elevated privileges",
                    AccessLevel.SHIZUKU,
                ),
                threadCount = Observed.platform("Thread counts require /proc access", AccessLevel.SHIZUKU),
                startTimeMillis = Observed.platform("Start time requires /proc access", AccessLevel.SHIZUKU),
                isSystem = info.uid < FIRST_APPLICATION_UID,
                isOwnProcess = info.pid == ownPid,
                discoveredVia = DiscoveryMethod.ACTIVITY_MANAGER,
            )
        }
    }

    /**
     * Recently-active packages from usage access. Each row is explicitly a
     * *package that ran*, not a live process: no PID, state
     * [ProcessState.RECENTLY_ACTIVE], and the last-used timestamp in place of a
     * start time — labelled so in the UI.
     */
    private suspend fun readRecentPackages(): List<ProcessInfo> {
        val stats = usageStats.recentlyUsedPackages().valueOrNull ?: return emptyList()
        val cutoff = System.currentTimeMillis() - RECENT_WINDOW_MILLIS
        return stats.asSequence()
            .filter { it.lastTimeUsed >= cutoff }
            .mapNotNull { usage ->
                val app = packages.getAppCached(usage.packageName) ?: return@mapNotNull null
                ProcessInfo(
                    id = ProcessInfo.idForName(usage.packageName),
                    processName = usage.packageName,
                    packageName = usage.packageName,
                    appLabel = app.label,
                    pid = Observed.platform(
                        "Android does not expose other processes' identifiers on this version",
                        AccessLevel.SHIZUKU,
                    ),
                    uid = Observed.of(app.uid, DataSource.PACKAGE_MANAGER),
                    state = ProcessState.RECENTLY_ACTIVE,
                    importance = ProcessImportance.UNKNOWN,
                    cpuPercent = Observed.platform(
                        "Per-app CPU is not observable at normal access level",
                        AccessLevel.SHIZUKU,
                    ),
                    memoryBytes = Observed.platform(
                        "Per-app memory is not observable at normal access level",
                        AccessLevel.SHIZUKU,
                    ),
                    threadCount = Observed.platform("Not observable", AccessLevel.SHIZUKU),
                    startTimeMillis = Observed.of(
                        usage.lastTimeUsed,
                        DataSource.USAGE_STATS,
                        Precision.ESTIMATED,
                    ),
                    isSystem = app.isSystemApp,
                    discoveredVia = DiscoveryMethod.USAGE_STATS,
                )
            }
            .toList()
    }

    /**
     * Combines the sources, preferring the richest row for each process.
     *
     * Preference order is deliberate: a /proc row carries real kernel data, an
     * ActivityManager row carries the importance band, and the two are merged when
     * both exist rather than one overwriting the other. Usage rows are only added
     * for packages nothing else found, so a live process is never duplicated by a
     * weaker "recently active" row.
     */
    private fun merge(
        fromProc: List<ProcessInfo>,
        fromAm: List<ProcessInfo>,
        fromUsage: List<ProcessInfo>,
        nowElapsed: Long,
    ): List<ProcessInfo> {
        val byPid = LinkedHashMap<Int, ProcessInfo>(fromProc.size + fromAm.size)
        val byName = LinkedHashMap<String, ProcessInfo>()

        for (p in fromProc) {
            val pid = p.pid.valueOrNull ?: continue
            byPid[pid] = p
            byName[p.processName] = p
        }

        for (am in fromAm) {
            val pid = am.pid.valueOrNull
            val existing = pid?.let { byPid[it] } ?: byName[am.processName]
            if (existing != null) {
                // Enrich the /proc row with the importance band only /proc lacks.
                val merged = existing.copy(
                    importance = am.importance,
                    packageName = existing.packageName ?: am.packageName,
                    appLabel = existing.appLabel ?: am.appLabel,
                    uid = if (existing.uid is Observed.Value) existing.uid else am.uid,
                )
                if (pid != null) byPid[pid] = merged
                byName[merged.processName] = merged
            } else {
                if (pid != null) byPid[pid] = am
                byName[am.processName] = am
            }
        }

        val known = byName.keys.toHashSet()
        val extras = fromUsage.filter { it.processName !in known }

        // Own process is guaranteed present: it is the one thing never hidden, and
        // Section 43 requires the app to account for itself.
        val result = ArrayList<ProcessInfo>(byPid.size + extras.size + 1)
        result += byPid.values
        // ActivityManager rows without a PID (never happens today, but the API
        // permits it) live only in byName.
        result += byName.values.filter { it.pid.valueOrNull == null }
        result += extras

        if (result.none { it.isOwnProcess }) {
            result += ownProcessRow(nowElapsed)
        }
        return result
    }

    /** Our own process, read directly. Never restricted, on any API level. */
    private fun ownProcessRow(nowElapsed: Long): ProcessInfo {
        val stat = procFs.readProcessStat(ownPid).valueOrNull
        return ProcessInfo(
            id = ProcessInfo.idForPid(ownPid),
            processName = context.packageName,
            packageName = context.packageName,
            appLabel = packages.labelFor(context.packageName),
            pid = Observed.of(ownPid, DataSource.OWN_PROC),
            uid = Observed.of(android.os.Process.myUid(), DataSource.OWN_PROC),
            state = stat?.state ?: ProcessState.RUNNING,
            importance = ProcessImportance.FOREGROUND,
            cpuPercent = stat?.let { cpuSampler.sampleProcess(ownPid, it.cpuJiffies, nowElapsed) }
                ?: Observed.Failed("Own /proc/self/stat could not be read"),
            memoryBytes = stat?.let { Observed.of(it.rssBytes, DataSource.OWN_PROC) }
                ?: Observed.Failed("Own RSS could not be read"),
            threadCount = stat?.let { Observed.of(it.numThreads, DataSource.OWN_PROC) }
                ?: Observed.of(Thread.activeCount(), DataSource.RUNTIME),
            startTimeMillis = stat?.let {
                Observed.of(
                    bootWallClockMillis + procFs.jiffiesToMillis(it.startTimeJiffies),
                    DataSource.OWN_PROC,
                )
            } ?: Observed.Failed("Own start time could not be read"),
            isSystem = false,
            isOwnProcess = true,
            discoveredVia = DiscoveryMethod.SELF,
        )
    }

    /**
     * Derives a package name from a process name. Android names app processes
     * after the package, optionally with a `:suffix` for a private process or a
     * `sharedUserId` prefix — so the prefix before `:` is a real, documented
     * relationship, not a guess. Anything that is not an installed package
     * (kernel threads, native daemons) returns null rather than a fabricated link.
     */
    private fun packageNameOf(processName: String): String? {
        if (processName.isBlank()) return null
        if (processName.startsWith("[") || processName.startsWith("/")) return null
        val base = processName.substringBefore(':')
        if (!base.contains('.')) return null
        return if (packages.isInstalled(base)) base else null
    }

    private fun importanceOf(importance: Int): ProcessImportance = when (importance) {
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> ProcessImportance.FOREGROUND
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE ->
            ProcessImportance.FOREGROUND_SERVICE
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> ProcessImportance.VISIBLE
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> ProcessImportance.PERCEPTIBLE
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> ProcessImportance.SERVICE
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> ProcessImportance.CACHED
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE -> ProcessImportance.GONE
        else -> ProcessImportance.UNKNOWN
    }

    // --------------------------------------------------------------------- memory

    override suspend fun getMemoryInfo(): MemoryInfo = withContext(io) {
        val am = activityManager
        val amInfo = ActivityManager.MemoryInfo()
        var haveAm = false
        if (am != null) {
            try {
                am.getMemoryInfo(amInfo)
                haveAm = true
            } catch (t: Throwable) {
                haveAm = false
            }
        }

        // The /proc/meminfo parse is the part a user can switch off (Section 43). The
        // ActivityManager call above is one cached Binder round-trip and is what makes
        // the screen mean anything at all, so it always runs.
        val sampleDetail = samplingPolicy.memoryDetailEnabled
        val meminfo = if (sampleDetail) {
            procFs.readMemInfo()
        } else {
            Observed.samplingDisabled(SamplingPolicy.MEMORY_OFF)
        }
        val kb: (String) -> Observed<Long> = { key ->
            when (meminfo) {
                is Observed.Value -> meminfo.value[key]
                    ?.let { Observed.of(it * 1024L, DataSource.PROC_FS) }
                    ?: Observed.notPresent("/proc/meminfo does not report $key on this kernel")
                is Observed.Restricted -> meminfo
                is Observed.Failed -> meminfo
            }
        }

        // Total/available: ActivityManager first because it is always permitted;
        // /proc/meminfo is the fallback so a device that denies one still shows a
        // real figure from the other.
        val procTotal = (meminfo.valueOrNull?.get("MemTotal") ?: 0L) * 1024L
        val procAvailable = (meminfo.valueOrNull?.get("MemAvailable") ?: 0L) * 1024L

        MemoryInfo(
            totalBytes = if (haveAm && amInfo.totalMem > 0) amInfo.totalMem else procTotal,
            availableBytes = if (haveAm && amInfo.availMem > 0) amInfo.availMem else procAvailable,
            cachedBytes = kb("Cached"),
            buffersBytes = kb("Buffers"),
            freeBytes = kb("MemFree"),
            swapTotalBytes = kb("SwapTotal"),
            swapFreeBytes = kb("SwapFree"),
            lowMemoryThresholdBytes = if (haveAm) {
                Observed.of(amInfo.threshold, DataSource.ACTIVITY_MANAGER)
            } else {
                Observed.Failed("ActivityManager did not report a memory threshold")
            },
            isLowMemory = haveAm && amInfo.lowMemory,
            appTotalBytes = Observed.platform(
                "The sum of per-app memory requires elevated access to read PSS for every process",
                AccessLevel.SHIZUKU,
            ),
            systemTotalBytes = Observed.platform(
                "Kernel and system memory attribution requires elevated access",
                AccessLevel.SHIZUKU,
            ),
        )
    }

    // ------------------------------------------------------------------------ CPU

    /**
     * CPU (Section 7).
     *
     * `coreCount` is a static device fact — `availableProcessors()` costs nothing and
     * changes never — so it survives the sampling switch. Everything else here is a
     * real measurement: four `/proc` reads, a per-core scan, one sysfs file per core
     * for frequency and a thermal-zone walk. When the user switches CPU sampling off,
     * none of that runs, and every field says which switch stopped it (Section 43).
     */
    override suspend fun getCpuInfo(): CpuInfo = withContext(io) {
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

        if (!samplingPolicy.cpuEnabled) {
            val off = Observed.samplingDisabled(SamplingPolicy.CPU_OFF)
            return@withContext CpuInfo(
                coreCount = cores,
                overallPercent = off,
                perCorePercent = off,
                frequenciesKHz = off,
                loadAverage = off,
                ownProcessPercent = off,
                temperatureDeciCelsius = off,
            )
        }

        val ownStat = procFs.readProcessStat(ownPid).valueOrNull

        CpuInfo(
            coreCount = cores,
            overallPercent = cpuSampler.sampleSystem(procFs.readSystemCpuTimes()),
            perCorePercent = cpuSampler.samplePerCore(procFs.readPerCoreCpuTimes()),
            frequenciesKHz = procFs.readCoreFrequencies(cores),
            loadAverage = procFs.readLoadAverage(),
            ownProcessPercent = ownStat?.let {
                cpuSampler.sampleProcess(ownPid, it.cpuJiffies, SystemClock.elapsedRealtime())
            } ?: Observed.Failed("Own CPU counters could not be read"),
            temperatureDeciCelsius = procFs.readCpuTemperature(),
        )
    }

    // -------------------------------------------------------------------- battery

    /**
     * Battery state from the sticky `ACTION_BATTERY_CHANGED` broadcast plus
     * [BatteryManager] properties.
     *
     * Section 0.1 forbids fabricating precise battery figures, and this is where
     * that matters most. Every optional field is verified before it is reported:
     *
     *  - voltage/temperature are only trusted inside physically plausible ranges,
     *    because a device that does not supply them reports 0 or -1 rather than
     *    omitting the extra;
     *  - `CURRENT_NOW` is notoriously unreliable — some OEMs report milliamps in a
     *    microamp field, some invert the sign, many return 0 — so an implausible
     *    magnitude is reported as unavailable instead of shown;
     *  - **no time-remaining figure is derived at all**, because doing so from an
     *    instantaneous current reading would be invention, not measurement.
     */
    override suspend fun getBatteryInfo(): BatteryInfo = withContext(io) {
        val intent: Intent? = try {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (t: Throwable) {
            null
        }

        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val levelPercent = if (level >= 0 && scale > 0) level * 100 / scale else 0

        val statusRaw = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val pluggedRaw = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0

        val temperature = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val voltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, Int.MIN_VALUE)
        val technology = intent?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)
        val healthRaw = intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1

        // Everything above arrived in the one sticky broadcast and is therefore already
        // paid for — level, health, temperature and voltage all ride on the same intent.
        // The three counters below are separate BatteryManager property queries, each a
        // Binder call into the battery service and a sysfs read in the HAL beneath it,
        // and those are what the sampling switch actually stops (Section 43).
        val sampleCounters = samplingPolicy.batteryDetailEnabled
        val countersOff = Observed.samplingDisabled(SamplingPolicy.BATTERY_OFF)

        BatteryInfo(
            levelPercent = levelPercent,
            isCharging = statusRaw == BatteryManager.BATTERY_STATUS_CHARGING ||
                statusRaw == BatteryManager.BATTERY_STATUS_FULL && pluggedRaw != 0,
            chargingSource = when (pluggedRaw) {
                BatteryManager.BATTERY_PLUGGED_AC -> ChargingSource.AC
                BatteryManager.BATTERY_PLUGGED_USB -> ChargingSource.USB
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> ChargingSource.WIRELESS
                PLUGGED_DOCK -> ChargingSource.DOCK
                0 -> ChargingSource.NONE
                else -> ChargingSource.UNKNOWN
            },
            status = when (statusRaw) {
                BatteryManager.BATTERY_STATUS_CHARGING -> BatteryStatus.CHARGING
                BatteryManager.BATTERY_STATUS_DISCHARGING -> BatteryStatus.DISCHARGING
                BatteryManager.BATTERY_STATUS_FULL -> BatteryStatus.FULL
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> BatteryStatus.NOT_CHARGING
                else -> BatteryStatus.UNKNOWN
            },
            health = when (healthRaw) {
                BatteryManager.BATTERY_HEALTH_GOOD -> Observed.of(BatteryHealth.GOOD, DataSource.BATTERY_MANAGER)
                BatteryManager.BATTERY_HEALTH_OVERHEAT ->
                    Observed.of(BatteryHealth.OVERHEAT, DataSource.BATTERY_MANAGER)
                BatteryManager.BATTERY_HEALTH_DEAD -> Observed.of(BatteryHealth.DEAD, DataSource.BATTERY_MANAGER)
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE ->
                    Observed.of(BatteryHealth.OVER_VOLTAGE, DataSource.BATTERY_MANAGER)
                BatteryManager.BATTERY_HEALTH_COLD -> Observed.of(BatteryHealth.COLD, DataSource.BATTERY_MANAGER)
                BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE ->
                    Observed.of(BatteryHealth.UNSPECIFIED_FAILURE, DataSource.BATTERY_MANAGER)
                else -> Observed.notPresent("This device does not report a battery health state")
            },
            // 0.1 °C units. Anything outside -30 °C..90 °C is a sentinel, not a reading.
            temperatureDeciCelsius = if (temperature != null && temperature in -300..900) {
                Observed.of(temperature, DataSource.BATTERY_MANAGER)
            } else {
                Observed.notPresent("This device does not report battery temperature")
            },
            // Single-cell Li-ion is 2.5–4.5 V; packs go higher, so 1000–20000 mV.
            voltageMilliVolts = if (voltage != null && voltage in 1_000..20_000) {
                Observed.of(voltage, DataSource.BATTERY_MANAGER)
            } else {
                Observed.notPresent("This device does not report battery voltage")
            },
            currentMicroAmps = if (sampleCounters) readCurrentNow() else countersOff,
            chargeCounterMicroAh = if (sampleCounters) {
                readIntProperty(
                    BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER,
                    "charge counter",
                ) { it > 0 }
            } else {
                countersOff
            },
            energyCounterNanoWattHours = if (sampleCounters) readEnergyCounter() else countersOff,
            technology = if (!technology.isNullOrBlank()) {
                Observed.of(technology, DataSource.BATTERY_MANAGER)
            } else {
                Observed.notPresent("This device does not report a battery technology string")
            },
            isPowerSaveMode = try {
                powerManager?.isPowerSaveMode == true
            } catch (t: Throwable) {
                false
            },
            isScreenOn = isScreenOn(),
        )
    }

    /**
     * `BATTERY_PROPERTY_CURRENT_NOW`, in microamps, when the value is credible.
     *
     * A phone draws roughly 50 mA idle to 10 A while fast-charging, i.e. 50 000 to
     * 10 000 000 µA. Devices that do not implement the property return 0 or
     * Integer.MIN_VALUE; devices that report milliamps in this field return
     * suspiciously small magnitudes. Both are reported as unavailable, which is
     * the honest answer, rather than displayed as "0 mA" — a figure a user would
     * reasonably read as "nothing is drawing power".
     */
    private fun readCurrentNow(): Observed<Int> {
        val bm = batteryManager
            ?: return Observed.notPresent("No battery service on this device")
        val raw = try {
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        } catch (t: Throwable) {
            return Observed.Failed("Battery current could not be read", t.message)
        }
        if (raw == 0 || raw == Int.MIN_VALUE) {
            return Observed.notPresent(
                "This device does not report instantaneous battery current",
            )
        }
        val magnitude = kotlin.math.abs(raw)
        if (magnitude < MIN_PLAUSIBLE_CURRENT_UA || magnitude > MAX_PLAUSIBLE_CURRENT_UA) {
            return Observed.Failed(
                "This device reports an implausible battery current, so it is not shown",
                "raw value $raw µA is outside the plausible range",
            )
        }
        // Sign convention is not standardised across OEMs, so the magnitude is
        // reported and the charging state (which *is* reliable) tells the direction.
        return Observed.of(raw, DataSource.BATTERY_MANAGER, Precision.EXACT)
    }

    private fun readEnergyCounter(): Observed<Long> {
        val bm = batteryManager
            ?: return Observed.notPresent("No battery service on this device")
        return try {
            val value = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)
            if (value <= 0L || value == Long.MIN_VALUE) {
                Observed.notPresent("This device does not report an energy counter")
            } else {
                Observed.of(value, DataSource.BATTERY_MANAGER)
            }
        } catch (t: Throwable) {
            Observed.Failed("Energy counter could not be read", t.message)
        }
    }

    private inline fun readIntProperty(
        property: Int,
        label: String,
        plausible: (Int) -> Boolean,
    ): Observed<Int> {
        val bm = batteryManager
            ?: return Observed.notPresent("No battery service on this device")
        return try {
            val value = bm.getIntProperty(property)
            if (value == Int.MIN_VALUE || !plausible(value)) {
                Observed.notPresent("This device does not report a $label")
            } else {
                Observed.of(value, DataSource.BATTERY_MANAGER)
            }
        } catch (t: Throwable) {
            Observed.Failed("Battery $label could not be read", t.message)
        }
    }

    private fun isScreenOn(): Boolean = try {
        powerManager?.isInteractive == true
    } catch (t: Throwable) {
        false
    }

    // -------------------------------------------------------------------- network

    override suspend fun getNetworkInfo(): NetworkInfo = withContext(io) {
        val cm = connectivityManager
        val capabilities = try {
            cm?.getNetworkCapabilities(cm.activeNetwork)
        } catch (t: Throwable) {
            null
        }

        val transport = when {
            capabilities == null -> NetworkTransport.NONE
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkTransport.VPN
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkTransport.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkTransport.CELLULAR
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkTransport.ETHERNET
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> NetworkTransport.BLUETOOTH
            else -> NetworkTransport.OTHER
        }

        // TrafficStats is device-wide and resets at boot; both facts are stated in
        // the UI rather than letting a user read it as this app's traffic.
        //
        // The counters, and the rate derived from them, are what the sampling switch
        // stops. The baseline is dropped at the same time: resuming later must not
        // divide a large byte delta by a small interval and call the result throughput.
        val sampleCounters = samplingPolicy.networkCountersEnabled
        if (!sampleCounters) lastTraffic = null

        val rx = if (sampleCounters) TrafficStats.getTotalRxBytes() else 0L
        val tx = if (sampleCounters) TrafficStats.getTotalTxBytes() else 0L
        val supported = sampleCounters &&
            rx != TrafficStats.UNSUPPORTED.toLong() &&
            tx != TrafficStats.UNSUPPORTED.toLong()

        val nowElapsed = SystemClock.elapsedRealtime()
        val previous = lastTraffic
        if (supported) lastTraffic = TrafficSample(rx, tx, nowElapsed)

        val countersOff = Observed.samplingDisabled(SamplingPolicy.NETWORK_OFF)

        val rates: Pair<Observed<Double>, Observed<Double>> = when {
            !sampleCounters -> countersOff to countersOff
            !supported -> Observed.notPresent("This kernel does not expose interface counters") to
                Observed.notPresent("This kernel does not expose interface counters")
            previous == null -> Observed.Restricted(
                com.processlens.core.common.RestrictionReason.NOT_PRESENT_ON_DEVICE,
                null,
                "Waiting for a second sample — throughput is a rate, not an instant value.",
            ) to Observed.Restricted(
                com.processlens.core.common.RestrictionReason.NOT_PRESENT_ON_DEVICE,
                null,
                "Waiting for a second sample — throughput is a rate, not an instant value.",
            )
            else -> {
                val seconds = (nowElapsed - previous.elapsedMillis) / 1000.0
                if (seconds <= 0.0 || rx < previous.rxBytes || tx < previous.txBytes) {
                    Observed.Failed("Interface counters reset between samples") to
                        Observed.Failed("Interface counters reset between samples")
                } else {
                    Observed.of(
                        (rx - previous.rxBytes) / seconds,
                        DataSource.TRAFFIC_STATS,
                        Precision.SAMPLED,
                    ) to Observed.of(
                        (tx - previous.txBytes) / seconds,
                        DataSource.TRAFFIC_STATS,
                        Precision.SAMPLED,
                    )
                }
            }
        }

        NetworkInfo(
            transport = transport,
            isConnected = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
            isMetered = cm?.let {
                capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
            } ?: false,
            isVpnActive = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true,
            linkDownstreamKbps = capabilities?.linkDownstreamBandwidthKbps
                ?.takeIf { it > 0 }
                ?.let { Observed.of(it, DataSource.CONNECTIVITY, Precision.ESTIMATED) }
                ?: Observed.notPresent("No link bandwidth estimate is reported"),
            linkUpstreamKbps = capabilities?.linkUpstreamBandwidthKbps
                ?.takeIf { it > 0 }
                ?.let { Observed.of(it, DataSource.CONNECTIVITY, Precision.ESTIMATED) }
                ?: Observed.notPresent("No link bandwidth estimate is reported"),
            totalRxBytes = when {
                !sampleCounters -> countersOff
                supported -> Observed.of(rx, DataSource.TRAFFIC_STATS)
                else -> Observed.notPresent("This kernel does not expose interface counters")
            },
            totalTxBytes = when {
                !sampleCounters -> countersOff
                supported -> Observed.of(tx, DataSource.TRAFFIC_STATS)
                else -> Observed.notPresent("This kernel does not expose interface counters")
            },
            rxRateBytesPerSecond = rates.first,
            txRateBytesPerSecond = rates.second,
            perAppAvailable = permissions.hasUsageAccess(),
            interfaceName = Observed.platform(
                "Interface names are not exposed to apps from Android 10 onward",
            ),
        )
    }

    override suspend fun getPerAppNetworkUsage(sinceMillis: Long): Observed<List<AppNetworkUsage>> =
        usageStats.perAppUsage(sinceMillis)

    // --------------------------------------------------------------------- system

    override suspend fun getStorageInfo(): StorageInfo = withContext(io) {
        try {
            val path = context.filesDir
            val stat = StatFs(path.absolutePath)
            StorageInfo(
                totalBytes = stat.blockCountLong * stat.blockSizeLong,
                availableBytes = stat.availableBlocksLong * stat.blockSizeLong,
                volumeLabel = "Internal storage",
            )
        } catch (t: Throwable) {
            StorageInfo(0L, 0L, "Unavailable")
        }
    }

    override suspend fun getDeviceInfo(): DeviceInfo = withContext(io) {
        val am = activityManager
        val amInfo = ActivityManager.MemoryInfo()
        try {
            am?.getMemoryInfo(amInfo)
        } catch (t: Throwable) {
            // Total RAM falls back to /proc/meminfo below.
        }
        val totalRam = if (amInfo.totalMem > 0) {
            amInfo.totalMem
        } else {
            (procFs.readMemInfo().valueOrNull?.get("MemTotal") ?: 0L) * 1024L
        }

        DeviceInfo(
            manufacturer = Build.MANUFACTURER.orEmpty().ifBlank { "Unknown" },
            model = Build.MODEL.orEmpty().ifBlank { "Unknown" },
            device = Build.DEVICE.orEmpty().ifBlank { "Unknown" },
            androidRelease = Build.VERSION.RELEASE.orEmpty().ifBlank { "Unknown" },
            apiLevel = Build.VERSION.SDK_INT,
            securityPatch = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Build.VERSION.SECURITY_PATCH?.takeIf { it.isNotBlank() }
            } else {
                null
            },
            kernelVersion = System.getProperty("os.version")
                ?.takeIf { it.isNotBlank() }
                ?.let { Observed.of(it, DataSource.RUNTIME) }
                ?: Observed.notPresent("Kernel version is not reported"),
            supportedAbis = Build.SUPPORTED_ABIS?.toList().orEmpty(),
            totalRamBytes = totalRam,
            coreCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
            isEmulator = isProbablyEmulator(),
            uptimeMillis = SystemClock.elapsedRealtime(),
        )
    }

    /**
     * Emulator detection, shown as a note because /proc and dumpsys behave
     * differently under emulation and a user comparing results should know.
     * Deliberately conservative: it only reports true on unambiguous markers.
     */
    private fun isProbablyEmulator(): Boolean {
        val fingerprint = Build.FINGERPRINT.orEmpty()
        return fingerprint.startsWith("generic") ||
            fingerprint.startsWith("unknown") ||
            fingerprint.contains("emulator", ignoreCase = true) ||
            Build.MODEL.orEmpty().contains("sdk_gphone", ignoreCase = true) ||
            Build.PRODUCT.orEmpty().let { it == "sdk" || it.startsWith("sdk_") }
    }

    // -------------------------------------------------------------------- threads

    /**
     * Threads of a process. Readable for our own process on every API level, and
     * for others only where the /proc walk works — Section 11's feature degrades
     * to "own process only" rather than to an empty list with no explanation.
     */
    override suspend fun getThreads(pid: Int): Observed<List<ThreadInfo>> = withContext(io) {
        val tids = when (val listed = procFs.listThreadIds(pid)) {
            is Observed.Value -> listed.value
            is Observed.Restricted -> return@withContext listed
            is Observed.Failed -> return@withContext listed
        }
        if (tids.isEmpty()) {
            return@withContext Observed.platform(
                if (pid == ownPid) {
                    "No threads were listed for this process"
                } else {
                    "Android does not expose other processes' threads to normal applications"
                },
                AccessLevel.SHIZUKU,
            )
        }

        val nowElapsed = SystemClock.elapsedRealtime()
        val threads = tids.mapNotNull { tid ->
            val stat = procFs.readThreadStat(pid, tid).valueOrNull ?: return@mapNotNull null
            ThreadInfo(
                tid = tid,
                name = stat.comm,
                state = stat.state,
                // Thread CPU shares the sampler keyed by tid; tids and pids come
                // from the same number space, so there is no collision.
                cpuPercent = cpuSampler.sampleProcess(tid, stat.cpuJiffies, nowElapsed),
                priority = Observed.of(stat.priority, DataSource.PROC_FS),
            )
        }
        if (threads.isEmpty()) {
            Observed.Failed("Thread entries existed but none could be read")
        } else {
            Observed.of(threads, DataSource.PROC_FS)
        }
    }

    // ------------------------------------------------------------------ wakelocks

    /**
     * Wake locks have never been readable by normal apps on any Android version.
     * Section 16 asks for the closest legitimate alternative, which is to state the
     * limitation and name what would lift it — not to infer wake locks from
     * screen-on time, which would be invention.
     */
    override suspend fun getWakeLocks(): Observed<List<WakeLockInfo>> = Observed.platform(
        "Android does not expose other applications' wake locks to normal apps. " +
            "Shizuku or root access allows this to be read from dumpsys power.",
        AccessLevel.SHIZUKU,
    )

    // ------------------------------------------------------------------- services

    /**
     * Running services (Section 19).
     *
     * `getRunningServices` returns only the caller's own services from API 26.
     * Rather than showing an almost-empty list, this reports the restriction and
     * the app inspector separately lists *declared* services from each package's
     * manifest — real data, clearly labelled as "declared, not running".
     */
    override suspend fun getRunningServices(packageName: String?): Observed<List<ServiceInfo>> =
        withContext(io) {
            val am = activityManager
                ?: return@withContext Observed.Failed("No activity service on this device")

            val running = try {
                @Suppress("DEPRECATION")
                am.getRunningServices(MAX_SERVICES)
            } catch (t: Throwable) {
                null
            }

            if (running == null) {
                return@withContext Observed.platform(
                    "Running services could not be read on this device",
                    AccessLevel.SHIZUKU,
                )
            }

            val filtered = running.filter {
                packageName == null || it.service?.packageName == packageName
            }

            // On API 26+ the platform only ever returns our own services. Saying
            // "1 service running" for the whole device would be a lie of omission,
            // so the restriction is returned unless we were asked about ourselves.
            val ownOnly = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                packageName != context.packageName

            if (ownOnly && filtered.none { it.service?.packageName == packageName }) {
                return@withContext Observed.platform(
                    "Since Android 8, an app can only see its own running services. " +
                        "Declared services are listed from the package manifest instead.",
                    AccessLevel.SHIZUKU,
                )
            }

            Observed.of(
                filtered.mapNotNull { info ->
                    val component = info.service ?: return@mapNotNull null
                    ServiceInfo(
                        className = component.className,
                        packageName = component.packageName,
                        processName = info.process ?: component.packageName,
                        pid = if (info.pid > 0) {
                            Observed.of(info.pid, DataSource.ACTIVITY_MANAGER)
                        } else {
                            Observed.notPresent("Service is not currently hosted in a process")
                        },
                        isForeground = info.foreground,
                        clientCount = Observed.of(info.clientCount, DataSource.ACTIVITY_MANAGER),
                        activeSinceMillis = Observed.of(
                            bootWallClockMillis + info.activeSince,
                            DataSource.ACTIVITY_MANAGER,
                        ),
                        isExported = Observed.platform(
                            "Export state comes from the manifest, shown on the component list",
                        ),
                    )
                },
                DataSource.ACTIVITY_MANAGER,
            )
        }

    // ------------------------------------------------------------------ own usage

    /**
     * ProcessLens measuring itself (Section 43). Every figure here is genuinely
     * available on every API level — the sandbox never hides a process from
     * itself — so this is the one observation that is never restricted.
     */
    override suspend fun getOwnResourceUsage(): OwnUsage = withContext(io) {
        val runtime = Runtime.getRuntime()
        val stat = procFs.readProcessStat(ownPid).valueOrNull

        val debugInfo = Debug.MemoryInfo()
        val pssBytes = try {
            Debug.getMemoryInfo(debugInfo)
            debugInfo.totalPss.toLong() * 1024L
        } catch (t: Throwable) {
            -1L
        }

        OwnUsage(
            cpuPercent = stat?.let {
                cpuSampler.sampleProcess(ownPid, it.cpuJiffies, SystemClock.elapsedRealtime())
            } ?: Observed.Failed("Own CPU counters could not be read"),
            memoryBytes = when {
                pssBytes > 0 -> Observed.of(pssBytes, DataSource.DEBUG_MEMINFO)
                stat != null -> Observed.of(stat.rssBytes, DataSource.OWN_PROC)
                else -> Observed.Failed("Own memory could not be read")
            },
            heapUsedBytes = runtime.totalMemory() - runtime.freeMemory(),
            heapMaxBytes = runtime.maxMemory(),
            threadCount = stat?.numThreads ?: Thread.activeCount(),
        )
    }

    private data class TrafficSample(val rxBytes: Long, val txBytes: Long, val elapsedMillis: Long)

    private companion object {
        /** android.os.Process.FIRST_APPLICATION_UID, which is not in the public SDK. */
        const val FIRST_APPLICATION_UID = 10_000

        /** BatteryManager.BATTERY_PLUGGED_DOCK (API 33), hidden below that. */
        const val PLUGGED_DOCK = 8

        const val MIN_PLAUSIBLE_CURRENT_UA = 1_000
        const val MAX_PLAUSIBLE_CURRENT_UA = 15_000_000

        const val MAX_SERVICES = 512

        /** How recent a usage record must be to count as "recently active". */
        const val RECENT_WINDOW_MILLIS = 60L * 60L * 1000L
    }
}
