package com.processlens.data.repository

import com.processlens.core.common.AccessLevel
import com.processlens.core.common.DataSource
import com.processlens.core.common.DefaultDispatcher
import com.processlens.core.common.Observed
import com.processlens.core.common.map
import com.processlens.core.common.valueOrNull
import com.processlens.core.system.CompositeSystemObserver
import com.processlens.core.system.PackageInspector
import com.processlens.core.system.UsageStatsReader
import com.processlens.data.database.ApplicationProfileDao
import com.processlens.data.database.ApplicationProfileEntity
import com.processlens.domain.model.AppComponents
import com.processlens.domain.model.AppInfo
import com.processlens.domain.model.AppNetworkUsage
import com.processlens.domain.model.AppProfile
import com.processlens.domain.model.AppUsageStats
import com.processlens.domain.model.ObservationHistory
import com.processlens.domain.model.PermissionInfo
import com.processlens.domain.model.ProcessInfo
import com.processlens.domain.model.ServiceInfo
import com.processlens.domain.repository.AppRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Installed applications and their behaviour profiles (Sections 20–22).
 *
 * The profile is assembled from live observation *plus* whatever this app has
 * previously recorded about the package. It never asserts a behaviour it has not
 * seen: an app with no recorded samples gets a profile whose aggregates are absent,
 * and the UI says "no observations recorded yet" rather than showing zeroes that
 * would read as "this app uses no CPU".
 */
@Singleton
class AppRepositoryImpl @Inject constructor(
    private val packages: PackageInspector,
    private val observer: CompositeSystemObserver,
    private val usageStats: UsageStatsReader,
    private val profileDao: ApplicationProfileDao,
    @DefaultDispatcher private val computation: CoroutineDispatcher,
) : AppRepository {

    /**
     * Emits once. The installed-app set changes rarely and enumerating it is
     * expensive (a few hundred `PackageInfo` reads), so polling it would be pure
     * overhead — screens re-collect on resume instead.
     */
    override fun observeApps(includeSystem: Boolean): Flow<List<AppInfo>> = flow {
        emit(packages.getInstalledApps(includeSystem).sortedBy { it.label.lowercase() })
    }.flowOn(computation)

    override suspend fun getApp(packageName: String): AppInfo? = packages.getApp(packageName)

    override suspend fun getComponents(packageName: String): Observed<AppComponents> =
        packages.getComponents(packageName)

    override suspend fun getPermissions(packageName: String): Observed<List<PermissionInfo>> =
        packages.getPermissions(packageName)

    override suspend fun getServices(packageName: String): Observed<List<ServiceInfo>> =
        observer.getRunningServices(packageName)

    override fun observeProfile(packageName: String): Flow<AppProfile> = flow {
        emit(getProfile(packageName))
    }.flowOn(computation)

    override suspend fun getProfile(packageName: String): AppProfile = withContext(computation) {
        val app = packages.getApp(packageName)
            ?: error("Package $packageName is not installed or not visible to this app")

        val components = packages.getComponents(packageName)
        val permissions = packages.getPermissions(packageName)
        val services = observer.getRunningServices(packageName)

        // Processes belonging to this package, matched by package name and by the
        // "pkg:process" naming convention Android uses for extra processes. Matching
        // on UID as well would be stronger, but UID is itself often unreadable.
        val processes = observer.getProcesses().filter { it.belongsTo(packageName) }

        val network = usageStats.usageForUid(app.uid, System.currentTimeMillis() - DAY_MILLIS)
        val usage = recentUsageFor(packageName)

        // Live figures aggregated across every process the app is running in — an
        // app with three processes uses the sum, not whichever one we saw first.
        val memory = sumObserved(processes.map { it.memoryBytes })
        val cpu = sumObservedFloat(processes.map { it.cpuPercent })

        // Record what we just saw, so the profile deepens with use.
        recordObservation(app, cpu.valueOrNull, memory.valueOrNull)

        AppProfile(
            app = app,
            components = components.valueOrNull ?: AppComponents(emptyList(), emptyList(), emptyList(), emptyList()),
            permissions = permissions.valueOrNull.orEmpty(),
            runningProcesses = processes,
            runningServices = services.valueOrNull.orEmpty(),
            networkUsage = network,
            usage = usage,
            memoryBytes = memory,
            cpuPercent = cpu,
        )
    }

    /** The stored rolling profile, if any observations have been recorded. */
    suspend fun getRecordedProfile(packageName: String): ApplicationProfileEntity? =
        profileDao.get(packageName)

    override fun observeHistory(packageName: String): Flow<ObservationHistory?> =
        profileDao.observe(packageName).map { entity -> entity?.toHistory() }

    private suspend fun recentUsageFor(packageName: String): Observed<AppUsageStats> {
        val all = usageStats.recentlyUsedPackages()
        return when (all) {
            is Observed.Value -> {
                val match = all.value.firstOrNull { it.packageName == packageName }
                if (match != null) {
                    Observed.of(match, all.source, all.precision)
                } else {
                    // Usage access *is* granted and the app simply has no usage in
                    // the window. That is a real answer, not a restriction.
                    Observed.of(
                        AppUsageStats(
                            packageName = packageName,
                            totalForegroundMillis = 0L,
                            lastTimeUsed = 0L,
                            launchCount = Observed.platform("Launch counts are not exposed on this API level"),
                        ),
                        all.source,
                        all.precision,
                    )
                }
            }
            is Observed.Restricted -> all
            is Observed.Failed -> all
        }
    }

    /**
     * Folds this observation into the package's rolling aggregates.
     *
     * A null sample is skipped rather than counted as zero — averaging in a
     * "not measurable" reading as 0% would drag the average toward a number the
     * device never reported.
     */
    private suspend fun recordObservation(app: AppInfo, cpu: Float?, memory: Long?) {
        if (cpu == null && memory == null) return
        val now = System.currentTimeMillis()
        val existing = profileDao.get(app.packageName)

        val cpuCount = (existing?.cpuSampleCount ?: 0) + if (cpu != null) 1 else 0
        val memCount = (existing?.memorySampleCount ?: 0) + if (memory != null) 1 else 0

        profileDao.upsert(
            ApplicationProfileEntity(
                id = existing?.id ?: 0,
                packageName = app.packageName,
                label = app.label,
                firstObservedAt = existing?.firstObservedAt ?: now,
                lastObservedAt = now,
                observationCount = (existing?.observationCount ?: 0) + 1,
                cpuSampleCount = cpuCount,
                cpuAverage = rollingAverage(existing?.cpuAverage, existing?.cpuSampleCount ?: 0, cpu),
                cpuPeak = maxOfNullable(existing?.cpuPeak, cpu),
                memorySampleCount = memCount,
                memoryAverageBytes = rollingAverageLong(
                    existing?.memoryAverageBytes,
                    existing?.memorySampleCount ?: 0,
                    memory,
                ),
                memoryPeakBytes = maxOfNullableLong(existing?.memoryPeakBytes, memory),
                foregroundMillis = existing?.foregroundMillis,
                wakeLockCount = existing?.wakeLockCount,
                networkBytes = existing?.networkBytes,
                notes = existing?.notes,
            ),
        )
    }

    private companion object {
        const val DAY_MILLIS = 24L * 60L * 60L * 1000L
    }
}

