package com.processlens.core.system

import com.processlens.core.common.AccessLevel
import com.processlens.core.common.Observed
import com.processlens.domain.model.AppBatteryUsage
import com.processlens.domain.model.AppNetworkUsage
import com.processlens.domain.model.BatteryInfo
import com.processlens.domain.model.CpuInfo
import com.processlens.domain.model.DeviceInfo
import com.processlens.domain.model.MemoryInfo
import com.processlens.domain.model.NetworkInfo
import com.processlens.domain.model.ProcessInfo
import com.processlens.domain.model.ServiceInfo
import com.processlens.domain.model.StorageInfo
import com.processlens.domain.model.SystemCapabilities
import com.processlens.domain.model.ThreadInfo
import com.processlens.domain.model.UserSettings
import com.processlens.domain.model.WakeLockInfo
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single [SystemObserver] the rest of the app talks to (Section 46).
 *
 * Everything above this line — repositories, view models, screens — is written
 * against one interface and never learns which observer answered. That is what
 * keeps the rule "the UI should never directly call shell commands or privileged
 * APIs" enforceable rather than aspirational: there is no shell reachable from
 * above this class.
 *
 * Routing is re-evaluated lazily rather than fixed at construction, because access
 * genuinely changes while the app runs: the user grants Shizuku permission, starts
 * the Shizuku service, or revokes it. [invalidate] is called when settings change
 * so the next observation picks the new route.
 */
