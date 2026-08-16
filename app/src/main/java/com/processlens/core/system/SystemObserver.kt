package com.processlens.core.system

import com.processlens.core.common.AccessLevel
import com.processlens.core.common.Observed
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
import com.processlens.domain.model.WakeLockInfo

/**
 * The system observation abstraction required by Section 46.
 *
 * Three implementations exist — [StandardAndroidObserver], [ShizukuObserver] and
 * [RootObserver] — and they are composed by `CompositeSystemObserver`, which asks
 * the most capable available observer first and falls back. The UI layer only
 * ever sees this interface, so no screen can reach a shell command or a
 * privileged API directly.
 *
 * Every method is `suspend` and every implementation confines itself to an IO
 * dispatcher, so no observation can block a frame (Section 43).
 */
interface SystemObserver {

    /** Which privilege tier this observer speaks for. */
    val accessLevel: AccessLevel

    /**
     * Whether this observer can currently do anything at all. Shizuku and root
     * observers return false when their service is absent, and the composite
     * skips them without cost.
     */
    suspend fun isAvailable(): Boolean

    suspend fun getProcesses(): List<ProcessInfo>

    suspend fun getMemoryInfo(): MemoryInfo

    suspend fun getBatteryInfo(): BatteryInfo

    suspend fun getNetworkInfo(): NetworkInfo

    suspend fun getCapabilities(): SystemCapabilities

    // --- Beyond the Section 46 minimum, but part of the same abstraction so the
    // --- UI never needs a second, privileged path.

    suspend fun getCpuInfo(): CpuInfo

    suspend fun getStorageInfo(): StorageInfo

    suspend fun getDeviceInfo(): DeviceInfo

    /** Threads of one process (Section 11). Restricted for other apps on API 29+. */
    suspend fun getThreads(pid: Int): Observed<List<ThreadInfo>>

    /** Held wake locks (Section 16). Requires elevated access on every API level. */
    suspend fun getWakeLocks(): Observed<List<WakeLockInfo>>

    /** Running services (Section 19). Own-package only without elevated access. */
    suspend fun getRunningServices(packageName: String?): Observed<List<ServiceInfo>>

    /** Per-app network bytes (Section 18). Needs usage access. */
    suspend fun getPerAppNetworkUsage(sinceMillis: Long): Observed<List<AppNetworkUsage>>

    /**
     * ProcessLens' own CPU and memory cost (Section 43). Always available — this
     * is the one process the sandbox never hides from us — so it is not
     * [Observed]-wrapped at the interface level.
     */
    suspend fun getOwnResourceUsage(): OwnUsage
}

/**
 * Self-measurement. Shown in the UI so the tool is accountable for its own
 * overhead rather than exempting itself from the scrutiny it applies to others.
 */
data class OwnUsage(
    val cpuPercent: Observed<Float>,
    val memoryBytes: Observed<Long>,
    val heapUsedBytes: Long,
    val heapMaxBytes: Long,
    val threadCount: Int,
) {
    val heapFraction: Float
        get() = if (heapMaxBytes <= 0) 0f else heapUsedBytes.toFloat() / heapMaxBytes.toFloat()
}
