package com.processlens.data.database

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.processlens.domain.model.InvestigationState
import com.processlens.domain.model.ProcessImportance
import com.processlens.domain.model.SnapshotEntry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * The Room database, on a real device (Sections 44, 56).
 *
 * This runs as an instrumentation test rather than a JVM test on purpose: the thing
 * worth verifying is the behaviour of the actual SQLite build shipped in the Android
 * image — foreign-key cascades, `INSERT OR IGNORE` conflict handling, the collation
 * used by `ORDER BY`. A JVM SQLite substitute would answer for a different engine.
 */
@RunWith(AndroidJUnit4::class)
class ProcessLensDatabaseTest {

    private lateinit var db: ProcessLensDatabase
    private val converters = Converters()

    @Before
    fun createDb() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // In-memory, but the same schema and the same engine as the shipped database.
        db = Room.inMemoryDatabaseBuilder(context, ProcessLensDatabase::class.java)
            .build()
    }

    @After
    @Throws(IOException::class)
    fun closeDb() {
        db.close()
    }

    // -------------------------------------------------------------- investigations

    @Test
    fun anInvestigationRoundTripsThroughTheDatabase() = runTest {
        val id = db.investigationDao().insert(investigation(name = "Battery drain"))

        val loaded = db.investigationDao().getById(id)

        assertThat(loaded).isNotNull()
        assertThat(loaded!!.name).isEqualTo("Battery drain")
        assertThat(loaded.id).isEqualTo(id)
        assertThat(loaded.sampleIntervalMillis).isEqualTo(2_000L)
    }

    @Test
    fun theActiveInvestigationIsTheOneStillInTheRecordingState() = runTest {
        db.investigationDao().insert(
            investigation(name = "Finished", endedAt = 2_000L, state = InvestigationState.COMPLETED),
        )
        val activeId = db.investigationDao().insert(
            investigation(name = "Running", endedAt = null, state = InvestigationState.RECORDING),
        )

        val active = db.investigationDao().getActive()

        assertThat(active?.id).isEqualTo(activeId)
        assertThat(active?.name).isEqualTo("Running")
    }

    @Test
    fun observingAnInvestigationEmitsLaterUpdates() = runTest {
        val id = db.investigationDao().insert(investigation(name = "Initial"))

        val before = db.investigationDao().observeById(id).first()
        assertThat(before?.name).isEqualTo("Initial")

        db.investigationDao().update(before!!.copy(name = "Renamed", endedAt = 9_000L))

        val after = db.investigationDao().observeById(id).first()
        assertThat(after?.name).isEqualTo("Renamed")
        assertThat(after?.endedAt).isEqualTo(9_000L)
    }

    @Test
    fun aStaleRecordingIsMarkedInterruptedRatherThanLeftRunning() = runTest {
        // The recording service can be killed by the platform without an end marker.
        // Section 44: the row must not stay "recording" forever, and it must not be
        // silently deleted either — an interrupted investigation still holds real data.
        db.investigationDao().insert(
            investigation(name = "Killed mid-run", endedAt = null, state = InvestigationState.RECORDING),
        )

        val changed = db.investigationDao().markStaleAsInterrupted()

        assertThat(changed).isEqualTo(1)
        val row = db.investigationDao().observeAll().first().single()
        assertThat(row.state).isEqualTo(InvestigationState.INTERRUPTED.name)
        assertThat(db.investigationDao().getActive()).isNull()
    }

    // -------------------------------------------------------------------- cascades

    @Test
    fun deletingAnInvestigationCascadesToItsEventsAndSnapshots() = runTest {
        val id = db.investigationDao().insert(investigation())
        db.eventDao().insert(event(id, timestamp = 1_000L))
        db.eventDao().insert(event(id, timestamp = 2_000L))
        db.snapshotDao().insert(snapshot(id, timestamp = 1_000L))

        assertThat(db.investigationDao().eventCount(id)).isEqualTo(2)
        assertThat(db.snapshotDao().count(id)).isEqualTo(1)

        db.investigationDao().delete(id)

        // No orphan rows: the foreign keys declare CASCADE and SQLite must honour it.
        assertThat(db.eventDao().getForInvestigation(id)).isEmpty()
        assertThat(db.snapshotDao().getForInvestigation(id)).isEmpty()
    }

    // ---------------------------------------------------------------------- events

    @Test
    fun eventsComeBackInChronologicalOrder() = runTest {
        val id = db.investigationDao().insert(investigation())
        // Inserted out of order — the timeline depends on the query, not the insert.
        db.eventDao().insert(event(id, timestamp = 3_000L, title = "third"))
        db.eventDao().insert(event(id, timestamp = 1_000L, title = "first"))
        db.eventDao().insert(event(id, timestamp = 2_000L, title = "second"))

        val titles = db.eventDao().getForInvestigation(id).map { it.title }

        assertThat(titles).containsExactly("first", "second", "third").inOrder()
    }

    @Test
    fun anEventWindowExcludesEventsOutsideIt() = runTest {
        val id = db.investigationDao().insert(investigation())
        db.eventDao().insertAll(
            listOf(
                event(id, timestamp = 500L, title = "before"),
                event(id, timestamp = 1_500L, title = "inside"),
                event(id, timestamp = 5_000L, title = "after"),
            ),
        )

        val window = db.eventDao().getInWindow(id, from = 1_000L, to = 2_000L)

        assertThat(window.map { it.title }).containsExactly("inside")
    }

    @Test
    fun eventsAreCountedBySeverity() = runTest {
        val id = db.investigationDao().insert(investigation())
        db.eventDao().insertAll(
            listOf(
                event(id, timestamp = 1L, severity = "CRITICAL"),
                event(id, timestamp = 2L, severity = "CRITICAL"),
                event(id, timestamp = 3L, severity = "WARNING"),
            ),
        )

        assertThat(db.eventDao().countBySeverity(id, "CRITICAL")).isEqualTo(2)
        assertThat(db.eventDao().countBySeverity(id, "WARNING")).isEqualTo(1)
        assertThat(db.eventDao().countBySeverity(id, "INFO")).isEqualTo(0)
    }

    @Test
    fun theEvidenceStringSurvivesTheRoundTrip() = runTest {
        // Section 15 hangs on this: the number behind a claim is stored verbatim, and
        // the UI shows it beside the description. If it were lost the event would be
        // an unsupported assertion.
        val id = db.investigationDao().insert(investigation())
        val evidence = "CPU 84.2% (sampled, /proc) at 14:03:12, threshold 80%"
        db.eventDao().insert(event(id, timestamp = 1L).copy(evidence = evidence))

        assertThat(db.eventDao().getForInvestigation(id).single().evidence).isEqualTo(evidence)
    }

    // ------------------------------------------------------------------- snapshots

    @Test
    fun anUnobservableCpuReadingIsStoredAsNullRatherThanZero() = runTest {
        // The single most important storage rule in the app (Section 42). A 0.0 here
        // would be indistinguishable from a measured idle CPU on every later screen,
        // every chart and every export.
        val id = db.investigationDao().insert(investigation())
        db.snapshotDao().insert(snapshot(id, timestamp = 1_000L, cpuPercent = null))

        val row = db.snapshotDao().getLatest(id)

        assertThat(row).isNotNull()
        assertThat(row!!.cpuPercent).isNull()
    }

    @Test
    fun theChartSeriesPreservesNullsAsGaps() = runTest {
        val id = db.investigationDao().insert(investigation())
        db.snapshotDao().insert(snapshot(id, timestamp = 1_000L, cpuPercent = 10f))
        db.snapshotDao().insert(snapshot(id, timestamp = 2_000L, cpuPercent = null))
        db.snapshotDao().insert(snapshot(id, timestamp = 3_000L, cpuPercent = 30f))

        val series = db.snapshotDao().getSeries(id)

        assertThat(series).hasSize(3)
        assertThat(series.map { it.cpuPercent }).containsExactly(10f, null, 30f).inOrder()
    }

    @Test
    fun theNearestSnapshotToATimestampIsFoundForReplay() = runTest {
        val id = db.investigationDao().insert(investigation())
        db.snapshotDao().insert(snapshot(id, timestamp = 1_000L))
        db.snapshotDao().insert(snapshot(id, timestamp = 5_000L))

        // Scrubbing the timeline to 4.4 s must land on the 5 s sample, not the 1 s one.
        assertThat(db.snapshotDao().getNearest(id, 4_400L)?.timestamp).isEqualTo(5_000L)
        assertThat(db.snapshotDao().getNearest(id, 1_200L)?.timestamp).isEqualTo(1_000L)
    }

    @Test
    fun perProcessEntriesSurviveJsonStorageWithTheirGaps() = runTest {
        val id = db.investigationDao().insert(investigation())
        val entries = listOf(
            SnapshotEntry(
                processName = "com.example.app",
                packageName = "com.example.app",
                pid = 1234,
                cpuPercent = 12.5f,
                memoryBytes = 64L * 1024 * 1024,
                importance = ProcessImportance.FOREGROUND,
            ),
            SnapshotEntry(
                // Discovered via usage stats: no PID, no CPU, no memory are observable.
                processName = "com.other.app",
                packageName = "com.other.app",
                pid = null,
                cpuPercent = null,
                memoryBytes = null,
                importance = ProcessImportance.UNKNOWN,
            ),
        )
        db.snapshotDao().insert(snapshot(id, timestamp = 1L, entries = entries))

        val restored = db.snapshotDao().getLatest(id)!!.toDomain(converters).entries

        assertThat(restored).hasSize(2)
        assertThat(restored[0].pid).isEqualTo(1234)
        assertThat(restored[0].cpuPercent).isWithin(0.001f).of(12.5f)
        // The gaps came back as gaps, not as zeros.
        assertThat(restored[1].pid).isNull()
        assertThat(restored[1].cpuPercent).isNull()
        assertThat(restored[1].memoryBytes).isNull()
    }

    @Test
    fun aggregatesReportThePeakProcessCount() = runTest {
        val id = db.investigationDao().insert(investigation())
        db.snapshotDao().insert(snapshot(id, timestamp = 1L, processCount = 40))
        db.snapshotDao().insert(snapshot(id, timestamp = 2L, processCount = 137))
        db.snapshotDao().insert(snapshot(id, timestamp = 3L, processCount = 92))

        assertThat(db.investigationDao().peakProcessCount(id)).isEqualTo(137)
    }

    @Test
    fun aggregatesOverAnEmptyInvestigationAreNullRatherThanZero() = runTest {
        val id = db.investigationDao().insert(investigation())

        // No snapshots were recorded, so there is no peak. Zero would be a claim.
        assertThat(db.investigationDao().peakProcessCount(id)).isNull()
    }

    // ------------------------------------------------------------------- favorites

    @Test
    fun favouritingTheSameThingTwiceIsIdempotent() = runTest {
        db.favoriteDao().insert(favorite(key = "com.example.app"))
        db.favoriteDao().insert(favorite(key = "com.example.app"))

        assertThat(db.favoriteDao().observeAll().first()).hasSize(1)
        assertThat(db.favoriteDao().isFavorite("APP", "com.example.app")).isTrue()
    }

    @Test
    fun unfavouritingRemovesOnlyTheMatchingTypeAndKey() = runTest {
        db.favoriteDao().insert(favorite(type = "APP", key = "com.example.app"))
        db.favoriteDao().insert(favorite(type = "PROCESS", key = "com.example.app"))

        db.favoriteDao().delete("APP", "com.example.app")

        assertThat(db.favoriteDao().isFavorite("APP", "com.example.app")).isFalse()
        assertThat(db.favoriteDao().isFavorite("PROCESS", "com.example.app")).isTrue()
    }

    @Test
    fun favouriteStatusIsObservable() = runTest {
        assertThat(db.favoriteDao().observeIsFavorite("APP", "com.example.app").first()).isFalse()

        db.favoriteDao().insert(favorite(key = "com.example.app"))

        assertThat(db.favoriteDao().observeIsFavorite("APP", "com.example.app").first()).isTrue()
    }

    // -------------------------------------------------------------------- settings

    @Test
    fun settingsAreCreatedOnFirstReadAndPersistAcrossWrites() = runTest {
        val created = db.settingsDao().getOrCreate()

        // Defaults, not an empty row.
        assertThat(created.themeMode).isEqualTo("DARK")
        assertThat(created.localOnlyMode).isTrue()

        db.settingsDao().upsert(created.copy(themeMode = "LIGHT", cpuWarningThreshold = 65))

        val reloaded = db.settingsDao().get()
        assertThat(reloaded?.themeMode).isEqualTo("LIGHT")
        assertThat(reloaded?.cpuWarningThreshold).isEqualTo(65)
        // Still a single row: settings are a singleton, not a growing table.
        assertThat(db.settingsDao().getOrCreate().id).isEqualTo(created.id)
    }

    @Test
    fun enumsArePersistedByNameSoReorderingTheEnumCannotCorruptThem() = runTest {
        // Ordinals would silently remap every stored preference the first time a new
        // member is inserted into the middle of an enum (Section 44).
        val settings = db.settingsDao().getOrCreate()
        db.settingsDao().upsert(settings.copy(accentColor = "TEAL", exportFormat = "CSV"))

        val row = db.settingsDao().get()!!
        assertThat(row.accentColor).isEqualTo("TEAL")
        assertThat(row.exportFormat).isEqualTo("CSV")
        assertThat(row.toDomain().accentColor.name).isEqualTo("TEAL")
    }

    @Test
    fun anUnknownStoredEnumFallsBackInsteadOfCrashing() = runTest {
        // A value written by a newer build then downgraded. Losing one preference is
        // recoverable; throwing on every read of the settings table is not.
        val settings = db.settingsDao().getOrCreate()
        db.settingsDao().upsert(settings.copy(themeMode = "SEPIA_FROM_THE_FUTURE"))

        val domain = db.settingsDao().get()!!.toDomain()

        assertThat(domain.themeMode.name).isEqualTo("DARK")
    }

    // -------------------------------------------------------------------- fixtures

    private fun investigation(
        name: String = "Investigation",
        endedAt: Long? = 10_000L,
        state: InvestigationState = InvestigationState.COMPLETED,
    ) = InvestigationEntity(
        name = name,
        startedAt = 1_000L,
        endedAt = endedAt,
        state = state.name,
        targetPackage = null,
        accessLevel = "NORMAL",
        apiLevel = 34,
        deviceModel = "Test Device",
        sampleIntervalMillis = 2_000L,
        notes = null,
    )

    private fun event(
        investigationId: Long,
        timestamp: Long,
        title: String = "CPU spike",
        severity: String = "WARNING",
    ) = InvestigationEventEntity(
        investigationId = investigationId,
        timestamp = timestamp,
        type = "CPU_SPIKE",
        severity = severity,
        title = title,
        detail = "System CPU rose to 84%",
        evidence = "CPU 84.2% (sampled, /proc), threshold 80%",
        processName = null,
        packageName = null,
        value = 84.2,
        previousValue = 41.0,
    )

    private fun snapshot(
        investigationId: Long,
        timestamp: Long,
        cpuPercent: Float? = 25f,
        processCount: Int = 100,
        entries: List<SnapshotEntry> = emptyList(),
    ) = ProcessSnapshotEntity(
        investigationId = investigationId,
        timestamp = timestamp,
        cpuPercent = cpuPercent,
        cpuProvenance = "PROC_FS",
        memoryUsedBytes = 3L * 1024 * 1024 * 1024,
        memoryAvailableBytes = 1L * 1024 * 1024 * 1024,
        batteryLevel = 80,
        batteryTemperatureDeciCelsius = 305,
        isCharging = false,
        isScreenOn = true,
        networkRxBytes = 1_024L,
        networkTxBytes = 512L,
        ownCpuPercent = 0.8f,
        ownMemoryBytes = 48L * 1024 * 1024,
        entries = converters.snapshotEntriesToJson(entries),
        processCount = processCount,
        discoveryMethod = "PROC_WALK",
    )

    private fun favorite(type: String = "APP", key: String = "com.example.app") = FavoriteEntity(
        type = type,
        key = key,
        label = key,
        createdAt = 1_000L,
    )
}

