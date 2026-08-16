package com.processlens.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.processlens.domain.model.ProcessImportance
import com.processlens.domain.model.SnapshotEntry
import org.json.JSONArray
import org.json.JSONObject

/**
 * The ProcessLens database (Section 44).
 *
 * `exportSchema = true` (the location is set in `build.gradle.kts`) so every
 * schema version is committed as JSON and migrations can be tested against the
 * real historical schema rather than a hand-written approximation.
 *
 * **No destructive fallback is configured.** `fallbackToDestructiveMigration()` is
 * deliberately absent: a missing migration must fail loudly in development rather
 * than silently delete a user's recorded investigations on upgrade, which is what
 * Section 44's "never wipe existing user data after schema changes" forbids.
 */
@Database(
    entities = [
        InvestigationEntity::class,
        InvestigationEventEntity::class,
        ProcessSnapshotEntity::class,
        ApplicationProfileEntity::class,
        FavoriteEntity::class,
        UserSettingsEntity::class,
    ],
    version = ProcessLensDatabase.VERSION,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class ProcessLensDatabase : RoomDatabase() {

    abstract fun investigationDao(): InvestigationDao
    abstract fun eventDao(): InvestigationEventDao
    abstract fun snapshotDao(): ProcessSnapshotDao
    abstract fun profileDao(): ApplicationProfileDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun settingsDao(): UserSettingsDao

    companion object {
        const val VERSION = 1
        const val NAME = "processlens.db"

        /**
         * Every migration, in order. Empty at version 1 — but the array, the
         * exported schema and the absence of a destructive fallback are in place
         * from the first release, which is what makes the *second* release safe.
         *
         * The pattern for a future version:
         * ```
         * val MIGRATION_1_2 = Migration(1, 2) { db ->
         *     db.execSQL("ALTER TABLE investigations ADD COLUMN tag TEXT")
         * }
         * ```
         * Additive `ALTER TABLE … ADD COLUMN` with a default preserves every
         * existing row. A migration that drops or recreates a table must copy the
         * data across first.
         */
        val MIGRATIONS: Array<Migration> = emptyArray()
    }
}

/**
 * Converters for the composite columns.
 *
 * Snapshot entries are stored as JSON rather than in a child table: a snapshot's
 * rows are only ever read together with their snapshot, never queried
 * independently, and a five-minute recording would otherwise insert tens of
 * thousands of rows — which is precisely the self-inflicted load Section 43 warns
 * about. `org.json` is used rather than a serialization library because it is in
 * the platform, so it costs nothing in APK size or build time.
 */
class Converters {

    @TypeConverter
    fun snapshotEntriesToJson(entries: List<SnapshotEntry>): String {
        val array = JSONArray()
        for (entry in entries) {
            val obj = JSONObject()
            obj.put(KEY_NAME, entry.processName)
            entry.packageName?.let { obj.put(KEY_PACKAGE, it) }
            entry.pid?.let { obj.put(KEY_PID, it) }
            // A null CPU or memory figure is *omitted*, not written as 0: on read
            // it comes back null, which the UI renders as "not available".
            entry.cpuPercent?.let { obj.put(KEY_CPU, it.toDouble()) }
            entry.memoryBytes?.let { obj.put(KEY_MEMORY, it) }
            obj.put(KEY_IMPORTANCE, entry.importance.name)
            array.put(obj)
        }
        return array.toString()
    }

    @TypeConverter
    fun jsonToSnapshotEntries(json: String): List<SnapshotEntry> {
        if (json.isBlank()) return emptyList()
        return try {
            val array = JSONArray(json)
            val out = ArrayList<SnapshotEntry>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                // A row with no process name identifies nothing, so it is dropped
                // rather than stored as an anonymous entry.
                val name = obj.optString(KEY_NAME)
                if (name.isBlank()) continue
                out += SnapshotEntry(
                    processName = name,
                    packageName = obj.optString(KEY_PACKAGE).takeIf { it.isNotBlank() },
                    pid = if (obj.has(KEY_PID)) obj.optInt(KEY_PID) else null,
                    cpuPercent = if (obj.has(KEY_CPU)) obj.optDouble(KEY_CPU).toFloat() else null,
                    memoryBytes = if (obj.has(KEY_MEMORY)) obj.optLong(KEY_MEMORY) else null,
                    importance = runCatching {
                        ProcessImportance.valueOf(obj.optString(KEY_IMPORTANCE))
                    }.getOrDefault(ProcessImportance.UNKNOWN),
                )
            }
            out
        } catch (t: Throwable) {
            // A corrupt blob loses that snapshot's detail, not the whole recording.
            emptyList()
        }
    }

    private companion object {
        const val KEY_NAME = "n"
        const val KEY_PACKAGE = "p"
        const val KEY_PID = "i"
        const val KEY_CPU = "c"
        const val KEY_MEMORY = "m"
        const val KEY_IMPORTANCE = "v"
    }
}

/** Convenience for building a migration without the anonymous-class boilerplate. */
@Suppress("FunctionName")
fun Migration(from: Int, to: Int, body: (SupportSQLiteDatabase) -> Unit): Migration =
    object : Migration(from, to) {
        override fun migrate(db: SupportSQLiteDatabase) = body(db)
    }
