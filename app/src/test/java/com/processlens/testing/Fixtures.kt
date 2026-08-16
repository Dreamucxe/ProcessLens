package com.processlens.testing

import com.processlens.core.common.DataSource
import com.processlens.core.common.Observed
import com.processlens.core.system.OwnUsage
import com.processlens.domain.model.BatteryInfo
import com.processlens.domain.model.BatteryStatus
import com.processlens.domain.model.ChargingSource
import com.processlens.domain.model.CpuInfo
import com.processlens.domain.model.DeviceInfo
import com.processlens.domain.model.DiscoveryMethod
import com.processlens.domain.model.EventSeverity
import com.processlens.domain.model.EventType
import com.processlens.domain.model.Investigation
import com.processlens.domain.model.InvestigationEvent
import com.processlens.domain.model.InvestigationState
import com.processlens.domain.model.MemoryInfo
import com.processlens.domain.model.NetworkInfo
import com.processlens.domain.model.NetworkTransport
import com.processlens.domain.model.ProcessImportance
import com.processlens.domain.model.ProcessInfo
import com.processlens.domain.model.ProcessSnapshot
import com.processlens.domain.model.ProcessState
import com.processlens.domain.model.SnapshotEntry
import com.processlens.domain.model.StorageInfo
import com.processlens.domain.repository.ProcessListResult
import com.processlens.domain.repository.SystemState

/**
 * Fixtures for the unit tests.
 *
 * Every builder defaults its [Observed] fields to *unavailable*, not to a value. A
 * test that wants a readable CPU figure has to say so, which means a test can never
 * accidentally assert against a default that the app itself would never produce on a
 * restricted device.
 */
object Fixtures {

    /** A fixed instant, so no test depends on the wall clock. */
    const val T0: Long = 1_700_000_000_000L

    fun process(
        name: String,
        packageName: String? = name,
        label: String? = null,
        pid: Observed<Int> = Observed.platform("test: pid withheld"),
        parentPid: Observed<Int> = Observed.platform("test: parent pid withheld"),
        cpu: Observed<Float> = Observed.platform("test: cpu withheld"),
        memory: Observed<Long> = Observed.platform("test: memory withheld"),
        threads: Observed<Int> = Observed.platform("test: threads withheld"),
        startTime: Observed<Long> = Observed.platform("test: start time withheld"),
        importance: ProcessImportance = ProcessImportance.SERVICE,
        isSystem: Boolean = false,
        discoveredVia: DiscoveryMethod = DiscoveryMethod.PROC_WALK,
    ): ProcessInfo = ProcessInfo(
        id = pid.let { observed ->
            if (observed is Observed.Value) ProcessInfo.idForPid(observed.value)
            else ProcessInfo.idForName(name)
        },
        processName = name,
        packageName = packageName,
        appLabel = label,
        pid = pid,
        uid = Observed.platform("test: uid withheld"),
        state = ProcessState.RUNNING,
        importance = importance,
        cpuPercent = cpu,
        memoryBytes = memory,
        threadCount = threads,
        startTimeMillis = startTime,
        isSystem = isSystem,
        parentPid = parentPid,
        discoveredVia = discoveredVia,
    )

    /** Shorthand for a readable value with a plausible provenance. */
    fun <T> value(value: T, source: DataSource = DataSource.PROC_FS): Observed<T> =
        Observed.of(value, source)

    fun investigation(
        id: Long = 1L,
        name: String = "Test recording",
        startedAt: Long = T0,
        endedAt: Long? = T0 + 60_000L,
        state: InvestigationState = InvestigationState.COMPLETED,
        targetPackage: String? = null,
        sampleIntervalMillis: Long = 2_000L,
        snapshotCount: Int = 2,
        eventCount: Int = 1,
        accessLevelName: String = "Normal",
        apiLevel: Int = 34,
        notes: String? = null,
    ): Investigation = Investigation(
        id = id,
        name = name,
        startedAt = startedAt,
        endedAt = endedAt,
        state = state,
        targetPackage = targetPackage,
        sampleIntervalMillis = sampleIntervalMillis,
        eventCount = eventCount,
        snapshotCount = snapshotCount,
        processesObserved = 3,
        accessLevelName = accessLevelName,
        apiLevel = apiLevel,
        deviceLabel = "Test Device",
        notes = notes,
    )

