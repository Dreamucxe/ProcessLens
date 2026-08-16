package com.processlens.domain.model

import com.processlens.core.common.Observed

/** An installed package, as the app inspector (Sections 20–22) sees it. */
data class AppInfo(
    val packageName: String,
    val label: String,
    val versionName: String?,
    val versionCode: Long,
    val targetSdk: Int,
    val minSdk: Observed<Int>,
    val uid: Int,
    val isSystemApp: Boolean,
    val isUpdatedSystemApp: Boolean,
    val isEnabled: Boolean,
    val isDebuggable: Boolean,
    val firstInstallTime: Long,
    val lastUpdateTime: Long,
    /** Installing package (Play, adb, sideload), API 30+ gives an InstallSourceInfo. */
    val installSource: Observed<String>,
    val apkSizeBytes: Observed<Long>,
    val hasLauncherActivity: Boolean,
)

/** Full forensic profile for one package (Section 22). */
data class AppProfile(
    val app: AppInfo,
    val components: AppComponents,
    val permissions: List<PermissionInfo>,
    val runningProcesses: List<ProcessInfo>,
    val runningServices: List<ServiceInfo>,
    val networkUsage: Observed<AppNetworkUsage>,
    val usage: Observed<AppUsageStats>,
    val memoryBytes: Observed<Long>,
    val cpuPercent: Observed<Float>,
) {
    val isRunning: Boolean get() = runningProcesses.isNotEmpty() || runningServices.isNotEmpty()
}

data class AppComponents(
    val activities: List<ComponentEntry>,
    val services: List<ComponentEntry>,
    val receivers: List<ComponentEntry>,
    val providers: List<ComponentEntry>,
) {
    val total: Int get() = activities.size + services.size + receivers.size + providers.size
}

data class ComponentEntry(
    val className: String,
    val isExported: Boolean,
    val isEnabled: Boolean,
    val permission: String?,
    /** Providers only. */
    val authority: String? = null,
    /** Services only: the declared foregroundServiceType, if any. */
    val extra: String? = null,
) {
    val simpleName: String get() = className.substringAfterLast('.')
}

data class ServiceInfo(
    val className: String,
    val packageName: String,
    val processName: String,
    val pid: Observed<Int>,
    val isForeground: Boolean,
    val clientCount: Observed<Int>,
    val activeSinceMillis: Observed<Long>,
    val isExported: Observed<Boolean>,
) {
    val simpleName: String get() = className.substringAfterLast('.')
}

/**
 * One requested permission and its real grant state. [PermissionGrant.RESTRICTED]
 * covers the case Section 21 calls out: held in the manifest and nominally
 * granted, but soft-restricted by the platform so it does not actually work.
 */
data class PermissionInfo(
    val name: String,
    val group: PermissionGroup,
    val grant: PermissionGrant,
    val isDangerous: Boolean,
    val label: String?,
    val description: String?,
    /** True when the platform will never prompt: signature/system-only. */
    val isSignatureLevel: Boolean,
) {
    val shortName: String get() = name.substringAfterLast('.')
}

enum class PermissionGrant(val label: String) {
    GRANTED("Allowed"),
    DENIED("Denied"),
    /** Granted but soft-restricted / appop-revoked, so effectively inert. */
    RESTRICTED("Restricted"),
    /** Normal-protection permission: granted at install, cannot be revoked. */
    AUTO_GRANTED("Granted at install"),
    UNKNOWN("Unknown"),
}

/** Section 21's grouping. Mapped from the platform permission group where one exists. */
enum class PermissionGroup(val label: String) {
    LOCATION("Location"),
    CAMERA("Camera"),
    MICROPHONE("Microphone"),
    STORAGE("Storage"),
    NOTIFICATIONS("Notifications"),
    SENSORS("Sensors"),
    PHONE("Phone"),
    CONTACTS("Contacts"),
    CALENDAR("Calendar"),
    NETWORK("Network"),
    NEARBY_DEVICES("Nearby devices"),
    SYSTEM("System"),
    OTHER("Other"),
    ;

