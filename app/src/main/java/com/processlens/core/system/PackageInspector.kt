package com.processlens.core.system

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo as PlatformPermissionInfo
import android.content.pm.ServiceInfo as PlatformServiceInfo
import android.graphics.drawable.Drawable
import android.os.Build
import com.processlens.core.common.DataSource
import com.processlens.core.common.IoDispatcher
import com.processlens.core.common.Observed
import com.processlens.domain.model.AppComponents
import com.processlens.domain.model.AppInfo
import com.processlens.domain.model.ComponentEntry
import com.processlens.domain.model.PermissionGrant
import com.processlens.domain.model.PermissionGroup
import com.processlens.domain.model.PermissionInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * All PackageManager access (Sections 20–22).
 *
 * Two performance rules are baked in, because this is the layer most likely to
 * make the app the problem it is investigating (Section 43):
 *
 *  1. **The app list is fetched with minimal flags.** Requesting
 *     `GET_ACTIVITIES or GET_SERVICES or GET_RECEIVERS or GET_PROVIDERS or
 *     GET_PERMISSIONS` for every installed package moves megabytes over Binder
 *     and reliably throws `TransactionTooLargeException` on devices with many
 *     apps. Components are therefore loaded per-package, on demand, only when a
 *     detail screen asks.
 *  2. **Labels and icons are cached.** `loadLabel` hits the APK's resource table
 *     each call; in a scrolling list that is a frame-time killer.
 */
