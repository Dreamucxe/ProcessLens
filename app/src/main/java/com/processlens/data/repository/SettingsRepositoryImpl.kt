package com.processlens.data.repository

import com.processlens.core.common.IoDispatcher
import com.processlens.data.database.FavoriteDao
import com.processlens.data.database.UserSettingsDao
import com.processlens.data.database.toDomain
import com.processlens.data.database.toEntity
import com.processlens.domain.model.Favorite
import com.processlens.domain.model.FavoriteType
import com.processlens.domain.model.UserSettings
import com.processlens.domain.repository.FavoritesRepository
import com.processlens.domain.repository.SettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Settings, persisted in the single-row `user_settings` table (Section 44).
 *
 * [update] takes a transform rather than a whole object so two concurrent writers
 * — say the theme toggle and the refresh-rate picker — cannot clobber each other by
 * each writing a full copy read before the other's change. The mutex makes
 * read-modify-write atomic across coroutines; the row's `@ColumnInfo` defaults make
 * it atomic across schema versions.
 */
@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val dao: UserSettingsDao,
    @IoDispatcher private val io: CoroutineDispatcher,
) : SettingsRepository {

    private val writeLock = Mutex()

    override fun observe(): Flow<UserSettings> = dao.observe()
        .map { it?.toDomain() ?: UserSettings() }
        .distinctUntilChanged()

    override suspend fun get(): UserSettings = withContext(io) {
        dao.getOrCreate().toDomain()
    }

    override suspend fun update(transform: (UserSettings) -> UserSettings) {
        withContext(io) {
            writeLock.withLock {
                val current = dao.getOrCreate().toDomain()
                val updated = transform(current)
                if (updated != current) dao.upsert(updated.toEntity())
            }
        }
    }

    override suspend fun markOnboardingComplete() =
        update { it.copy(onboardingCompleted = true) }
}

/** Favourites (Section 31). */
@Singleton
class FavoritesRepositoryImpl @Inject constructor(
    private val dao: FavoriteDao,
    @IoDispatcher private val io: CoroutineDispatcher,
) : FavoritesRepository {

    override fun observeAll(): Flow<List<Favorite>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override fun observeIsFavorite(type: FavoriteType, key: String): Flow<Boolean> =
        dao.observeIsFavorite(type.name, key).distinctUntilChanged()

    /** Returns the new state, so a caller can announce it for accessibility. */
    override suspend fun toggle(type: FavoriteType, key: String, label: String): Boolean =
        withContext(io) {
            if (dao.isFavorite(type.name, key)) {
                dao.delete(type.name, key)
                false
            } else {
                dao.insert(
                    Favorite(
                        type = type,
                        key = key,
                        label = label,
                        createdAt = System.currentTimeMillis(),
                    ).toEntity(),
                )
                true
            }
        }

    override suspend fun remove(type: FavoriteType, key: String) = withContext(io) {
        dao.delete(type.name, key)
    }
}