    companion object {
        /**
         * Maps an AOSP permission group or permission name to a display group.
         * Falls back on name inspection because OEMs add their own permissions
         * that carry no standard group.
         */
        fun fromPermission(permission: String, platformGroup: String?): PermissionGroup {
            platformGroup?.substringAfterLast('.')?.let { g ->
                when (g) {
                    "LOCATION" -> return LOCATION
                    "CAMERA" -> return CAMERA
                    "MICROPHONE" -> return MICROPHONE
                    "STORAGE", "READ_MEDIA_VISUAL", "READ_MEDIA_AURAL" -> return STORAGE
                    "NOTIFICATIONS" -> return NOTIFICATIONS
                    "SENSORS", "ACTIVITY_RECOGNITION" -> return SENSORS
                    "PHONE", "CALL_LOG", "SMS" -> return PHONE
                    "CONTACTS" -> return CONTACTS
                    "CALENDAR" -> return CALENDAR
                    "NEARBY_DEVICES" -> return NEARBY_DEVICES
                }
            }
            val n = permission.substringAfterLast('.')
            return when {
                n.contains("LOCATION") -> LOCATION
                n.contains("CAMERA") -> CAMERA
                n.contains("AUDIO") || n.contains("MICROPHONE") -> MICROPHONE
                n.contains("STORAGE") || n.contains("MEDIA") -> STORAGE
                n.contains("NOTIFICATION") -> NOTIFICATIONS
                n.contains("SENSOR") || n.contains("BODY") || n.contains("ACTIVITY_RECOGNITION") -> SENSORS
                n.contains("PHONE") || n.contains("CALL") || n.contains("SMS") -> PHONE
                n.contains("CONTACT") || n.contains("ACCOUNT") -> CONTACTS
                n.contains("CALENDAR") -> CALENDAR
                n.contains("BLUETOOTH") || n.contains("NEARBY") || n.contains("UWB") -> NEARBY_DEVICES
                n.contains("INTERNET") || n.contains("NETWORK") || n.contains("WIFI") -> NETWORK
                n.startsWith("READ_") || n.startsWith("WRITE_") -> SYSTEM
                else -> OTHER
            }
        }
    }
}

data class AppUsageStats(
    val packageName: String,
    val totalForegroundMillis: Long,
    val lastTimeUsed: Long,
    val launchCount: Observed<Int>,
)

/** A WakeLock observation (Section 16), only obtainable with elevated access. */
data class WakeLockInfo(
    val tag: String,
    val packageName: String?,
    val type: String,
    val isActive: Boolean,
    val heldDurationMillis: Observed<Long>,
    val acquireCount: Observed<Int>,
    /** UID the lock is attributed to, from `uid=` or the WorkSource. */
    val uid: Int? = null,
    /**
     * True when a WorkSource named a different UID than the holder — the lock is
     * held by one process *for* another app. Surfaced because attributing it to
     * the holder (usually system_server) would misdirect an investigation.
     */
    val isOnBehalfOfOther: Boolean = false,
)

/**
 * Android's own per-app battery attribution, parsed from `dumpsys batterystats`
 * (Section 17).
 *
 * [milliampHours] is computed by the platform's BatteryStatsService against the
 * device power profile — ProcessLens does not estimate it. That is the only reason
 * this type exists: Section 17 forbids inventing battery figures, so the app either
 * shows the platform's own number or shows nothing.
 */
data class AppBatteryUsage(
    val uid: Int,
    val packageName: String?,
    val appLabel: String?,
    val milliampHours: Double,
    /** The dump's own breakdown string, kept verbatim as evidence. */
    val breakdown: String?,
    val percentOfComputedDrain: Observed<Float>,
) {
    val displayName: String get() = appLabel ?: packageName ?: "UID $uid"
}

/**
 * What ProcessLens itself has recorded about a package over time (Section 22).
 *
 * Every field is nullable and every average is paired with the count it was computed
 * from. That pairing is the point: "12.4% average CPU" means something quite different
 * over three samples than over four hundred, and Section 42 will not let the UI imply
 * continuity it does not have. A package with no recorded samples has no
 * [ObservationHistory] at all rather than one full of zeroes.
 */
data class ObservationHistory(
    val packageName: String,
    val label: String,
    val firstObservedAt: Long,
    val lastObservedAt: Long,
    val observationCount: Int,
    val cpuSampleCount: Int,
    val cpuAverage: Float?,
    val cpuPeak: Float?,
    val memorySampleCount: Int,
    val memoryAverageBytes: Long?,
    val memoryPeakBytes: Long?,
) {
    val hasCpu: Boolean get() = cpuSampleCount > 0 && cpuAverage != null
    val hasMemory: Boolean get() = memorySampleCount > 0 && memoryAverageBytes != null
    val observedWindowMillis: Long get() = (lastObservedAt - firstObservedAt).coerceAtLeast(0L)
}
