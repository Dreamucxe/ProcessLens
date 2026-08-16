package com.processlens.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface InvestigationDao {

    @Insert
    suspend fun insert(investigation: InvestigationEntity): Long

    @Update
    suspend fun update(investigation: InvestigationEntity)

    @Query("SELECT * FROM investigations ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<InvestigationEntity>>

    @Query("SELECT * FROM investigations WHERE id = :id")
    fun observeById(id: Long): Flow<InvestigationEntity?>

    @Query("SELECT * FROM investigations WHERE id = :id")
    suspend fun getById(id: Long): InvestigationEntity?

    @Query("SELECT * FROM investigations WHERE state = 'RECORDING' ORDER BY startedAt DESC LIMIT 1")
    suspend fun getActive(): InvestigationEntity?

    @Query("SELECT * FROM investigations WHERE state = 'RECORDING' ORDER BY startedAt DESC LIMIT 1")
    fun observeActive(): Flow<InvestigationEntity?>

    @Query("DELETE FROM investigations WHERE id = :id")
    suspend fun delete(id: Long)

    /** Counts, so the list can show them without loading every event and snapshot. */
    @Query("SELECT COUNT(*) FROM investigation_events WHERE investigationId = :id")
    suspend fun eventCount(id: Long): Int

    @Query("SELECT COUNT(*) FROM process_snapshots WHERE investigationId = :id")
    suspend fun snapshotCount(id: Long): Int

    @Query(
        """
        SELECT MAX(processCount) FROM process_snapshots WHERE investigationId = :id
        """,
    )
    suspend fun peakProcessCount(id: Long): Int?

    /**
     * Marks any investigation still flagged RECORDING as INTERRUPTED.
     *
     * Called once at startup: if the recording service was killed by the platform,
     * the row is left mid-flight. Reporting it as still recording would be false,
     * and deleting it would discard real data — so it is marked interrupted and the
     * samples it did capture stay usable (Section 44's "never wipe user data").
     */
    @Query(
        """
        UPDATE investigations
        SET state = 'INTERRUPTED',
            endedAt = COALESCE(
                (SELECT MAX(timestamp) FROM process_snapshots WHERE investigationId = investigations.id),
                startedAt
            )
        WHERE state = 'RECORDING'
        """,
    )
    suspend fun markStaleAsInterrupted(): Int
}

@Dao
interface InvestigationEventDao {

    @Insert
    suspend fun insert(event: InvestigationEventEntity): Long

    @Insert
    suspend fun insertAll(events: List<InvestigationEventEntity>)

    @Query("SELECT * FROM investigation_events WHERE investigationId = :id ORDER BY timestamp ASC")
    fun observeForInvestigation(id: Long): Flow<List<InvestigationEventEntity>>

    @Query("SELECT * FROM investigation_events WHERE investigationId = :id ORDER BY timestamp ASC")
    suspend fun getForInvestigation(id: Long): List<InvestigationEventEntity>

    @Query(
        """
        SELECT * FROM investigation_events
        WHERE investigationId = :id AND timestamp BETWEEN :from AND :to
        ORDER BY timestamp ASC
        """,
    )
    suspend fun getInWindow(id: Long, from: Long, to: Long): List<InvestigationEventEntity>

    /** Most recent events across all investigations, for the dashboard timeline. */
    @Query("SELECT * FROM investigation_events ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<InvestigationEventEntity>>

    @Query("SELECT COUNT(*) FROM investigation_events WHERE investigationId = :id AND severity = :severity")
    suspend fun countBySeverity(id: Long, severity: String): Int
}

@Dao
interface ProcessSnapshotDao {

    @Insert
    suspend fun insert(snapshot: ProcessSnapshotEntity): Long

    @Query("SELECT * FROM process_snapshots WHERE investigationId = :id ORDER BY timestamp ASC")
    suspend fun getForInvestigation(id: Long): List<ProcessSnapshotEntity>

    @Query("SELECT * FROM process_snapshots WHERE investigationId = :id ORDER BY timestamp ASC")
    fun observeForInvestigation(id: Long): Flow<List<ProcessSnapshotEntity>>

    /**
     * System-level columns only, without the per-process JSON.
     *
     * A five-minute recording at 2 s holds 150 snapshots, each with a few hundred
     * process rows; deserialising all of that to draw one chart line is exactly the
     * kind of waste Section 43 warns against.
     */
    @Query(
        """
        SELECT timestamp, cpuPercent, memoryUsedBytes, memoryAvailableBytes,
               batteryLevel, batteryTemperatureDeciCelsius, isCharging, isScreenOn,
               networkRxBytes, networkTxBytes, ownCpuPercent, ownMemoryBytes, processCount
        FROM process_snapshots WHERE investigationId = :id ORDER BY timestamp ASC
        """,
    )
    suspend fun getSeries(id: Long): List<SnapshotSeriesRow>

    @Query(
        """
        SELECT * FROM process_snapshots
        WHERE investigationId = :id AND timestamp <= :timestamp
        ORDER BY timestamp DESC LIMIT 1
        """,
    )
    suspend fun getNearest(id: Long, timestamp: Long): ProcessSnapshotEntity?

    @Query("SELECT * FROM process_snapshots WHERE investigationId = :id ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatest(id: Long): ProcessSnapshotEntity?

    @Query("SELECT COUNT(*) FROM process_snapshots WHERE investigationId = :id")
    suspend fun count(id: Long): Int
}

/** Projection for chart series — deliberately excludes the per-process JSON. */
data class SnapshotSeriesRow(
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

@Dao
interface ApplicationProfileDao {

    @Upsert
    suspend fun upsert(profile: ApplicationProfileEntity)

    @Query("SELECT * FROM application_profiles WHERE packageName = :packageName")
    suspend fun get(packageName: String): ApplicationProfileEntity?

    @Query("SELECT * FROM application_profiles WHERE packageName = :packageName")
    fun observe(packageName: String): Flow<ApplicationProfileEntity?>

    @Query("SELECT * FROM application_profiles ORDER BY lastObservedAt DESC")
    fun observeAll(): Flow<List<ApplicationProfileEntity>>

    @Query("SELECT * FROM application_profiles ORDER BY cpuPeak DESC LIMIT :limit")
    suspend fun topByCpuPeak(limit: Int): List<ApplicationProfileEntity>

    @Query("DELETE FROM application_profiles WHERE packageName = :packageName")
    suspend fun delete(packageName: String)

    @Query("DELETE FROM application_profiles")
    suspend fun clear()
}

@Dao
interface FavoriteDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(favorite: FavoriteEntity): Long

    @Query("DELETE FROM favorites WHERE type = :type AND key = :key")
    suspend fun delete(type: String, key: String)

    @Query("SELECT * FROM favorites ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<FavoriteEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE type = :type AND key = :key)")
    fun observeIsFavorite(type: String, key: String): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE type = :type AND key = :key)")
    suspend fun isFavorite(type: String, key: String): Boolean
}

@Dao
interface UserSettingsDao {

    @Query("SELECT * FROM user_settings WHERE id = $SETTINGS_ROW_ID")
    fun observe(): Flow<UserSettingsEntity?>

    @Query("SELECT * FROM user_settings WHERE id = $SETTINGS_ROW_ID")
    suspend fun get(): UserSettingsEntity?

    @Upsert
    suspend fun upsert(settings: UserSettingsEntity)

    /**
     * Reads the row, creating it with defaults if this is a first run. Wrapped in a
     * transaction so two collectors starting at once cannot both insert.
     */
    @Transaction
    suspend fun getOrCreate(): UserSettingsEntity {
        get()?.let { return it }
        val fresh = UserSettingsEntity()
        upsert(fresh)
        return fresh
    }
}
