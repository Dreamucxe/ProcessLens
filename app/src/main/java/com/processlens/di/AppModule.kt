package com.processlens.di

import android.content.Context
import androidx.room.Room
import com.processlens.core.common.ApplicationScope
import com.processlens.core.common.DefaultDispatcher
import com.processlens.core.common.IoDispatcher
import com.processlens.core.common.MainDispatcher
import com.processlens.core.system.ProcFsReader
import com.processlens.core.system.CompositeSystemObserver
import com.processlens.core.system.SystemObserver
import com.processlens.data.database.ApplicationProfileDao
import com.processlens.data.database.Converters
import com.processlens.data.database.FavoriteDao
import com.processlens.data.database.InvestigationDao
import com.processlens.data.database.InvestigationEventDao
import com.processlens.data.database.ProcessLensDatabase
import com.processlens.data.database.ProcessSnapshotDao
import com.processlens.data.database.UserSettingsDao
import com.processlens.data.repository.AppRepositoryImpl
import com.processlens.data.repository.FavoritesRepositoryImpl
import com.processlens.data.repository.InvestigationRepositoryImpl
import com.processlens.data.repository.ProcessRepositoryImpl
import com.processlens.data.repository.SettingsRepositoryImpl
import com.processlens.data.repository.SystemRepositoryImpl
import com.processlens.domain.repository.AppRepository
import com.processlens.domain.repository.FavoritesRepository
import com.processlens.domain.repository.InvestigationRepository
import com.processlens.domain.repository.ProcessRepository
import com.processlens.domain.repository.SettingsRepository
import com.processlens.domain.repository.SystemRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/**
 * Dispatchers, injected rather than referenced statically.
 *
 * `Dispatchers.IO` written inline inside a repository is untestable: a unit test
 * cannot substitute a deterministic scheduler for it. Injecting them means every
 * repository in this app can be driven by `StandardTestDispatcher` in a test and
 * still run on the right thread pool in production.
 */
@Module
@InstallIn(SingletonComponent::class)
object DispatcherModule {

    /** Blocking file and Binder reads: `/proc`, `dumpsys`, PackageManager. */
    @Provides
    @IoDispatcher
    fun ioDispatcher(): CoroutineDispatcher = Dispatchers.IO

    /**
     * Parsing, diffing and event derivation. Distinct from IO because these are
     * CPU-bound: running them on the unbounded IO pool would let a burst of parses
     * spawn dozens of threads on a device that has eight cores (Section 43).
     */
    @Provides
    @DefaultDispatcher
    fun defaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    @MainDispatcher
    fun mainDispatcher(): CoroutineDispatcher = Dispatchers.Main.immediate

    /**
     * Application-lifetime scope for work that must outlive a screen — the
     * recording service's sampling loop, and the startup reconciliation of
     * interrupted investigations.
     *
     * `SupervisorJob` so one failed child does not cancel the rest: a `/proc` read
     * that throws on an unusual OEM kernel must not take down recording.
     */
    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(@IoDispatcher io: CoroutineDispatcher): CoroutineScope =
        CoroutineScope(SupervisorJob() + io)
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): ProcessLensDatabase =
        Room.databaseBuilder(context, ProcessLensDatabase::class.java, ProcessLensDatabase.NAME)
            // Every migration, in order. No destructive fallback is configured, so a
            // missing migration fails loudly in development instead of silently
            // deleting a user's recorded investigations (Section 44).
            .addMigrations(*ProcessLensDatabase.MIGRATIONS)
            .build()

    @Provides
    @Singleton
    fun converters(): Converters = Converters()

    @Provides fun investigationDao(db: ProcessLensDatabase): InvestigationDao = db.investigationDao()
    @Provides fun eventDao(db: ProcessLensDatabase): InvestigationEventDao = db.eventDao()
    @Provides fun snapshotDao(db: ProcessLensDatabase): ProcessSnapshotDao = db.snapshotDao()
    @Provides fun profileDao(db: ProcessLensDatabase): ApplicationProfileDao = db.profileDao()
    @Provides fun favoriteDao(db: ProcessLensDatabase): FavoriteDao = db.favoriteDao()
    @Provides fun settingsDao(db: ProcessLensDatabase): UserSettingsDao = db.settingsDao()
}

@Module
@InstallIn(SingletonComponent::class)
object SystemModule {

    /**
     * [ProcFsReader] is a plain class with no injected dependencies — it reads
     * files and nothing else — so it is provided rather than constructor-injected.
     * Singleton because it holds nothing per-caller and every observer shares it.
     */
    @Provides
    @Singleton
    fun procFsReader(): ProcFsReader = ProcFsReader()
}

/**
 * Binds the interfaces the rest of the app depends on to their implementations.
 *
 * Note the direction: `SystemObserver` resolves to [CompositeSystemObserver], which
 * is the only class that knows an elevated shell exists. Nothing above the data
 * layer can obtain a `ShizukuShell` or `RootShell` from the graph, which is how
 * Section 46's "the UI should never directly call shell commands" is enforced by
 * construction rather than by convention.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    abstract fun systemObserver(impl: CompositeSystemObserver): SystemObserver

    @Binds
    abstract fun systemRepository(impl: SystemRepositoryImpl): SystemRepository

    @Binds
    abstract fun processRepository(impl: ProcessRepositoryImpl): ProcessRepository

    @Binds
    abstract fun appRepository(impl: AppRepositoryImpl): AppRepository

    @Binds
    abstract fun investigationRepository(impl: InvestigationRepositoryImpl): InvestigationRepository

    @Binds
    abstract fun settingsRepository(impl: SettingsRepositoryImpl): SettingsRepository

    @Binds
    abstract fun favoritesRepository(impl: FavoritesRepositoryImpl): FavoritesRepository
}
