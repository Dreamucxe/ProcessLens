package com.processlens.feature.timeline

import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.processlens.core.system.ExportStore
import com.processlens.domain.model.EventGroup
import com.processlens.domain.model.EventSeverity
import com.processlens.domain.model.ExportFormat
import com.processlens.domain.model.Investigation
import com.processlens.domain.model.InvestigationEvent
import com.processlens.domain.model.InvestigationSummary
import com.processlens.domain.model.ProcessSnapshot
import com.processlens.domain.repository.InvestigationRepository
import com.processlens.domain.repository.SeriesPoint
import com.processlens.domain.usecase.ExportInvestigation
import com.processlens.feature.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * A recorded investigation, read back (Sections 13, 24, 25).
 *
 * The series is loaded once and held: it is a finished recording, so re-reading it would
 * only cost battery. The scrubber then indexes into that fixed array rather than
 * re-querying, which is what makes replay feel immediate.
 *
 * Gaps in the series are preserved as nulls the whole way through. A sample where CPU
 * could not be read is a hole in the line, not a zero — Section 42 in the one place
 * where a chart makes it easiest to lie.
 */
@HiltViewModel
class TimelineViewModel @Inject constructor(
    private val investigations: InvestigationRepository,
    private val exporter: ExportInvestigation,
    private val exportStore: ExportStore,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** The route declares this argument as [androidx.navigation.NavType.LongType]. */
    private val investigationId: Long =
        savedStateHandle.get<Long>(Routes.ARG_INVESTIGATION_ID) ?: -1L

    /** Which metric the main chart shows. Kept to what the recording actually stores. */
    enum class Metric(val label: String, val unit: String) {
        CPU("CPU", "%"),
        MEMORY("Memory", "bytes"),
        BATTERY("Battery", "%"),
        TEMPERATURE("Battery temperature", "°C"),
        NETWORK("Network", "bytes/s"),
        OWN_CPU("ProcessLens itself", "%"),
    }

    data class State(
        val investigation: Investigation? = null,
        val summary: InvestigationSummary? = null,
        val events: List<InvestigationEvent> = emptyList(),
        val visibleEvents: List<InvestigationEvent> = emptyList(),
        val series: List<SeriesPoint> = emptyList(),
        val metric: Metric = Metric.CPU,
        val groupFilter: EventGroup? = null,
        val minSeverity: EventSeverity = EventSeverity.INFO,
        /** Index into [series] the scrubber is on, or null when not scrubbing. */
        val scrubIndex: Int? = null,
        val scrubSnapshot: ProcessSnapshot? = null,
        val isLoadingSnapshot: Boolean = false,
        val isLoading: Boolean = true,
        val error: String? = null,
    ) {
        val scrubPoint: SeriesPoint? get() = scrubIndex?.let { series.getOrNull(it) }

        /** The chart series for [metric], with nulls preserved as gaps. */
        val chart: List<Float?>
            get() = series.mapIndexed { index, point ->
                when (metric) {
                    Metric.CPU -> point.cpuPercent
                    Metric.MEMORY -> point.memoryUsedBytes.toFloat()
                    Metric.BATTERY -> point.batteryLevel.toFloat()
                    Metric.TEMPERATURE -> point.batteryTemperatureDeciCelsius?.div(10f)
                    Metric.NETWORK -> networkRateAt(index)
                    Metric.OWN_CPU -> point.ownCpuPercent
                }
            }

        /** Chart ceiling for [metric], derived from the data rather than assumed. */
        val chartMax: Float
            get() = when (metric) {
                Metric.CPU, Metric.BATTERY, Metric.OWN_CPU -> 100f
                Metric.MEMORY -> series.maxOfOrNull { it.memoryUsedBytes.toFloat() }
                    ?.times(1.1f) ?: 1f
                Metric.TEMPERATURE -> 60f
                Metric.NETWORK -> chart.filterNotNull().maxOrNull()
                    ?.coerceAtLeast(1024f) ?: 1024f
            }

        /**
         * Bytes per second between consecutive samples.
         *
         * Derived, not stored: the recording holds cumulative counters, and a rate is
         * only meaningful as a delta over a known interval. Returns null at the first
         * sample and wherever either counter was unreadable.
         */
        private fun networkRateAt(index: Int): Float? {
            if (index <= 0) return null
            val now = series.getOrNull(index) ?: return null
            val prev = series.getOrNull(index - 1) ?: return null
            val nowTotal = (now.networkRxBytes ?: return null) + (now.networkTxBytes ?: return null)
            val prevTotal =
                (prev.networkRxBytes ?: return null) + (prev.networkTxBytes ?: return null)
            val elapsedSeconds = (now.timestamp - prev.timestamp) / 1000f
            if (elapsedSeconds <= 0f) return null
            val delta = nowTotal - prevTotal
            // Counters reset when an interface goes down; a negative delta is a reset,
            // not negative traffic, so it becomes a gap.
            if (delta < 0L) return null
            return delta / elapsedSeconds
        }
    }

    private val metric = MutableStateFlow(Metric.CPU)
    private val groupFilter = MutableStateFlow<EventGroup?>(null)
    private val minSeverity = MutableStateFlow(EventSeverity.INFO)
    private val scrubIndex = MutableStateFlow<Int?>(null)
    private val scrubSnapshot = MutableStateFlow<ProcessSnapshot?>(null)
    private val loadingSnapshot = MutableStateFlow(false)
    private val series = MutableStateFlow<List<SeriesPoint>>(emptyList())
    private val summary = MutableStateFlow<InvestigationSummary?>(null)
    private val loading = MutableStateFlow(true)
    private val errors = MutableStateFlow<String?>(null)

    private data class Filters(
        val metric: Metric,
        val group: EventGroup?,
        val severity: EventSeverity,
    )

    private data class Scrub(
        val index: Int?,
        val snapshot: ProcessSnapshot?,
        val loading: Boolean,
    )

    val state: StateFlow<State> = combine(
        combine(
            investigations.observeById(investigationId),
            investigations.observeEvents(investigationId),
        ) { investigation, events -> investigation to events },
        combine(metric, groupFilter, minSeverity) { m, g, s -> Filters(m, g, s) },
        combine(scrubIndex, scrubSnapshot, loadingSnapshot) { i, s, l -> Scrub(i, s, l) },
        combine(series, summary) { s, sum -> s to sum },
        combine(loading, errors) { l, e -> l to e },
    ) { record, filters, scrub, data, status ->
        val events = record.second
        State(
            investigation = record.first,
            summary = data.second,
            events = events,
            visibleEvents = events.filter { event ->
                (filters.group == null || event.type.group == filters.group) &&
                    event.severity.order >= filters.severity.order
            },
            series = data.first,
            metric = filters.metric,
            groupFilter = filters.group,
            minSeverity = filters.severity,
            scrubIndex = scrub.index,
            scrubSnapshot = scrub.snapshot,
            isLoadingSnapshot = scrub.loading,
            isLoading = status.first,
            error = status.second,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = State(),
    )

    init {
        load()
    }

    /**
     * Loads the series and the summary once.
     *
     * The summary is recomputed from the stored snapshots on every open rather than
     * cached, so a summary can never contradict the samples it is drawn from.
     */
    private fun load() {
        viewModelScope.launch {
            series.value = runCatching { investigations.getSeries(investigationId) }
                .onFailure { errors.value = it.message ?: "The recording could not be read" }
                .getOrDefault(emptyList())
            summary.value = runCatching { investigations.buildSummary(investigationId) }
                .onFailure { errors.value = errors.value ?: it.message }
                .getOrNull()
            loading.value = false
        }
    }

    fun setMetric(value: Metric) {
        metric.value = value
    }

    fun setGroup(value: EventGroup?) {
        groupFilter.value = value
    }

    fun setMinSeverity(value: EventSeverity) {
        minSeverity.value = value
    }

    /**
     * Moves the scrubber and loads the full snapshot at that instant.
     *
     * The series row is enough to draw the chart, but the per-process rows live in a
     * separate table and are fetched on demand — loading every snapshot's process list
     * up front would mean holding thousands of rows to show one.
     */
    fun scrubTo(index: Int?) {
        scrubIndex.value = index
        if (index == null) {
            scrubSnapshot.value = null
            return
        }
        val point = series.value.getOrNull(index) ?: return
        loadingSnapshot.value = true
        viewModelScope.launch {
            scrubSnapshot.value = runCatching {
                investigations.getSnapshotAt(investigationId, point.timestamp)
            }.getOrNull()
            loadingSnapshot.value = false
        }
    }

    /** Jumps the scrubber to the sample nearest an event's timestamp. */
    fun scrubToTimestamp(timestamp: Long) {
        val list = series.value
        if (list.isEmpty()) return
        var best = 0
        var bestDelta = Long.MAX_VALUE
        list.forEachIndexed { index, point ->
            val delta = kotlin.math.abs(point.timestamp - timestamp)
            if (delta < bestDelta) {
                bestDelta = delta
                best = index
            }
        }
        scrubTo(best)
    }

    fun dismissError() {
        errors.value = null
    }

    // ------------------------------------------------------------------ Export

    /**
     * Export state, deliberately outside [state].
     *
     * Partly because `combine` already has its five sources there, but mostly because
     * an export is an action with an outcome rather than a property of the recording.
     * Folding it into the screen state would mean every chart recomposition carried an
     * export result around with it.
     */
    data class ExportState(
        val isExporting: Boolean = false,
        val outcome: Outcome? = null,
    )

    sealed interface Outcome {
        /** [shareable] is false when local-only mode is on; the file still exists. */
        data class Written(
            val fileName: String,
            val displayPath: String,
            val byteCount: Long,
            val formatLabel: String,
            val sampleCount: Int,
            val eventCount: Int,
            val shareable: Boolean,
            val omissionNote: String?,
        ) : Outcome

        data class Failed(val message: String, val technical: String?) : Outcome
    }

    private val exporting = MutableStateFlow(false)
    private val outcome = MutableStateFlow<Outcome?>(null)
    private var lastWritten: ExportStore.Written? = null

    val exportState: StateFlow<ExportState> =
        combine(exporting, outcome) { busy, result -> ExportState(busy, result) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = ExportState(),
            )

    /** Writes this recording out in [format] (Section 26). */
    fun export(format: ExportFormat) {
        if (exporting.value) return
        exporting.value = true
        outcome.value = null
        viewModelScope.launch {
            when (val result = exporter.export(investigationId, format)) {
                is ExportInvestigation.Result.Success -> {
                    lastWritten = result.written
                    outcome.value = Outcome.Written(
                        fileName = result.written.fileName,
                        displayPath = result.written.displayPath,
                        byteCount = result.written.byteCount,
                        formatLabel = result.format.label,
                        sampleCount = result.sampleCount,
                        eventCount = result.eventCount,
                        shareable = result.shareable,
                        omissionNote = result.omissionNote,
                    )
                }

                is ExportInvestigation.Result.Failure -> {
                    lastWritten = null
                    outcome.value = Outcome.Failed(result.message, result.technical)
                }
            }
            exporting.value = false
        }
    }

    /**
     * The chooser for the export just written, or null when there is nothing to share.
     *
     * Built here rather than in the composable so the FileProvider authority and the
     * URI grant flags live in one place. Returns null in local-only mode even if a
     * caller asks, so the setting cannot be bypassed by a stale button.
     */
    fun shareIntent(): Intent? {
        val written = lastWritten ?: return null
        val current = outcome.value
        if (current !is Outcome.Written || !current.shareable) return null
        return exportStore.shareIntent(written)
    }

    fun dismissExport() {
        outcome.value = null
    }
}
