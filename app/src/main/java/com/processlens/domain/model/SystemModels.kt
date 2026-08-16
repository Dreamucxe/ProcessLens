package com.processlens.domain.model

import com.processlens.core.common.Observed

/**
 * System-wide memory. `totalBytes`/`availableBytes` come from ActivityManager's
 * MemoryInfo, which is always available; the finer breakdown comes from
 * /proc/meminfo, which some hardened kernels restrict, hence the [Observed]s.
 */
data class MemoryInfo(
    val totalBytes: Long,
    val availableBytes: Long,
    val cachedBytes: Observed<Long>,
    val buffersBytes: Observed<Long>,
    val freeBytes: Observed<Long>,
    val swapTotalBytes: Observed<Long>,
    val swapFreeBytes: Observed<Long>,
    /** ActivityManager's low-memory threshold; below this the killer runs. */
    val lowMemoryThresholdBytes: Observed<Long>,
    val isLowMemory: Boolean,
    /** Sum of PSS across app processes, only knowable with elevated access. */
    val appTotalBytes: Observed<Long>,
    val systemTotalBytes: Observed<Long>,
) {
    val usedBytes: Long get() = (totalBytes - availableBytes).coerceAtLeast(0L)
    val usedFraction: Float
        get() = if (totalBytes <= 0) 0f else usedBytes.toFloat() / totalBytes.toFloat()
}

/**
 * CPU. On API 26+ `/proc/stat` is unreadable by apps on most devices (SELinux
 * denies it even though the file mode looks permissive), so [overallPercent] is
 * frequently [Observed.Restricted] and the UI must say so rather than show 0%.
 */
data class CpuInfo(
    val coreCount: Int,
    val overallPercent: Observed<Float>,
    val perCorePercent: Observed<List<Float>>,
    val frequenciesKHz: Observed<List<CoreFrequency>>,
    val loadAverage: Observed<LoadAverage>,
    /** CPU time consumed by ProcessLens itself (Section 43). Always readable. */
    val ownProcessPercent: Observed<Float>,
    val temperatureDeciCelsius: Observed<Int>,
)

data class CoreFrequency(
    val coreIndex: Int,
    val currentKHz: Long,
    val minKHz: Long,
    val maxKHz: Long,
    /** Some cores are offline; the UI shows that rather than 0 MHz. */
    val isOnline: Boolean,
)

data class LoadAverage(val oneMinute: Float, val fiveMinute: Float, val fifteenMinute: Float)

data class StorageInfo(
    val totalBytes: Long,
    val availableBytes: Long,
    val volumeLabel: String,
) {
    val usedBytes: Long get() = (totalBytes - availableBytes).coerceAtLeast(0L)
    val usedFraction: Float
        get() = if (totalBytes <= 0) 0f else usedBytes.toFloat() / totalBytes.toFloat()
}

/**
 * Battery. Every optional figure is [Observed] because OEM support is wildly
 * inconsistent: `CURRENT_NOW` returns 0 on many devices, `voltage` is absent on
 * some, and Section 0.1 explicitly forbids inventing them when missing.
 */
data class BatteryInfo(
    val levelPercent: Int,
    val isCharging: Boolean,
    val chargingSource: ChargingSource,
    val status: BatteryStatus,
    val health: Observed<BatteryHealth>,
    val temperatureDeciCelsius: Observed<Int>,
    val voltageMilliVolts: Observed<Int>,
    val currentMicroAmps: Observed<Int>,
    val chargeCounterMicroAh: Observed<Int>,
    /** Platform's own estimate; only exists on API 28+ and often unset. */
    val energyCounterNanoWattHours: Observed<Long>,
    val technology: Observed<String>,
    val isPowerSaveMode: Boolean,
    val isScreenOn: Boolean,
)

enum class ChargingSource(val label: String) {
    AC("AC"), USB("USB"), WIRELESS("Wireless"), DOCK("Dock"), NONE("Not charging"), UNKNOWN("Unknown")
}

enum class BatteryStatus(val label: String) {
    CHARGING("Charging"), DISCHARGING("Discharging"), FULL("Full"),
    NOT_CHARGING("Not charging"), UNKNOWN("Unknown")
}

enum class BatteryHealth(val label: String) {
    GOOD("Good"), OVERHEAT("Overheating"), DEAD("Dead"),
    OVER_VOLTAGE("Over voltage"), COLD("Cold"), UNSPECIFIED_FAILURE("Failure"), UNKNOWN("Unknown")
}

/**
 * Network state and byte counters.
 *
 * Interface totals come from TrafficStats (always available, but device-wide and
 * reset at boot). Per-app figures come from NetworkStatsManager, which needs the
 * PACKAGE_USAGE_STATS special access — so [perAppAvailable] is false until the
 * user grants it, and the UI shows an explanation instead of an empty list.
 */
data class NetworkInfo(
    val transport: NetworkTransport,
    val isConnected: Boolean,
    val isMetered: Boolean,
    val isVpnActive: Boolean,
    val linkDownstreamKbps: Observed<Int>,
    val linkUpstreamKbps: Observed<Int>,
    val totalRxBytes: Observed<Long>,
    val totalTxBytes: Observed<Long>,
    /** Bytes/second across the sampling interval; null on the first sample. */
    val rxRateBytesPerSecond: Observed<Double>,
    val txRateBytesPerSecond: Observed<Double>,
    val perAppAvailable: Boolean,
    val interfaceName: Observed<String>,
)

enum class NetworkTransport(val label: String) {
    WIFI("Wi-Fi"), CELLULAR("Mobile"), ETHERNET("Ethernet"),
    BLUETOOTH("Bluetooth"), VPN("VPN"), NONE("Offline"), OTHER("Other")
}

data class AppNetworkUsage(
    val uid: Int,
    val packageName: String?,
    val appLabel: String?,
    val rxBytes: Long,
    val txBytes: Long,
    val since: Long,
) {
    val totalBytes: Long get() = rxBytes + txBytes
}

/** Device-level facts that need no permission and never change during a session. */
data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val device: String,
    val androidRelease: String,
    val apiLevel: Int,
    val securityPatch: String?,
    val kernelVersion: Observed<String>,
    val supportedAbis: List<String>,
    val totalRamBytes: Long,
    val coreCount: Int,
    val isEmulator: Boolean,
    /** Millis since boot including deep sleep. */
    val uptimeMillis: Long,
)

/** Threads of a process, where /proc/<pid>/task is readable (Section 11). */
data class ThreadInfo(
    val tid: Int,
    val name: String,
    val state: ProcessState,
    val cpuPercent: Observed<Float>,
    val priority: Observed<Int>,
)