/** True when this process belongs to [packageName], by name or by declared package. */
private fun ProcessInfo.belongsTo(packageName: String): Boolean =
    this.packageName == packageName ||
        processName == packageName ||
        processName.startsWith("$packageName:")

/**
 * Maps the stored row to the domain type.
 *
 * The averages are carried across as-is together with their sample counts; nothing is
 * recomputed or filled in here, so a row recorded from three samples still says three.
 */
private fun ApplicationProfileEntity.toHistory(): ObservationHistory = ObservationHistory(
    packageName = packageName,
    label = label,
    firstObservedAt = firstObservedAt,
    lastObservedAt = lastObservedAt,
    observationCount = observationCount,
    cpuSampleCount = cpuSampleCount,
    cpuAverage = cpuAverage,
    cpuPeak = cpuPeak,
    memorySampleCount = memorySampleCount,
    memoryAverageBytes = memoryAverageBytes,
    memoryPeakBytes = memoryPeakBytes,
)

/**
 * Sums a set of [Observed] figures, preserving unavailability.
 *
 * If nothing is measurable the sum is [Observed.Restricted], not 0 — the
 * difference between "this app used no memory" and "we could not measure it" is
 * the entire point of Section 42.
 */
private fun sumObserved(values: List<Observed<Long>>): Observed<Long> {
    val present = values.mapNotNull { it.valueOrNull }
    if (present.isEmpty()) {
        return values.firstOrNull()?.let { first ->
            when (first) {
                is Observed.Restricted -> first
                is Observed.Failed -> first
                is Observed.Value -> Observed.of(first.value, first.source, first.precision)
            }
        } ?: Observed.platform("No running process was observed for this application")
    }
    val source = values.firstNotNullOfOrNull { (it as? Observed.Value)?.source } ?: DataSource.PROC_FS
    return Observed.of(present.sum(), source)
}

private fun sumObservedFloat(values: List<Observed<Float>>): Observed<Float> {
    val present = values.mapNotNull { it.valueOrNull }
    if (present.isEmpty()) {
        return values.firstOrNull()?.let { first ->
            when (first) {
                is Observed.Restricted -> first
                is Observed.Failed -> first
                is Observed.Value -> Observed.of(first.value, first.source, first.precision)
            }
        } ?: Observed.platform("No running process was observed for this application")
    }
    val source = values.firstNotNullOfOrNull { (it as? Observed.Value)?.source } ?: DataSource.PROC_FS
    return Observed.of(present.sum(), source)
}

private fun rollingAverage(previous: Float?, previousCount: Int, sample: Float?): Float? {
    if (sample == null) return previous
    if (previous == null || previousCount <= 0) return sample
    return ((previous * previousCount) + sample) / (previousCount + 1)
}

private fun rollingAverageLong(previous: Long?, previousCount: Int, sample: Long?): Long? {
    if (sample == null) return previous
    if (previous == null || previousCount <= 0) return sample
    return ((previous * previousCount) + sample) / (previousCount + 1)
}

private fun maxOfNullable(a: Float?, b: Float?): Float? = when {
    a == null -> b
    b == null -> a
    else -> maxOf(a, b)
}

private fun maxOfNullableLong(a: Long?, b: Long?): Long? = when {
    a == null -> b
    b == null -> a
    else -> maxOf(a, b)
}
