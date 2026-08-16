package com.processlens.core.system

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import com.processlens.core.common.DataSource
import com.processlens.core.common.IoDispatcher
import com.processlens.core.common.Observed
import com.processlens.core.common.Precision
import com.processlens.core.permissions.PermissionChecker
import com.processlens.domain.model.AppNetworkUsage
import com.processlens.domain.model.AppUsageStats
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Usage-access-gated observation: app usage stats and per-app network bytes.
 *
 * These two live together because they share one gate — the PACKAGE_USAGE_STATS
 * appop the user grants in Settings — and because from API 28 onward they are the
 * *only* legitimate way an ordinary app can learn anything about other apps'
 * activity. When `getRunningAppProcesses()` stopped returning other processes,
 * usage stats became the honest substitute, and this class is careful to label
 * what it produces as recently-active *packages*, never as a process table.
 */
@Singleton
class UsageStatsReader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissions: PermissionChecker,
    private val packages: PackageInspector,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    private val usageStatsManager: UsageStatsManager?
        get() = try {
            context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        } catch (t: Throwable) {
            null
        }

    private val networkStatsManager: NetworkStatsManager?
        get() = try {
            context.getSystemService(Context.NETWORK_STATS_SERVICE) as? NetworkStatsManager
        } catch (t: Throwable) {
            null
        }

    /**
     * Packages used within [windowMillis], most-recent first.
     *
     * `queryUsageStats` buckets by day/week/month; `INTERVAL_BEST` lets the
     * platform pick, and the result is filtered to the requested window because
     * the returned bucket is usually wider than asked for.
     */
    suspend fun recentlyUsedPackages(windowMillis: Long = DEFAULT_WINDOW): Observed<List<AppUsageStats>> =
        withContext(io) {
            if (!permissions.hasUsageAccess()) {
                return@withContext Observed.needsPermission(
                    "Usage access has not been granted, so recently-active apps cannot be listed.",
                )
            }
            val manager = usageStatsManager
                ?: return@withContext Observed.notPresent("This device has no usage stats service")

            try {
                val now = System.currentTimeMillis()
                val stats = manager.queryUsageStats(
                    UsageStatsManager.INTERVAL_BEST,
                    now - windowMillis,
                    now,
                )
                if (stats.isNullOrEmpty()) {
                    // An empty result with the permission held is a real answer on a
                    // freshly-booted device, not a failure.
                    return@withContext Observed.of(emptyList(), DataSource.USAGE_STATS)
                }

                // The same package appears once per bucket; fold to the latest.
                val merged = HashMap<String, AppUsageStats>(stats.size)
                for (s in stats) {
                    val pkg = s.packageName ?: continue
                    if (s.lastTimeUsed <= 0 && s.totalTimeInForeground <= 0) continue
                    val existing = merged[pkg]
                    merged[pkg] = AppUsageStats(
                        packageName = pkg,
                        totalForegroundMillis = (existing?.totalForegroundMillis ?: 0L) +
                            s.totalTimeInForeground,
                        lastTimeUsed = maxOf(existing?.lastTimeUsed ?: 0L, s.lastTimeUsed),
                        launchCount = launchCountOf(s),
                    )
                }
                Observed.of(
                    merged.values.sortedByDescending { it.lastTimeUsed },
                    DataSource.USAGE_STATS,
                )
            } catch (t: Throwable) {
                Observed.Failed("Usage statistics could not be read", t.message)
            }
        }

    /**
     * Launch count is only on `UsageStats` from API 28 as a hidden field; the
     * public API never exposes it, so it is reported as unavailable rather than
     * reflected out of a private field.
     */
    private fun launchCountOf(@Suppress("UNUSED_PARAMETER") s: android.app.usage.UsageStats): Observed<Int> =
        Observed.platform("Launch counts are not exposed by the public usage stats API")

    /**
     * Foreground/background transitions in a window, which is how an
     * investigation learns that an app came to the foreground at a given moment
     * (Section 13's "process started" events, honestly labelled).
     */
    suspend fun usageEvents(sinceMillis: Long): Observed<List<UsageTransition>> = withContext(io) {
        if (!permissions.hasUsageAccess()) {
            return@withContext Observed.needsPermission("Usage access has not been granted.")
        }
        val manager = usageStatsManager
            ?: return@withContext Observed.notPresent("This device has no usage stats service")

        try {
            val now = System.currentTimeMillis()
            val events = manager.queryEvents(sinceMillis, now)
            val out = ArrayList<UsageTransition>()
            val event = UsageEvents.Event()
            while (events != null && events.hasNextEvent()) {
                events.getNextEvent(event)
                val kind = when (event.eventType) {
                    // ACTIVITY_RESUMED/PAUSED are the API 29+ names for
                    // MOVE_TO_FOREGROUND/BACKGROUND; both constants have the same
                    // numeric values, so one branch covers every API level.
                    UsageEvents.Event.ACTIVITY_RESUMED -> UsageTransitionKind.FOREGROUND
                    UsageEvents.Event.ACTIVITY_PAUSED -> UsageTransitionKind.BACKGROUND
                    UsageEvents.Event.ACTIVITY_STOPPED -> UsageTransitionKind.STOPPED
                    else -> null
                } ?: continue
                val pkg = event.packageName ?: continue
                out += UsageTransition(pkg, event.timeStamp, kind)
            }
            Observed.of(out, DataSource.USAGE_STATS)
        } catch (t: Throwable) {
            Observed.Failed("Usage events could not be read", t.message)
        }
    }

    // ------------------------------------------------------------------ network

    /**
     * Per-app network bytes since [sinceMillis] (Section 18).
     *
     * Wi-Fi and mobile are queried separately because `NetworkStatsManager` keys
     * on transport, and mobile additionally needs a subscriber id on API 26–28 —
     * which requires READ_PHONE_STATE. When that permission is absent the method
     * still returns Wi-Fi figures and the caller labels them as Wi-Fi-only, which
     * is the "closest legitimate alternative" Section 59 asks for rather than a
     * blank screen.
     */
    suspend fun perAppUsage(sinceMillis: Long): Observed<List<AppNetworkUsage>> = withContext(io) {
        if (!permissions.hasUsageAccess()) {
            return@withContext Observed.needsPermission(
                "Per-app network statistics require usage access.",
            )
        }
        val manager = networkStatsManager
            ?: return@withContext Observed.notPresent("This device has no network stats service")

        val now = System.currentTimeMillis()
        val totals = HashMap<Int, LongArray>()

        var anySucceeded = false
        var lastError: String? = null

        for (transport in intArrayOf(ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_MOBILE)) {
            // Mobile attribution needs a subscriber id we cannot obtain without
            // READ_PHONE_STATE; passing null is documented as "all subscribers" and
            // works when the permission is held.
            if (transport == ConnectivityManager.TYPE_MOBILE &&
                !permissions.hasPhoneStatePermission()
            ) {
                continue
            }
            try {
                @Suppress("DEPRECATION")
                val summary = manager.querySummary(transport, null, sinceMillis, now)
                summary.use { stats ->
                    val bucket = NetworkStats.Bucket()
                    while (stats.hasNextBucket()) {
                        stats.getNextBucket(bucket)
                        val slot = totals.getOrPut(bucket.uid) { LongArray(2) }
                        slot[0] += bucket.rxBytes
                        slot[1] += bucket.txBytes
                    }
                }
                anySucceeded = true
            } catch (se: SecurityException) {
                lastError = "Permission denied by the platform: ${se.message}"
            } catch (t: Throwable) {
                lastError = t.message
            }
        }

        if (!anySucceeded) {
            return@withContext Observed.Failed(
                "Network statistics could not be read",
                lastError,
            )
        }

        val list = totals.entries.mapNotNull { (uid, bytes) ->
            if (bytes[0] == 0L && bytes[1] == 0L) return@mapNotNull null
            val pkg = packages.primaryPackageForUid(uid)
            AppNetworkUsage(
                uid = uid,
                packageName = pkg,
                appLabel = pkg?.let { packages.labelFor(it) } ?: syntheticUidLabel(uid),
                rxBytes = bytes[0],
                txBytes = bytes[1],
                since = sinceMillis,
            )
        }.sortedByDescending { it.totalBytes }

        Observed.of(list, DataSource.NETWORK_STATS)
    }

    /**
     * Bytes for one UID. Used by the app profile screen so it does not have to
     * pull the whole device summary for a single package.
     */
    suspend fun usageForUid(uid: Int, sinceMillis: Long): Observed<AppNetworkUsage> = withContext(io) {
        if (!permissions.hasUsageAccess()) {
            return@withContext Observed.needsPermission(
                "Per-app network statistics require usage access.",
            )
        }
        val manager = networkStatsManager
            ?: return@withContext Observed.notPresent("This device has no network stats service")

        val now = System.currentTimeMillis()
        var rx = 0L
        var tx = 0L
        var ok = false
        var lastError: String? = null

        for (transport in intArrayOf(ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_MOBILE)) {
            if (transport == ConnectivityManager.TYPE_MOBILE &&
                !permissions.hasPhoneStatePermission()
            ) {
                continue
            }
            try {
                @Suppress("DEPRECATION")
                manager.queryDetailsForUid(transport, null, sinceMillis, now, uid).use { stats ->
                    val bucket = NetworkStats.Bucket()
                    while (stats.hasNextBucket()) {
                        stats.getNextBucket(bucket)
                        rx += bucket.rxBytes
                        tx += bucket.txBytes
                    }
                }
                ok = true
            } catch (t: Throwable) {
                lastError = t.message
            }
        }

        if (!ok) {
            return@withContext Observed.Failed("Network statistics could not be read", lastError)
        }
        val pkg = packages.primaryPackageForUid(uid)
        Observed.of(
            AppNetworkUsage(
                uid = uid,
                packageName = pkg,
                appLabel = pkg?.let { packages.labelFor(it) } ?: syntheticUidLabel(uid),
                rxBytes = rx,
                txBytes = tx,
                since = sinceMillis,
            ),
            DataSource.NETWORK_STATS,
            Precision.EXACT,
        )
    }

    /**
     * Names the well-known synthetic UIDs the platform reports traffic against, so
     * a row reads "Tethering" rather than "uid 1002" — and so it is never silently
     * attributed to a real app.
     */
    private fun syntheticUidLabel(uid: Int): String = when (uid) {
        android.os.Process.SYSTEM_UID -> "Android system"
        UID_TETHERING -> "Tethering"
        UID_REMOVED -> "Uninstalled apps"
        UID_ALL -> "All apps"
        else -> "UID $uid"
    }

    companion object {
        private val DEFAULT_WINDOW = 24L * 60L * 60L * 1000L

        /** NetworkStats.Bucket synthetic UIDs, which are public but poorly documented. */
        private const val UID_ALL = -1
        private const val UID_REMOVED = -4
        private const val UID_TETHERING = -5
    }
}

data class UsageTransition(
    val packageName: String,
    val timestamp: Long,
    val kind: UsageTransitionKind,
)

enum class UsageTransitionKind(val label: String) {
    FOREGROUND("Came to foreground"),
    BACKGROUND("Went to background"),
    STOPPED("Stopped"),
}
