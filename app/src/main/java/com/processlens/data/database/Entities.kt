package com.processlens.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entities (Section 44).
 *
 * These are deliberately *separate* from the domain models. A domain
 * `ProcessInfo` holds `Observed<Int>` values whose restriction reasons are
 * user-facing text; persisting that shape would freeze today's wording into the
 * database and make every copy change a migration. Instead the entities store the
 * raw reading plus a compact provenance code, and the mappers rebuild the
 * `Observed` wrapper on the way out — so a recorded investigation replayed a month
 * later still says *why* a figure was missing, in the current wording.
 */

@Entity(
    tableName = "investigations",
    indices = [Index("startedAt")],
)
data class InvestigationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val startedAt: Long,
    val endedAt: Long?,
    val state: String,
    /** Package under investigation, or null for a whole-device investigation. */
    val targetPackage: String?,
    val accessLevel: String,
    val apiLevel: Int,
    val deviceModel: String,
    /** Sampling interval actually used, so a replay knows the real resolution. */
    val sampleIntervalMillis: Long,
    val notes: String?,
)

@Entity(
    tableName = "investigation_events",
    foreignKeys = [
        ForeignKey(
            entity = InvestigationEntity::class,
            parentColumns = ["id"],
            childColumns = ["investigationId"],
            // Events are meaningless without their investigation, and orphan rows
            // would silently grow the database.
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("investigationId"), Index("timestamp")],
)
data class InvestigationEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val investigationId: Long,
    val timestamp: Long,
    val type: String,
    val severity: String,
    val title: String,
    val detail: String,
    /**
     * The measurement that produced this event, verbatim — e.g.
     * "CPU 84.2% (sampled, /proc) at 14:03:12, threshold 80%".
     *
     * Section 15 forbids claiming causation, so an event stores the observation it
     * was derived from and the UI presents that alongside the description. A user
     * can always see the number behind the claim.
     */
    val evidence: String,
    val processName: String?,
    val packageName: String?,
    /** The measured value that triggered this, in the type's natural unit. */
    val value: Double?,
    /** The previous value, where the event is a delta. */
    val previousValue: Double?,
)

@Entity(
    tableName = "process_snapshots",
    foreignKeys = [
        ForeignKey(
            entity = InvestigationEntity::class,
            parentColumns = ["id"],
            childColumns = ["investigationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("investigationId"), Index("timestamp")],
)
data class ProcessSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val investigationId: Long,
    val timestamp: Long,

    // System-wide figures at this instant. Nullable rather than sentinel-valued:
    // a null CPU reading means "not observable", and a 0 would be a fabrication.
    val cpuPercent: Float?,
    val cpuProvenance: String,
    val memoryUsedBytes: Long,
    val memoryAvailableBytes: Long,
    val batteryLevel: Int,
    val batteryTemperatureDeciCelsius: Int?,
    val isCharging: Boolean,
    val isScreenOn: Boolean,
    val networkRxBytes: Long?,
    val networkTxBytes: Long?,

    /** Section 43: the app accounts for its own cost inside its own recordings. */
    val ownCpuPercent: Float?,
    val ownMemoryBytes: Long?,

    /** Per-process rows, stored as JSON — see [SnapshotEntryConverter]. */
    val entries: String,
    val processCount: Int,
    val discoveryMethod: String,
)

@Entity(
    tableName = "application_profiles",
    indices = [Index(value = ["packageName"], unique = true)],
)
data class ApplicationProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val label: String,
    val firstObservedAt: Long,
    val lastObservedAt: Long,
    val observationCount: Int,

    // Rolling aggregates over observed samples. Counts are stored alongside the
    // averages so a later sample can extend them without re-reading history, and
    // so the UI can say "averaged over N samples" instead of implying continuity.
    val cpuSampleCount: Int,
    val cpuAverage: Float?,
    val cpuPeak: Float?,
    val memorySampleCount: Int,
    val memoryAverageBytes: Long?,
    val memoryPeakBytes: Long?,
    val foregroundMillis: Long?,
    val wakeLockCount: Int?,
    val networkBytes: Long?,
    val notes: String?,
)

@Entity(
    tableName = "favorites",
    indices = [Index(value = ["type", "key"], unique = true)],
)
data class FavoriteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val key: String,
    val label: String,
    val createdAt: Long,
)