    fun snapshot(
        investigationId: Long = 1L,
        timestamp: Long = T0,
        cpuPercent: Float? = 12.5f,
        memoryUsedBytes: Long = 2_000_000_000L,
        memoryAvailableBytes: Long = 1_000_000_000L,
        batteryLevel: Int = 80,
        batteryTemperatureDeciCelsius: Int? = 305,
        isCharging: Boolean = false,
        isScreenOn: Boolean = true,
        networkRxBytes: Long? = 1_024L,
        networkTxBytes: Long? = 512L,
        processCount: Int = 3,
        ownCpuPercent: Float? = 1.5f,
        ownMemoryBytes: Long? = 40_000_000L,
        entries: List<SnapshotEntry> = emptyList(),
    ): ProcessSnapshot = ProcessSnapshot(
        investigationId = investigationId,
        timestamp = timestamp,
        cpuPercent = cpuPercent,
        memoryUsedBytes = memoryUsedBytes,
        memoryAvailableBytes = memoryAvailableBytes,
        batteryLevel = batteryLevel,
        batteryTemperatureDeciCelsius = batteryTemperatureDeciCelsius,
        isCharging = isCharging,
        isScreenOn = isScreenOn,
        networkRxBytes = networkRxBytes,
        networkTxBytes = networkTxBytes,
        processCount = processCount,
        ownCpuPercent = ownCpuPercent,
        ownMemoryBytes = ownMemoryBytes,
        entries = entries,
    )

    fun deviceInfo(
        manufacturer: String = "TestCo",
        model: String = "Model X",
        androidRelease: String = "14",
        apiLevel: Int = 34,
    ): DeviceInfo = DeviceInfo(
        manufacturer = manufacturer,
        model = model,
        device = "testdevice",
        androidRelease = androidRelease,
        apiLevel = apiLevel,
        securityPatch = null,
        kernelVersion = Observed.platform("test: kernel version withheld"),
        supportedAbis = listOf("arm64-v8a"),
        totalRamBytes = 8_000_000_000L,
        coreCount = 8,
        isEmulator = false,
        uptimeMillis = 3_600_000L,
    )

