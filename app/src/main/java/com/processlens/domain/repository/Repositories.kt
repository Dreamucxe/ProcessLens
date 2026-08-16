package com.processlens.domain.repository

import com.processlens.core.common.Observed
import com.processlens.core.system.OwnUsage
import com.processlens.domain.model.AppBatteryUsage
import com.processlens.domain.model.AppComponents
import com.processlens.domain.model.AppInfo
import com.processlens.domain.model.AppNetworkUsage
import com.processlens.domain.model.AppProfile
import com.processlens.domain.model.BatteryInfo
import com.processlens.domain.model.CpuInfo
import com.processlens.domain.model.DeviceInfo
import com.processlens.domain.model.Favorite
import com.processlens.domain.model.FavoriteType
import com.processlens.domain.model.Investigation
import com.processlens.domain.model.InvestigationEvent
import com.processlens.domain.model.InvestigationSummary
import com.processlens.domain.model.MemoryInfo
import com.processlens.domain.model.NetworkInfo
import com.processlens.domain.model.ObservationHistory
import com.processlens.domain.model.PermissionInfo
import com.processlens.domain.model.ProcessInfo
import com.processlens.domain.model.ProcessSnapshot
import com.processlens.domain.model.ServiceInfo
import com.processlens.domain.model.StorageInfo
import com.processlens.domain.model.SystemCapabilities
import com.processlens.domain.model.ThreadInfo
import com.processlens.domain.model.UserSettings
import com.processlens.domain.model.WakeLockInfo
import kotlinx.coroutines.flow.Flow

/**
 * Repository contracts (Section 2's UI → ViewModel → Domain → Repository → System
 * layering).
 *
 * The interfaces live in `domain` and the implementations in `data`, so a view
 * model depends on the abstraction and a test can substitute a fake without a
 * device. Note what is *absent*: no method here accepts a command string, a shell,
 * or a raw file path. The privileged surface stops at the system layer.
 */

interface SystemRepository {
    /**
     * A live system snapshot at the user's chosen refresh rate.
     *
     * Cold flow: collection starts the polling and cancellation stops it, so a
     * backgrounded screen costs nothing (Section 43).
     */
    fun observeSystemState(): Flow<SystemState>

    /** One-shot read, for pull-to-refresh and the manual refresh rate. */
    suspend fun readSystemState(): SystemState

    fun observeCapabilities(): Flow<SystemCapabilities>
    suspend fun refreshCapabilities(): SystemCapabilities

    suspend fun getDeviceInfo(): DeviceInfo
    suspend fun getWakeLocks(): Observed<List<WakeLockInfo>>
    suspend fun getBatteryUsage(): Observed<List<AppBatteryUsage>>
    suspend fun getPerAppNetworkUsage(sinceMillis: Long): Observed<List<AppNetworkUsage>>

    /** Re-resolves the access route after a Shizuku grant or settings change. */
    suspend fun invalidateAccess()
}

/**
 * Everything the dashboard and live monitor need, read in one pass.
 *
 * Grouped into a single object on purpose: the screens show these figures
 * together, and four independent flows would poll the same subsystems at four
 * slightly different instants — producing a dashboard whose CPU and memory
 * readings came from different moments.
 */
data class SystemState(
    val timestamp: Long,
    val cpu: CpuInfo,
    val memory: MemoryInfo,
    val battery: BatteryInfo,
    val network: NetworkInfo,
    val storage: StorageInfo,
    val ownUsage: OwnUsage,
    val processCount: Int,
    val accessLevelName: String,
)

interface ProcessRepository {
    /** Live process list at the configured rate. */
    fun observeProcesses(): Flow<ProcessListResult>

    suspend fun readProcesses(): ProcessListResult

    suspend fun getProcess(id: String): ProcessInfo?

    suspend fun getThreads(pid: Int): Observed<List<ThreadInfo>>

    /**
     * Parent/child structure derived from real `ppid` values (Section 12).
     * Returns [Observed.Restricted] when parent PIDs are not readable — the tree is
     * never inferred from process names, which Section 42 forbids as inventing
     * relationships Android does not expose.
     */
    suspend fun getProcessTree(): Observed<List<ProcessTreeNode>>
}

