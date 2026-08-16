package com.processlens.domain.usecase

import com.processlens.BuildConfig
import com.processlens.core.common.IoDispatcher
import com.processlens.core.system.ExportStore
import com.processlens.domain.model.ExportFormat
import com.processlens.domain.repository.AppRepository
import com.processlens.domain.repository.InvestigationRepository
import com.processlens.domain.repository.SettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Exports one recording to a file (Section 26).
 *
 * Reads the recording back out of the database rather than exporting whatever a screen
 * currently holds. A screen shows a windowed, downsampled view for the sake of the
 * chart; the export is meant to be the evidence, so it takes every stored sample.
 *
 * All three of the export settings are honoured here, at the one place an export
 * happens: the format, whether system-app rows are included, and whether the result
 * may be offered to another app at all.
 */
@Singleton
class ExportInvestigation @Inject constructor(
    private val investigations: InvestigationRepository,
    private val apps: AppRepository,
    private val settings: SettingsRepository,
    private val store: ExportStore,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /**
     * The outcome. [shareable] is false when the user has switched local-only mode on,
     * and the caller must then not offer a share action — the file still exists.
     */
    sealed interface Result {
        data class Success(
            val written: ExportStore.Written,
            val format: ExportFormat,
            val shareable: Boolean,
            val sampleCount: Int,
            val eventCount: Int,
            /** Set when system-app rows were withheld, so the UI can say so. */
            val omissionNote: String?,
        ) : Result

        data class Failure(val message: String, val technical: String?) : Result
    }

    /**
     * Builds and writes the export.
     *
     * [formatOverride] lets a caller pick a format for one export without changing the
     * saved preference — the export sheet offers all three, and choosing one there is
     * not a settings change.
     */
    suspend fun export(
        investigationId: Long,
        formatOverride: ExportFormat? = null,
    ): Result = withContext(io) {
        val current = settings.get()
        val format = formatOverride ?: current.exportFormat

        val investigation = investigations.get(investigationId)
            ?: return@withContext Result.Failure(
                message = "That recording is no longer in the database.",
                technical = "InvestigationRepository.get(" + investigationId + ") returned null",
            )

        if (investigation.isRunning) {
            return@withContext Result.Failure(
                message = "This recording is still running. Stop it first, so the " +
                    "export covers a finished session rather than half of one.",
                technical = null,
            )
        }

        try {
            val snapshots = investigations.getSnapshots(investigationId)
            val events = investigations.observeEvents(investigationId).first()
            val summary = investigations.buildSummary(investigationId)

            // Only read the package list when it can change the output. Enumerating
            // every installed package costs real time on a large device, and an export
            // that includes system rows has no use for it (Section 43).
            val systemPackages = if (current.includeSystemAppsInExport) {
                emptySet()
            } else {
                apps.observeApps(includeSystem = true).first()
                    .filter { it.isSystemApp }
                    .map { it.packageName }
                    .toSet()
            }

            val data = ExportData(
                investigation = investigation,
                snapshots = snapshots,
                events = events,
                summary = summary,
                includeSystemApps = current.includeSystemAppsInExport,
                systemPackages = systemPackages,
                appVersionName = BuildConfig.VERSION_NAME,
                exportedAt = System.currentTimeMillis(),
            )

            val written = store.write(ExportRenderer.render(data, format))

            Result.Success(
                written = written,
                format = format,
                shareable = !current.localOnlyMode,
                sampleCount = snapshots.size,
                eventCount = events.size,
                omissionNote = if (data.hasFilteredRows) {
                    "System-app rows were left out, as set in Settings. The recording " +
                        "still holds them."
                } else {
                    null
                },
            )
        } catch (error: Throwable) {
            // Rethrowing cancellation is deliberate: a cancelled export is not a
            // failed one, and reporting it as an error would put a message on screen
            // for a screen the user has already left.
            if (error is kotlinx.coroutines.CancellationException) throw error
            Result.Failure(
                message = "The export could not be written. The device may be out of " +
                    "space, or the cache may be unwritable.",
                technical = error::class.java.simpleName + ": " + (error.message ?: "no detail"),
            )
        }
    }

    /** Bytes currently held by previous exports, for the data controls in Settings. */
    fun cacheUsedBytes(): Long = store.usedBytes()

    /** Deletes every previously written export. Returns how many files went. */
    suspend fun clearExports(): Int = withContext(io) { store.clear() }
}