/**
 * Schema migrations (Section 44).
 *
 * The database is at version 1, so there is no migration to exercise yet and
 * [ProcessLensDatabase.MIGRATIONS] is deliberately empty. What this class does verify
 * is the property that makes future migrations safe: the schema is exported to
 * `app/schemas`, and the builder has no `fallbackToDestructiveMigration()`. Without
 * that guarantee a version bump would delete every recorded investigation, which
 * Section 44 forbids outright.
 *
 * When version 2 arrives, add a `migrate1To2` test here using [helper] — the exported
 * version-1 JSON is already committed, so it can be tested against the real historical
 * schema rather than a hand-written approximation of it.
 */
@RunWith(AndroidJUnit4::class)
class ProcessLensMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ProcessLensDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun theExportedSchemaMatchesTheCompiledEntities() {
        // MigrationTestHelper reads app/schemas/<version>.json. If the entities and the
        // exported schema had drifted, creating the database at VERSION would throw —
        // which is the failure that would otherwise appear as a crash on a user's
        // device after an upgrade.
        helper.createDatabase(TEST_DB, ProcessLensDatabase.VERSION).close()
    }

    @Test
    fun theCurrentVersionOpensWithoutDestroyingData() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(TEST_DB)

        helper.createDatabase(TEST_DB, ProcessLensDatabase.VERSION).use { connection ->
            connection.execSQL(
                """
                INSERT INTO investigations
                    (name, startedAt, endedAt, state, targetPackage, accessLevel,
                     apiLevel, deviceModel, sampleIntervalMillis, notes)
                VALUES
                    ('Pre-existing', 1000, 2000, 'COMPLETED', NULL, 'NORMAL',
                     34, 'Test Device', 2000, NULL)
                """.trimIndent(),
            )
        }

        // Reopen through Room itself, exactly as the app does at launch.
        val db = Room.databaseBuilder(context, ProcessLensDatabase::class.java, TEST_DB)
            .addMigrations(*ProcessLensDatabase.MIGRATIONS)
            .build()

        try {
            val rows = db.query("SELECT name FROM investigations", null)
            rows.use {
                assertThat(it.moveToFirst()).isTrue()
                assertThat(it.getString(0)).isEqualTo("Pre-existing")
            }
        } finally {
            db.close()
            context.deleteDatabase(TEST_DB)
        }
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