/**
 * A process list plus the honesty metadata that makes it interpretable.
 *
 * [isCompleteList] is the crucial field: on API 28+ without elevated access the
 * list is *not* the system's process table, and every screen showing it says so.
 */
data class ProcessListResult(
    val processes: List<ProcessInfo>,
    val isCompleteList: Boolean,
    val discoveryMethods: List<String>,
    val accessLevelName: String,
    val limitationNote: String?,
)

data class ProcessTreeNode(
    val process: ProcessInfo,
    val children: List<ProcessTreeNode>,
) {
    val descendantCount: Int
        get() = children.size + children.sumOf { it.descendantCount }
}

interface AppRepository {
    fun observeApps(includeSystem: Boolean): Flow<List<AppInfo>>
    suspend fun getApp(packageName: String): AppInfo?
    suspend fun getComponents(packageName: String): Observed<AppComponents>
    suspend fun getPermissions(packageName: String): Observed<List<PermissionInfo>>
    suspend fun getServices(packageName: String): Observed<List<ServiceInfo>>

    /** The behaviour profile (Section 22), built from recorded observations only. */
    suspend fun getProfile(packageName: String): AppProfile
    fun observeProfile(packageName: String): Flow<AppProfile>

    /**
     * What ProcessLens has recorded about this package across previous sessions, or
     * null when it has never observed it running. Null and "recorded zero" are
     * different answers and the caller must be able to tell them apart (Section 42).
     */
    fun observeHistory(packageName: String): Flow<ObservationHistory?>
}

interface InvestigationRepository {
    fun observeAll(): Flow<List<Investigation>>
    fun observeActive(): Flow<Investigation?>
    fun observeById(id: Long): Flow<Investigation?>
    fun observeEvents(id: Long): Flow<List<InvestigationEvent>>
    fun observeRecentEvents(limit: Int): Flow<List<InvestigationEvent>>

    suspend fun get(id: Long): Investigation?
    suspend fun getSnapshots(id: Long): List<ProcessSnapshot>
    suspend fun getSeries(id: Long): List<SeriesPoint>
    suspend fun getSnapshotAt(id: Long, timestamp: Long): ProcessSnapshot?
    suspend fun buildSummary(id: Long): InvestigationSummary?
    suspend fun delete(id: Long)
    suspend fun rename(id: Long, name: String)

    /** Marks orphaned RECORDING rows as INTERRUPTED after an abnormal exit. */
    suspend fun reconcileStaleRecordings(): Int

    // ---- Write path, used only by the recorder ----

    /** Creates the row and returns its id. */
    suspend fun create(investigation: Investigation): Long

    /**
     * Appends one sample. [cpuProvenance] and [discoveryMethod] are stored with it
     * so a recording stays interpretable later: a snapshot taken under Shizuku and
     * one taken under a restricted normal-access session are not the same evidence,
     * and the export has to be able to say which it was.
     */
    suspend fun appendSnapshot(
        snapshot: ProcessSnapshot,
        cpuProvenance: String,
        discoveryMethod: String,
    ): Long

    suspend fun appendEvents(events: List<InvestigationEvent>)

    /** Closes the recording with a final state and end timestamp. */
    suspend fun finish(id: Long, state: com.processlens.domain.model.InvestigationState)
}

/** One point on a chart. Nullable metrics stay null — never zero-filled. */
data class SeriesPoint(
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
    val ownCpuPercent: Float?,
    val ownMemoryBytes: Long?,
    val processCount: Int,
)

interface SettingsRepository {
    fun observe(): Flow<UserSettings>
    suspend fun get(): UserSettings
    suspend fun update(transform: (UserSettings) -> UserSettings)
    suspend fun markOnboardingComplete()
}

interface FavoritesRepository {
    fun observeAll(): Flow<List<Favorite>>
    fun observeIsFavorite(type: FavoriteType, key: String): Flow<Boolean>
    suspend fun toggle(type: FavoriteType, key: String, label: String): Boolean
    suspend fun remove(type: FavoriteType, key: String)
}