    /**
     * A whole-system reading.
     *
     * The parameters are exactly the figures a recording samples; everything else is
     * filled in as unavailable, because those are the fields no test asserts on and
     * inventing plausible values for them would make an unrelated regression look fine.
     */
    fun systemState(
        timestamp: Long = T0,
        cpuPercent: Observed<Float> = value(18f),
        memoryTotalBytes: Long = 8_000_000_000L,
        memoryAvailableBytes: Long = 3_000_000_000L,
        batteryLevel: Int = 80,
        batteryTemperature: Observed<Int> = value(305),
        isCharging: Boolean = false,
        isScreenOn: Boolean = true,
        rxBytes: Observed<Long> = value(1_024L),
        txBytes: Observed<Long> = value(512L),
        ownCpuPercent: Observed<Float> = value(1.5f),
        ownMemoryBytes: Observed<Long> = value(40_000_000L),
        processCount: Int = 3,
        accessLevelName: String = "Normal",
    ): SystemState = SystemState(
        timestamp = timestamp,
        cpu = CpuInfo(
            coreCount = 8,
            overallPercent = cpuPercent,
            perCorePercent = Observed.platform("test: per-core withheld"),
            frequenciesKHz = Observed.platform("test: frequencies withheld"),
            loadAverage = Observed.platform("test: load average withheld"),
            ownProcessPercent = ownCpuPercent,
            temperatureDeciCelsius = Observed.platform("test: cpu temperature withheld"),
        ),
        memory = MemoryInfo(
            totalBytes = memoryTotalBytes,
            availableBytes = memoryAvailableBytes,
            cachedBytes = Observed.platform("test: cached withheld"),
            buffersBytes = Observed.platform("test: buffers withheld"),
            freeBytes = Observed.platform("test: free withheld"),
            swapTotalBytes = Observed.platform("test: swap total withheld"),
            swapFreeBytes = Observed.platform("test: swap free withheld"),
            lowMemoryThresholdBytes = Observed.platform("test: threshold withheld"),
            isLowMemory = false,
            appTotalBytes = Observed.platform("test: app total withheld"),
            systemTotalBytes = Observed.platform("test: system total withheld"),
        ),
        battery = BatteryInfo(
            levelPercent = batteryLevel,
            isCharging = isCharging,
            chargingSource = if (isCharging) ChargingSource.AC else ChargingSource.NONE,
            status = if (isCharging) BatteryStatus.CHARGING else BatteryStatus.DISCHARGING,
            health = Observed.platform("test: health withheld"),
            temperatureDeciCelsius = batteryTemperature,
            voltageMilliVolts = Observed.platform("test: voltage withheld"),
            currentMicroAmps = Observed.platform("test: current withheld"),
            chargeCounterMicroAh = Observed.platform("test: charge counter withheld"),
            energyCounterNanoWattHours = Observed.platform("test: energy counter withheld"),
            technology = Observed.platform("test: technology withheld"),
            isPowerSaveMode = false,
            isScreenOn = isScreenOn,
        ),
        network = NetworkInfo(
            transport = NetworkTransport.WIFI,
            isConnected = true,
            isMetered = false,
            isVpnActive = false,
            linkDownstreamKbps = Observed.platform("test: downstream withheld"),
            linkUpstreamKbps = Observed.platform("test: upstream withheld"),
            totalRxBytes = rxBytes,
            totalTxBytes = txBytes,
            rxRateBytesPerSecond = Observed.platform("test: rx rate withheld"),
            txRateBytesPerSecond = Observed.platform("test: tx rate withheld"),
            perAppAvailable = false,
            interfaceName = Observed.platform("test: interface withheld"),
        ),
        storage = StorageInfo(
            totalBytes = 128_000_000_000L,
            availableBytes = 64_000_000_000L,
            volumeLabel = "Internal storage",
        ),
        ownUsage = OwnUsage(
            cpuPercent = ownCpuPercent,
            memoryBytes = ownMemoryBytes,
            heapUsedBytes = 20_000_000L,
            heapMaxBytes = 200_000_000L,
            threadCount = 12,
        ),
        processCount = processCount,
        accessLevelName = accessLevelName,
    )

    fun processListResult(
        processes: List<ProcessInfo> = emptyList(),
        isCompleteList: Boolean = true,
        discoveryMethods: List<String> = listOf(DiscoveryMethod.PROC_WALK.label),
        accessLevelName: String = "Normal",
        limitationNote: String? = null,
    ): ProcessListResult = ProcessListResult(
        processes = processes,
        isCompleteList = isCompleteList,
        discoveryMethods = discoveryMethods,
        accessLevelName = accessLevelName,
        limitationNote = limitationNote,
    )

    fun entry(
        processName: String,
        packageName: String? = processName,
        pid: Int? = 1234,
        cpuPercent: Float? = 5f,
        memoryBytes: Long? = 100_000_000L,
        importance: ProcessImportance = ProcessImportance.SERVICE,
    ): SnapshotEntry = SnapshotEntry(
        processName = processName,
        packageName = packageName,
        pid = pid,
        cpuPercent = cpuPercent,
        memoryBytes = memoryBytes,
        importance = importance,
    )

    fun event(
        investigationId: Long = 1L,
        timestamp: Long = T0,
        type: EventType = EventType.CPU_SPIKE,
        severity: EventSeverity = EventSeverity.WARNING,
        title: String = "CPU spike",
        detail: String = "Rose from 5% to 60%",
        packageName: String? = "com.example.app",
        processName: String? = "com.example.app",
        value: Double? = 60.0,
        previousValue: Double? = 5.0,
        evidence: String = "/proc sampled at 2 s intervals",
    ): InvestigationEvent = InvestigationEvent(
        investigationId = investigationId,
        timestamp = timestamp,
        type = type,
        severity = severity,
        title = title,
        detail = detail,
        packageName = packageName,
        processName = processName,
        value = value,
        previousValue = previousValue,
        evidence = evidence,
    )
}