@Singleton
class CompositeSystemObserver @Inject constructor(
    private val standard: StandardAndroidObserver,
    private val shizuku: ShizukuShell,
    private val root: RootShell,
    private val procFs: ProcFsReader,
    private val packages: PackageInspector,
    private val cpuSamplerFactory: CpuSamplerFactory,
    private val samplingPolicy: SamplingPolicy,
) : SystemObserver {

    private val routeLock = Mutex()

    /** Cached route. Null means "not yet resolved". */
    private var active: SystemObserver? = null

    /** Whether elevated observation is permitted by the user's settings. */
    private var allowShizuku: Boolean = true
    private var allowRoot: Boolean = false

    /**
     * Elevated observers keep their own CPU baselines, because their jiffy figures
     * come from a different source than /proc and mixing the two would produce a
     * nonsense delta on the sample after a route change.
     */
    private val shizukuSampler by lazy { cpuSamplerFactory.create() }
    private val rootSampler by lazy { cpuSamplerFactory.create() }

    override val accessLevel: AccessLevel
        get() = active?.accessLevel ?: AccessLevel.NORMAL

    override suspend fun isAvailable(): Boolean = true

    /**
     * Applies the user's advanced settings (Section 40). Called by the repository
     * layer when settings change; a route resolved under the old settings is
     * discarded rather than left in place.
     *
     * The per-domain sampling switches go the same way, into [SamplingPolicy], which
     * the observers consult when deciding what to actually read. They do not force a
     * route change — switching CPU sampling off does not alter *how* ProcessLens
     * observes, only *what* it observes.
     */
    suspend fun applySettings(settings: UserSettings) = routeLock.withLock {
        samplingPolicy.apply(settings)
        val changed = allowShizuku != settings.shizukuEnabled || allowRoot != settings.rootEnabled
        val rootDisabled = allowRoot && !settings.rootEnabled
        allowShizuku = settings.shizukuEnabled
        allowRoot = settings.rootEnabled
        if (changed) {
            active = null
            // A proven grant survives `invalidate`, by design (defect 4). But the
            // user turning root support *off* is the one case where that proof must
            // not be carried forward: re-enabling it later has to consult their
            // superuser manager afresh rather than silently elevating on a grant
            // recorded under the old setting.
            if (rootDisabled) root.forgetGrant()
            // The access level may now differ, so any refusal remembered under the
            // old route is stale — the next capability refresh re-probes everything.
            procFs.invalidateRestrictions()
        }
    }

    /** Forces re-resolution — after a permission grant, or a manual refresh. */
    suspend fun invalidate() = routeLock.withLock {
        active = null
        root.invalidate()
        // A manual refresh is exactly the moment to re-ask what the current access
        // level can read: a denial cached before the user granted Shizuku or root
        // would otherwise outlive the grant that lifts it.
        procFs.invalidateRestrictions()
    }

    /**
     * Picks the highest access level that actually works right now.
     *
     * Root is preferred over Shizuku only because it strictly dominates it; both
     * are verified by running the read-only [DiagnosticCommand.Probe] rather than
     * by checking whether a binary exists, because a present `su` that denies the
     * request is worse than no `su` at all — it would route every observation
     * through a shell that always fails.
     */
    private suspend fun observer(): SystemObserver {
        active?.let { return it }
        return routeLock.withLock {
            active?.let { return@withLock it }
            val resolved = resolve()
            active = resolved
            resolved
        }
    }

    private suspend fun resolve(): SystemObserver {
        if (allowRoot && root.isAvailable()) {
            return ElevatedObserver(root, standard, packages, rootSampler)
        }
        if (allowShizuku && shizuku.isAvailable()) {
            return ElevatedObserver(shizuku, standard, packages, shizukuSampler)
        }
        return standard
    }

    /**
     * The elevated observer, when one is active. Screens that offer a
     * shell-only observation (per-app battery attribution, platform PSS) ask for it
     * and show their "requires Shizuku or root" state when it is null — rather than
     * silently omitting the section, which a user could not distinguish from
     * "nothing to report".
     */
    suspend fun elevatedOrNull(): ElevatedObserver? = observer() as? ElevatedObserver

    /** Android's own per-app battery attribution, or the reason it is unavailable. */
    suspend fun getBatteryUsage(): Observed<List<AppBatteryUsage>> =
        elevatedOrNull()?.getBatteryUsage() ?: Observed.platform(
            "Android does not expose per-app battery attribution to normal applications. " +
                "Shizuku or root access allows it to be read from the platform's own battery statistics.",
            AccessLevel.SHIZUKU,
        )

    /** Platform-computed PSS per process, or the reason it is unavailable. */
    suspend fun getPssByPid(): Observed<Map<Int, Long>> =
        elevatedOrNull()?.getPssByPid() ?: Observed.platform(
            "Per-process PSS is computed by the platform and only readable through an elevated shell.",
            AccessLevel.SHIZUKU,
        )

    // ------------------------------------------------------------------ delegation

    override suspend fun getProcesses(): List<ProcessInfo> = observer().getProcesses()
    override suspend fun getMemoryInfo(): MemoryInfo = observer().getMemoryInfo()
    override suspend fun getBatteryInfo(): BatteryInfo = observer().getBatteryInfo()
    override suspend fun getNetworkInfo(): NetworkInfo = observer().getNetworkInfo()
    override suspend fun getCpuInfo(): CpuInfo = observer().getCpuInfo()
    override suspend fun getStorageInfo(): StorageInfo = observer().getStorageInfo()
    override suspend fun getDeviceInfo(): DeviceInfo = observer().getDeviceInfo()
    override suspend fun getOwnResourceUsage(): OwnUsage = observer().getOwnResourceUsage()

    override suspend fun getCapabilities(): SystemCapabilities = observer().getCapabilities()

    override suspend fun getThreads(pid: Int): Observed<List<ThreadInfo>> =
        observer().getThreads(pid)

    override suspend fun getWakeLocks(): Observed<List<WakeLockInfo>> = observer().getWakeLocks()

    override suspend fun getRunningServices(packageName: String?): Observed<List<ServiceInfo>> =
        observer().getRunningServices(packageName)

    override suspend fun getPerAppNetworkUsage(sinceMillis: Long): Observed<List<AppNetworkUsage>> =
        observer().getPerAppNetworkUsage(sinceMillis)
}

/**
 * Creates [CpuSampler] instances. A sampler holds per-PID baselines, so it cannot
 * be a singleton shared between observers reading from different sources — the
 * delta between a `/proc` jiffy count and a `ps` CPU time would be meaningless.
 */
@Singleton
class CpuSamplerFactory @Inject constructor() {
    fun create(): CpuSampler = CpuSampler()
}
