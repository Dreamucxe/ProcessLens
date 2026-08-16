package com.processlens.data.repository

import com.processlens.core.common.IoDispatcher
import com.processlens.data.database.Converters
import com.processlens.data.database.InvestigationDao
import com.processlens.data.database.InvestigationEventDao
import com.processlens.data.database.ProcessSnapshotDao
import com.processlens.data.database.SnapshotSeriesRow
import com.processlens.data.database.toDomain
import com.processlens.data.database.toEntity
import com.processlens.domain.model.Investigation
import com.processlens.domain.model.InvestigationEvent
import com.processlens.domain.model.InvestigationSummary
import com.processlens.domain.model.InvestigationState
import com.processlens.domain.model.ProcessSnapshot
import com.processlens.domain.repository.InvestigationRepository
import com.processlens.domain.repository.SeriesPoint
import com.processlens.domain.usecase.InvestigationSummarizer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Recorded investigations, and the summaries derived from them (Sections 14, 24, 25).
 *
 * The summary is *recomputed from the stored snapshots* every time it is asked for,
 * rather than written once at the end of a recording. That is deliberate: a summary
 * stored as prose cannot be audited, whereas one derived on demand can always be
 * traced back to the samples that produced it — and if the derivation is later found
 * to be wrong, existing recordings gain the corrected reading instead of keeping a
 * stale claim.
 */
@Singleton
class InvestigationRepositoryImpl @Inject constructor(
    private val investigationDao: InvestigationDao,
    private val eventDao: InvestigationEventDao,
    private val snapshotDao: ProcessSnapshotDao,
    private val converters: Converters,
    @IoDispatcher private val io: CoroutineDispatcher,
) : InvestigationRepository {

    override fun observeAll(): Flow<List<Investigation>> =
        investigationDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override fun observeActive(): Flow<Investigation?> =
        investigationDao.observeActive().map { it?.toDomain() }

    override fun observeById(id: Long): Flow<Investigation?> =
        investigationDao.observeById(id).map { it?.toDomain() }

    override fun observeEvents(id: Long): Flow<List<InvestigationEvent>> =
        eventDao.observeForInvestigation(id).map { rows -> rows.map { it.toDomain() } }

    override fun observeRecentEvents(limit: Int): Flow<List<InvestigationEvent>> =
        eventDao.observeRecent(limit).map { rows -> rows.map { it.toDomain() } }

    override suspend fun get(id: Long): Investigation? = withContext(io) {
        val row = investigationDao.getById(id) ?: return@withContext null
        // Counts are fetched separately so the list query stays cheap; only the
        // detail screen pays for them.
        row.toDomain(
            eventCount = investigationDao.eventCount(id),
            snapshotCount = investigationDao.snapshotCount(id),
            processesObserved = investigationDao.peakProcessCount(id) ?: 0,
        )
    }

    override suspend fun getSnapshots(id: Long): List<ProcessSnapshot> = withContext(io) {
        snapshotDao.getForInvestigation(id).map { it.toDomain(converters) }
    }

    override suspend fun getSeries(id: Long): List<SeriesPoint> = withContext(io) {
        snapshotDao.getSeries(id).map { it.toSeriesPoint() }
    }

    override suspend fun getSnapshotAt(id: Long, timestamp: Long): ProcessSnapshot? =
        withContext(io) {
            snapshotDao.getNearest(id, timestamp)?.toDomain(converters)
        }

    override suspend fun delete(id: Long) = withContext(io) {
        // Events and snapshots go with it: the foreign keys cascade.
        investigationDao.delete(id)
    }

    override suspend fun rename(id: Long, name: String) {
        withContext(io) {
            val row = investigationDao.getById(id) ?: return@withContext
            investigationDao.update(row.copy(name = name.trim().ifBlank { row.name }))
        }
    }

    override suspend fun reconcileStaleRecordings(): Int = withContext(io) {
        investigationDao.markStaleAsInterrupted()
    }

    // --------------------------------------------------------------- write path

    override suspend fun create(investigation: Investigation): Long = withContext(io) {
        investigationDao.insert(investigation.toEntity())
    }

    override suspend fun appendSnapshot(
        snapshot: ProcessSnapshot,
        cpuProvenance: String,
        discoveryMethod: String,
    ): Long = withContext(io) {
        snapshotDao.insert(snapshot.toEntity(converters, cpuProvenance, discoveryMethod))
    }

    override suspend fun appendEvents(events: List<InvestigationEvent>) {
        if (events.isEmpty()) return
        withContext(io) { eventDao.insertAll(events.map { it.toEntity() }) }
    }

    override suspend fun finish(id: Long, state: InvestigationState) {
        withContext(io) {
            val row = investigationDao.getById(id) ?: return@withContext
            investigationDao.update(
                row.copy(state = state.name, endedAt = System.currentTimeMillis()),
            )
        }
    }

    // ------------------------------------------------------------------- summary

    override suspend fun buildSummary(id: Long): InvestigationSummary? = withContext(io) {
        val investigation = get(id) ?: return@withContext null
        // The derivation itself lives in InvestigationSummarizer, which is pure. This
        // method's only job is to read the samples; deciding what they mean is domain
        // logic and is unit-tested without a database.
        InvestigationSummarizer.summarize(
            investigation = investigation,
            snapshots = snapshotDao.getForInvestigation(id).map { it.toDomain(converters) },
            events = eventDao.getForInvestigation(id).map { it.toDomain() },
        )
    }
}

private fun SnapshotSeriesRow.toSeriesPoint() = SeriesPoint(
    timestamp = timestamp,
    cpuPercent = cpuPercent,
    memoryUsedBytes = memoryUsedBytes,
    memoryAvailableBytes = memoryAvailableBytes,
    batteryLevel = batteryLevel,
    batteryTemperatureDeciCelsius = batteryTemperatureDeciCelsius,
    isCharging = isCharging,
    isScreenOn = isScreenOn,
    networkRxBytes = networkRxBytes,
    networkTxBytes = networkTxBytes,
    ownCpuPercent = ownCpuPercent,
    ownMemoryBytes = ownMemoryBytes,
    processCount = processCount,
)
