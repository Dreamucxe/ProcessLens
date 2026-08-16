package com.processlens.core.permissions

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for what the app is currently permitted to do
 * (Sections 41, 47).
 *
 * Two categories are handled differently:
 *
 *  - *Runtime* permissions (READ_PHONE_STATE, POST_NOTIFICATIONS) are checked with
 *    `checkSelfPermission`.
 *  - *Special access* (usage access, battery-optimisation exemption) is not a
 *    runtime permission at all: it is an appop the user toggles in a dedicated
 *    Settings page. `checkSelfPermission` returns GRANTED for PACKAGE_USAGE_STATS
 *    as soon as it is in the manifest, which is misleading — the appop is what
 *    actually gates the data. So it is queried via [AppOpsManager] instead, which
 *    is the only way to get the true answer.
 */
@Singleton
class PermissionChecker @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * True when the user has granted usage access. Unlocks UsageStatsManager
     * (app state and foreground time) and NetworkStatsManager (per-app bytes).
     *
     * `unsafeCheckOpNoThrow` is the non-deprecated spelling from API 29; the older
     * `checkOpNoThrow` is used below that. Both can throw on OEM builds that have
     * stripped the appop, so the whole thing is guarded.
     */
    fun hasUsageAccess(): Boolean = try {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName,
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName,
            )
        }
        when (mode) {
            AppOpsManager.MODE_ALLOWED -> true
            // MODE_DEFAULT means "fall back to the permission", which for this op
            // means checking whether we hold it as a privileged app. We do not,
            // but checking is cheap and correct.
            AppOpsManager.MODE_DEFAULT -> ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.PACKAGE_USAGE_STATS,
            ) == PackageManager.PERMISSION_GRANTED
            else -> false
        }
    } catch (t: Throwable) {
        false
    }

    fun hasPhoneStatePermission(): Boolean = hasRuntimePermission(
        android.Manifest.permission.READ_PHONE_STATE,
    )

    /**
     * POST_NOTIFICATIONS only exists from API 33. Below that, notifications are
     * granted by default, so the honest answer is "yes" rather than "unknown".
     */
    fun hasNotificationPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasRuntimePermission(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true
        }

    fun hasRuntimePermission(permission: String): Boolean = try {
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        false
    }

    /**
     * Whether the app is exempt from Doze/App Standby. Relevant because a long
     * investigation recording can otherwise be throttled mid-run, which would
     * show up as a gap in the timeline.
     */
    fun isIgnoringBatteryOptimisations(): Boolean = try {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        pm.isIgnoringBatteryOptimizations(context.packageName)
    } catch (t: Throwable) {
        false
    }

    /**
     * Whether QUERY_ALL_PACKAGES is effective. From API 30 package visibility is
     * filtered, and without this the app inspector would silently under-report,
     * so the capability matrix needs to know.
     */
    fun hasFullPackageVisibility(): Boolean = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R -> true
        else -> hasRuntimePermission(android.Manifest.permission.QUERY_ALL_PACKAGES)
    }

    // ------------------------------------------------------------------ intents

    /**
     * Intent to the usage-access settings page. Falls back to the global page,
     * then to app details, because the per-app deep link is missing on some OEM
     * builds and an unresolvable intent would crash the caller.
     */
    fun usageAccessSettingsIntent(): Intent {
        val perApp = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
        if (perApp.resolveActivityInfo(context.packageManager, 0) != null) return perApp

        val global = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        if (global.resolveActivityInfo(context.packageManager, 0) != null) return global

        return appDetailsSettingsIntent()
    }

    fun batteryOptimisationSettingsIntent(): Intent {
        val direct = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        return if (direct.resolveActivityInfo(context.packageManager, 0) != null) {
            direct
        } else {
            appDetailsSettingsIntent()
        }
    }

    fun appDetailsSettingsIntent(): Intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    )

    /** App-details page for *another* package, used by the app inspector. */
    fun appDetailsSettingsIntent(packageName: String): Intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", packageName, null),
    )
}