@Singleton
class PackageInspector @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    private val pm: PackageManager get() = context.packageManager

    private val labelCache = ConcurrentHashMap<String, String>()
    private val uidToPackages = ConcurrentHashMap<Int, List<String>>()
    private val appCache = ConcurrentHashMap<String, AppInfo>()

    /**
     * Installed-package names, resolved once. Used to decide whether a process
     * name genuinely maps to a package: from API 30 the platform filters package
     * visibility, so a name that is absent here may exist but be hidden — the
     * caller treats "not in this set" as "no verified link", never as "no such
     * app", which is the difference between omitting a claim and making a false one.
     */
    private val installedNames: Set<String> by lazy {
        try {
            getInstalledPackagesCompat(0).mapTo(HashSet()) { it.packageName }
        } catch (t: Throwable) {
            emptySet()
        }
    }

    /** True only when the package is installed *and visible to this app*. */
    fun isInstalled(packageName: String): Boolean =
        packageName in installedNames || appCache.containsKey(packageName)

    /** Cheap: label only, cached. Falls back to the package name, never to "". */
    fun labelFor(packageName: String): String = labelCache.getOrPut(packageName) {
        try {
            val ai = getApplicationInfoCompat(packageName, 0)
            pm.getApplicationLabel(ai).toString().ifBlank { packageName }
        } catch (t: Throwable) {
            packageName
        }
    }

    /**
     * Icons are deliberately *not* cached here — a Drawable holds a Bitmap, and a
     * map of several hundred would dwarf the app's own memory budget. Compose
     * caches the composed result per list item instead, which is bounded by what
     * is on screen.
     */
    fun iconFor(packageName: String): Drawable? = try {
        pm.getApplicationIcon(packageName)
    } catch (t: Throwable) {
        null
    }

    /** Packages sharing a UID. Needed to attribute network stats, which are per-UID. */
    fun packagesForUid(uid: Int): List<String> = uidToPackages.getOrPut(uid) {
        try {
            pm.getPackagesForUid(uid)?.toList() ?: emptyList()
        } catch (t: Throwable) {
            emptyList()
        }
    }

    fun primaryPackageForUid(uid: Int): String? = packagesForUid(uid).firstOrNull()

    suspend fun getInstalledApps(includeSystem: Boolean = true): List<AppInfo> = withContext(io) {
        val packages: List<PackageInfo> = try {
            getInstalledPackagesCompat(0)
        } catch (t: Throwable) {
            emptyList()
        }

        packages.mapNotNull { pkg ->
            val ai = pkg.applicationInfo ?: return@mapNotNull null
            val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (!includeSystem && isSystem) return@mapNotNull null
            toAppInfo(pkg, ai)
        }
    }

    suspend fun getApp(packageName: String): AppInfo? = withContext(io) {
        appCache[packageName] ?: try {
            val pkg = getPackageInfoCompat(packageName, 0)
            val ai = pkg.applicationInfo ?: return@withContext null
            toAppInfo(pkg, ai)
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Synchronous cache read for hot paths (list rows, process→app attribution).
     * Populates lazily from PackageManager on a miss; the Binder call is cheap for
     * a single package, and the result is memoised so a scrolling list does not
     * repeat it.
     */
    fun getAppCached(packageName: String): AppInfo? = appCache[packageName] ?: try {
        val pkg = getPackageInfoCompat(packageName, 0)
        pkg.applicationInfo?.let { toAppInfo(pkg, it) }
    } catch (t: Throwable) {
        null
    }

    private fun toAppInfo(pkg: PackageInfo, ai: ApplicationInfo): AppInfo {
        val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        val label = try {
            pm.getApplicationLabel(ai).toString().ifBlank { pkg.packageName }
        } catch (t: Throwable) {
            pkg.packageName
        }
        labelCache[pkg.packageName] = label

        val info = AppInfo(
            packageName = pkg.packageName,
            label = label,
            versionName = pkg.versionName,
            versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pkg.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pkg.versionCode.toLong()
            },
            targetSdk = ai.targetSdkVersion,
            // minSdkVersion on ApplicationInfo is API 24+, but it is @hide-adjacent
            // on some OEM builds, so it is read defensively.
            minSdk = try {
                Observed.of(ai.minSdkVersion, DataSource.PACKAGE_MANAGER)
            } catch (t: Throwable) {
                Observed.notPresent("minSdkVersion is not reported on this device")
            },
            uid = ai.uid,
            isSystemApp = isSystem,
            isUpdatedSystemApp = (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0,
            isEnabled = ai.enabled,
            isDebuggable = (ai.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
            firstInstallTime = pkg.firstInstallTime,
            lastUpdateTime = pkg.lastUpdateTime,
            installSource = installSourceOf(pkg.packageName),
            apkSizeBytes = apkSizeOf(ai),
            hasLauncherActivity = try {
                pm.getLaunchIntentForPackage(pkg.packageName) != null
            } catch (t: Throwable) {
                false
            },
        )
        appCache[pkg.packageName] = info
        return info
    }

    /**
     * The installing package. `getInstallSourceInfo` (API 30+) is the supported
     * path; below that `getInstallerPackageName` is the only option and is
     * deprecated rather than absent, so it is used with the suppression.
     */
    private fun installSourceOf(packageName: String): Observed<String> = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val info = pm.getInstallSourceInfo(packageName)
            val installer = info.installingPackageName ?: info.initiatingPackageName
            if (installer == null) {
                Observed.notPresent("No installer recorded (sideloaded or preinstalled)")
            } else {
                Observed.of(labelFor(installer), DataSource.PACKAGE_MANAGER)
            }
        } else {
            @Suppress("DEPRECATION")
            val installer = pm.getInstallerPackageName(packageName)
            if (installer == null) {
                Observed.notPresent("No installer recorded (sideloaded or preinstalled)")
            } else {
                Observed.of(labelFor(installer), DataSource.PACKAGE_MANAGER)
            }
        }
    } catch (t: Throwable) {
        Observed.Failed("Install source could not be read", t.message)
    }

    /**
     * APK size by stat-ing the source APK. Real, not estimated — but it is the
     * base APK only: split APKs and the app's data directory are not included,
     * and the UI labels it accordingly rather than calling it "app size".
     */
    private fun apkSizeOf(ai: ApplicationInfo): Observed<Long> = try {
        val path = ai.sourceDir
        if (path.isNullOrBlank()) {
            Observed.notPresent("No source APK path")
        } else {
            val f = File(path)
            if (f.exists() && f.length() > 0) {
                Observed.of(f.length(), DataSource.PACKAGE_MANAGER)
            } else {
                Observed.platform("APK path is not readable by this app")
            }
        }
    } catch (t: Throwable) {
        Observed.Failed("APK size could not be read", t.message)
    }

    // ------------------------------------------------------------- components

    /**
     * Components of one package (Section 20). `MATCH_DISABLED_COMPONENTS` is
     * included so a disabled component is *listed as disabled* rather than
     * silently missing — an absence the user could not tell from "none declared".
     */
    suspend fun getComponents(packageName: String): Observed<AppComponents> = withContext(io) {
        try {
            val flags = PackageManager.GET_ACTIVITIES or
                PackageManager.GET_SERVICES or
                PackageManager.GET_RECEIVERS or
                PackageManager.GET_PROVIDERS or
                PackageManager.MATCH_DISABLED_COMPONENTS
            val pkg = getPackageInfoCompat(packageName, flags)

            Observed.of(
                AppComponents(
                    activities = pkg.activities?.map { a ->
                        ComponentEntry(
                            className = a.name,
                            isExported = a.exported,
                            isEnabled = a.enabled,
                            permission = a.permission,
                        )
                    }.orEmpty(),
                    services = pkg.services?.map { s ->
                        ComponentEntry(
                            className = s.name,
                            isExported = s.exported,
                            isEnabled = s.enabled,
                            permission = s.permission,
                            extra = foregroundServiceTypeLabel(s),
                        )
                    }.orEmpty(),
                    receivers = pkg.receivers?.map { r ->
                        ComponentEntry(
                            className = r.name,
                            isExported = r.exported,
                            isEnabled = r.enabled,
                            permission = r.permission,
                        )
                    }.orEmpty(),
                    providers = pkg.providers?.map { p ->
                        ComponentEntry(
                            className = p.name,
                            isExported = p.exported,
                            isEnabled = p.enabled,
                            permission = p.readPermission ?: p.writePermission,
                            authority = p.authority,
                        )
                    }.orEmpty(),
                ),
                DataSource.PACKAGE_MANAGER,
            )
        } catch (tle: android.os.TransactionTooLargeException) {
            Observed.Failed("This package declares too many components to read at once")
        } catch (nnf: PackageManager.NameNotFoundException) {
            Observed.notPresent("Package is not installed or is not visible to this app")
        } catch (t: Throwable) {
            Observed.Failed("Components could not be read", t.message)
        }
    }

    /** Decodes the `foregroundServiceType` bitmask declared in the manifest. */
    private fun foregroundServiceTypeLabel(s: PlatformServiceInfo): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val type = try {
            s.foregroundServiceType
        } catch (t: Throwable) {
            return null
        }
        if (type == 0) return null
        val names = buildList {
            if (type and PlatformServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC != 0) add("dataSync")
            if (type and PlatformServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK != 0) add("mediaPlayback")
            if (type and PlatformServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION != 0) add("location")
            if (type and PlatformServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE != 0) add("connectedDevice")
            if (type and PlatformServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION != 0) add("mediaProjection")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (type and PlatformServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA != 0) add("camera")
                if (type and PlatformServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE != 0) add("microphone")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                if (type and PlatformServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH != 0) add("health")
                if (type and PlatformServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE != 0) add("shortService")
                if (type and PlatformServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE != 0) add("specialUse")
            }
        }
        return names.joinToString(", ").ifBlank { null }
    }

    // ------------------------------------------------------------ permissions

    /**
     * Requested permissions with their *real* grant state (Section 21).
     *
     * The grant state comes from `requestedPermissionsFlags`, which is the only
     * accurate source: `checkPermission` answers for the *caller*, not for the
     * inspected package, and a naive implementation that uses it reports this
     * app's own grants against someone else's manifest.
     *
     * `RESTRICTED` is distinguished from `GRANTED` because a soft-restricted
     * permission is held in the manifest and flagged granted, yet the appop
     * behind it is revoked, so it does nothing — the distinction Section 21
     * explicitly asks for.
     */
    suspend fun getPermissions(packageName: String): Observed<List<PermissionInfo>> = withContext(io) {
        try {
            val pkg = getPackageInfoCompat(packageName, PackageManager.GET_PERMISSIONS)
            val requested = pkg.requestedPermissions
            if (requested == null || requested.isEmpty()) {
                return@withContext Observed.of(emptyList(), DataSource.PACKAGE_MANAGER)
            }
            val flags = pkg.requestedPermissionsFlags

            val list = requested.mapIndexed { index, name ->
                val flag = flags?.getOrNull(index) ?: 0
                val isGranted = (flag and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0

                // Protection level tells us whether a denial is even meaningful:
                // a `normal` permission is granted at install and cannot be revoked.
                val platform: PlatformPermissionInfo? = try {
                    pm.getPermissionInfo(name, 0)
                } catch (t: Throwable) {
                    null
                }
                val protection = platform?.let { protectionOf(it) } ?: ProtectionLevel.UNKNOWN

                PermissionInfo(
                    name = name,
                    group = PermissionGroup.fromPermission(name, platform?.group),
                    grant = when {
                        isGranted && isSoftRestricted(flag) -> PermissionGrant.RESTRICTED
                        isGranted && protection == ProtectionLevel.NORMAL -> PermissionGrant.AUTO_GRANTED
                        isGranted -> PermissionGrant.GRANTED
                        protection == ProtectionLevel.SIGNATURE -> PermissionGrant.DENIED
                        else -> PermissionGrant.DENIED
                    },
                    isDangerous = protection == ProtectionLevel.DANGEROUS,
                    label = platform?.loadLabel(pm)?.toString(),
                    description = try {
                        platform?.loadDescription(pm)?.toString()
                    } catch (t: Throwable) {
                        null
                    },
                    isSignatureLevel = protection == ProtectionLevel.SIGNATURE,
                )
            }.sortedWith(
                compareBy({ it.group.ordinal }, { !it.isDangerous }, { it.shortName }),
            )
            Observed.of(list, DataSource.PACKAGE_MANAGER)
        } catch (nnf: PackageManager.NameNotFoundException) {
            Observed.notPresent("Package is not installed or is not visible to this app")
        } catch (t: Throwable) {
            Observed.Failed("Permissions could not be read", t.message)
        }
    }

    /**
     * `REQUESTED_PERMISSION_IMPLICIT` (API 31+) marks a permission the platform
     * granted implicitly and soft-restricted. The constant is hidden, so the bit
     * is tested directly with the value from AOSP — guarded by an API check so it
     * is never applied on a version where the bit means something else.
     */
    private fun isSoftRestricted(flag: Int): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && (flag and IMPLICIT_FLAG) != 0

    private enum class ProtectionLevel { NORMAL, DANGEROUS, SIGNATURE, UNKNOWN }

    private fun protectionOf(info: PlatformPermissionInfo): ProtectionLevel {
        val base = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.protection
        } else {
            @Suppress("DEPRECATION")
            info.protectionLevel and PlatformPermissionInfo.PROTECTION_MASK_BASE
        }
        return when (base) {
            PlatformPermissionInfo.PROTECTION_NORMAL -> ProtectionLevel.NORMAL
            PlatformPermissionInfo.PROTECTION_DANGEROUS -> ProtectionLevel.DANGEROUS
            PlatformPermissionInfo.PROTECTION_SIGNATURE,
            PlatformPermissionInfo.PROTECTION_SIGNATURE_OR_SYSTEM,
            -> ProtectionLevel.SIGNATURE
            else -> ProtectionLevel.UNKNOWN
        }
    }

    // -------------------------------------------------------- compat wrappers

    /**
     * `PackageInfoFlags` replaced the int overloads in API 33 and the old ones are
     * deprecated (not removed). Wrapped once here so the deprecation suppression
     * lives in a single place instead of at twelve call sites.
     */
    private fun getPackageInfoCompat(packageName: String, flags: Int): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, flags)
        }

    private fun getInstalledPackagesCompat(flags: Int): List<PackageInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(flags)
        }

    private fun getApplicationInfoCompat(packageName: String, flags: Int): ApplicationInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getApplicationInfo(packageName, flags)
        }

    fun clearCaches() {
        labelCache.clear()
        uidToPackages.clear()
        appCache.clear()
    }

    private companion object {
        /** AOSP PackageInfo.REQUESTED_PERMISSION_IMPLICIT, hidden from the SDK. */
        const val IMPLICIT_FLAG = 0x00000004
    }
}
