package com.processlens.data.repository

import com.processlens.core.common.DefaultDispatcher
import com.processlens.core.common.Observed
import com.processlens.core.system.CompositeSystemObserver
import com.processlens.domain.model.AppBatteryUsage
import com.processlens.domain.model.AppNetworkUsage
import com.processlens.domain.model.DeviceInfo
import com.processlens.domain.model.ProcessInfo
import com.processlens.domain.model.SystemCapabilities
import com.processlens.domain.model.ThreadInfo
import com.processlens.domain.model.WakeLockInfo
import com.processlens.domain.repository.ProcessListResult
import com.processlens.domain.repository.ProcessRepository
import com.processlens.domain.repository.ProcessTreeNode
import com.processlens.domain.repository.SettingsRepository
import com.processlens.domain.repository.SystemRepository
import com.processlens.domain.repository.SystemState
import com.processlens.domain.usecase.ProcessListAssembler
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * System observation for the UI layer (Sections 5–8).
 *
 * The polling loop lives here rather than in a view model so that every screen
 * observing system state shares one cadence and one set of samples. Two screens
 * collecting simultaneously would otherwise each drive their own `/proc` reads,
 * doubling the app's own cost — and each would compute CPU deltas against its own
 * baseline, producing two different answers for the same instant.
 */
@Singleton
class SystemRepositoryImpl @Inject constructor(
    private val observer: CompositeSystemObserver,
    private val settings: SettingsRepository,
    @DefaultDispatcher private val computation: CoroutineDispatcher,
) : SystemRepository {

    private val capabilities = MutableStateFlow<SystemCapabilities?>(null)
    private val capabilityLock = Mutex()

    /**
     * Polls at the user's chosen interval (Section 6: 1/2/5/10 s or manual).
     *
     * `flow {}` makes this cold — nothing runs until a screen collects, and
     * collection stops the instant the screen leaves the composition, which is what
     * `collectAsStateWithLifecycle` guarantees on the UI side.
     */
    override fun observeSystemState(): Flow<SystemState> = flow {
        // Read settings on each iteration so a rate change takes effect immediately
        // rather than at the next app launch.
        while (true) {
            val current = settings.get()
            emit(readSystemState())
            if (!current.refreshRate.isAutomatic) {
                // Manual: emit once and wait for the caller to re-collect.
                break
            }
            kotlinx.coroutines.delay(current.refreshRate.millis)
        }
    }.flowOn(computation)

    override suspend fun readSystemState(): SystemState = withContext(computation) {
        val prefs = settings.get()
        observer.applySettings(prefs)

        // Sequential rather than parallel: these reads share `/proc` and the Binder
        // thread pool, and firing five at once measurably raises the app's own CPU
        // during the sample — the thing Section 43 tells it not to do.
        val cpu = observer.getCpuInfo()
        val memory = observer.getMemoryInfo()
        val battery = observer.getBatteryInfo()
        val network = observer.getNetworkInfo()
        val storage = observer.getStorageInfo()
        val own = observer.getOwnResourceUsage()

        SystemState(
            timestamp = System.currentTimeMillis(),
            cpu = cpu,
            memory = memory,
            battery = battery,
            network = network,
            storage = storage,
            ownUsage = own,
            // The count is a by-product of the process screen's own polling; the
            // dashboard does not walk the table again for a single number.
            processCount = lastKnownProcessCount,
            accessLevelName = observer.accessLevel.label,
        )
    }

    @Volatile
    private var lastKnownProcessCount: Int = 0

    internal fun recordProcessCount(count: Int) {
        lastKnownProcessCount = count
    }

    override fun observeCapabilities(): Flow<SystemCapabilities> =
        capabilities.asStateFlow().filterNotNull().distinctUntilChanged()

    override suspend fun refreshCapabilities(): SystemCapabilities = capabilityLock.withLock {
        val detected = observer.getCapabilities()
        capabilities.value = detected
        detected
    }

    override suspend fun getDeviceInfo(): DeviceInfo = observer.getDeviceInfo()

    override suspend fun getWakeLocks(): Observed<List<WakeLockInfo>> = observer.getWakeLocks()

    override suspend fun getBatteryUsage(): Observed<List<AppBatteryUsage>> =
        observer.getBatteryUsage()

    override suspend fun getPerAppNetworkUsage(sinceMillis: Long): Observed<List<AppNetworkUsage>> =
        observer.getPerAppNetworkUsage(sinceMillis)

    override suspend fun invalidateAccess() {
        observer.invalidate()
        refreshCapabilities()
    }
}

/**
 * The process list (Sections 9–12).
 *
 * Every result carries whether the list is complete. That flag is computed from the
 * discovery methods that actually produced the rows — not from the API level —
 * because a device with elevated access on API 34 has a complete table while a
 * device on API 29 without it does not.
 */
@Singleton
class ProcessRepositoryImpl @Inject constructor(
    private val observer: CompositeSystemObserver,
    private val settings: SettingsRepository,
    private val systemRepository: SystemRepositoryImpl,
    @DefaultDispatcher private val computation: CoroutineDispatcher,
) : ProcessRepository {

    override fun observeProcesses(): Flow<ProcessListResult> = flow {
        while (true) {
            val prefs = settings.get()
            emit(readProcesses())
            if (!prefs.refreshRate.isAutomatic) break
            // Section 43: the process table is the most expensive read, so it polls
            // at a multiple of the cheap gauges' interval rather than in lockstep.
            val interval = prefs.refreshRate.millis *
                prefs.processListPollMultiplier.coerceIn(1, 10)
            kotlinx.coroutines.delay(interval)
        }
    }.flowOn(computation)

    override suspend fun readProcesses(): ProcessListResult = withContext(computation) {
        val prefs = settings.get()
        observer.applySettings(prefs)

        val processes = observer.getProcesses()
            .filter { prefs.showSystemProcesses || !it.isSystem }

        systemRepository.recordProcessCount(processes.size)

        val methods = processes.map { it.discoveredVia }.distinct()
        val completeness = ProcessListAssembler.describe(methods)

        ProcessListResult(
            processes = processes,
            isCompleteList = completeness.isCompleteList,
            discoveryMethods = methods.map { it.label },
            accessLevelName = observer.accessLevel.label,
            limitationNote = completeness.limitationNote,
        )
    }

    override suspend fun getProcess(id: String): ProcessInfo? = withContext(computation) {
        // Re-read rather than cache: a details screen opened on a dead process must
        // discover that, not show a stale row.
        observer.getProcesses().firstOrNull { it.id == id }
    }

    override suspend fun getThreads(pid: Int): Observed<List<ThreadInfo>> = observer.getThreads(pid)

    /**
     * Builds the tree from real `ppid` values only (Section 12).
     *
     * When parent PIDs are not readable this returns [Observed.Restricted] rather
     * than a flat list dressed as a tree, and never groups by package name — an
     * app's processes are siblings under `zygote`, not parents of each other, so
     * inferring hierarchy from names would invent a relationship Android does not
     * expose (Section 42).
     */
    override suspend fun getProcessTree(): Observed<List<ProcessTreeNode>> = withContext(computation) {
        ProcessListAssembler.buildTree(observer.getProcesses())
    }
}