/**
 * Settings as a single row (Section 44).
 *
 * [id] is pinned to 1 so an `INSERT OR REPLACE` cannot accumulate rows. Every
 * column carries a Room default, which is what makes adding a setting a
 * non-destructive migration: `ALTER TABLE ADD COLUMN … DEFAULT …` fills existing
 * rows, so a user's other preferences survive untouched (Section 44's "never wipe
 * existing user data").
 */
@Entity(tableName = "user_settings")
data class UserSettingsEntity(
    @PrimaryKey val id: Int = SETTINGS_ROW_ID,

    @ColumnInfo(defaultValue = "DARK") val themeMode: String = "DARK",
    @ColumnInfo(defaultValue = "INDIGO") val accentColor: String = "INDIGO",
    @ColumnInfo(defaultValue = "0") val useDynamicColor: Boolean = false,
    @ColumnInfo(defaultValue = "1") val glassEffectEnabled: Boolean = true,
    @ColumnInfo(defaultValue = "FULL") val animationIntensity: String = "FULL",
    @ColumnInfo(defaultValue = "0") val reducedMotion: Boolean = false,
    @ColumnInfo(defaultValue = "1") val hapticsEnabled: Boolean = true,
    @ColumnInfo(defaultValue = "0") val highContrast: Boolean = false,

    @ColumnInfo(defaultValue = "TWO_SECONDS") val refreshRate: String = "TWO_SECONDS",
    @ColumnInfo(defaultValue = "1") val cpuPollingEnabled: Boolean = true,
    @ColumnInfo(defaultValue = "1") val memoryPollingEnabled: Boolean = true,
    @ColumnInfo(defaultValue = "1") val batteryPollingEnabled: Boolean = true,
    @ColumnInfo(defaultValue = "1") val networkPollingEnabled: Boolean = true,
    @ColumnInfo(defaultValue = "2") val processListPollMultiplier: Int = 2,

    @ColumnInfo(defaultValue = "5") val defaultDurationMinutes: Int = 5,
    @ColumnInfo(defaultValue = "50") val cpuWarningThreshold: Int = 50,
    @ColumnInfo(defaultValue = "80") val cpuCriticalThreshold: Int = 80,
    @ColumnInfo(defaultValue = "500") val memoryIncreaseWarningMb: Int = 500,
    @ColumnInfo(defaultValue = "400") val batteryTemperatureWarningDeciCelsius: Int = 400,
    @ColumnInfo(defaultValue = "1") val automaticEventDetection: Boolean = true,
    @ColumnInfo(defaultValue = "2000") val investigationSampleIntervalMillis: Long = 2_000L,

    @ColumnInfo(defaultValue = "1") val localOnlyMode: Boolean = true,
    @ColumnInfo(defaultValue = "1") val includeSystemAppsInExport: Boolean = true,
    @ColumnInfo(defaultValue = "JSON") val exportFormat: String = "JSON",

    @ColumnInfo(defaultValue = "1") val shizukuEnabled: Boolean = true,
    @ColumnInfo(defaultValue = "0") val rootEnabled: Boolean = false,
    @ColumnInfo(defaultValue = "1") val showOwnResourceUsage: Boolean = true,
    @ColumnInfo(defaultValue = "1") val showSystemProcesses: Boolean = true,

    @ColumnInfo(defaultValue = "0") val onboardingCompleted: Boolean = false,
)

/** The only valid primary key for [UserSettingsEntity]. */
const val SETTINGS_ROW_ID: Int = 1
