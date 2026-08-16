package com.processlens.feature.compare

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.processlens.core.common.Formatters
import com.processlens.domain.model.EventGroup
import com.processlens.domain.model.Investigation
import com.processlens.domain.model.InvestigationSummary
import com.processlens.domain.repository.InvestigationRepository
import com.processlens.domain.repository.SeriesPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Comparison mode (Section 23).
 *
 * Two recordings, side by side. The hard part is not the arithmetic but the honesty of
 * it: two recordings of different lengths, taken at different access levels, on
 * different sample intervals, are not directly comparable, and this screen has to say
 * so rather than subtract one number from another and present the difference as a
 * finding.
 *
 * So each comparison row carries a comparability verdict, and every derived delta is
 * suppressed — not zeroed — when the underlying figure is missing on either side.
 */
@HiltViewModel
class CompareViewModel @Inject constructor(
    private val investigations: InvestigationRepository,
) : ViewModel() {

    /**
     * How far two recordings can meaningfully be compared.
     *
     * `RATES_ONLY` is the common case: two recordings of different lengths can be
     * compared on averages and peaks but not on totals, because a total is partly a
     * measure of how long the recording ran.
     */
    enum class Comparability(val label: String, val detail: String) {
        DIRECT(
            "Directly comparable",
            "Both recordings used the same sample interval and access level, and their " +
                "durations are within 10% of each other.",
        ),
        RATES_ONLY(
            "Comparable as rates",
            "These recordings differ in length or cadence. Averages and peaks can be " +
                "compared; totals cannot, because a total partly measures how long the " +
                "recording ran.",
        ),
        WEAK(
            "Weakly comparable",
            "These recordings were taken at different access levels, so one could see " +
                "measurements the other could not. Differences may reflect what was " +
                "visible rather than what happened.",
        ),
    }

    /**
     * One comparison line.
     *
     * [left] and [right] are pre-formatted strings and [delta] is null whenever either
     * side is missing. A comparison against a value that was never measured is not a
     * smaller difference — it is no comparison at all.
     */
    data class Row(
        val label: String,
        val left: String,
        val right: String,
        val delta: String?,
        val direction: Direction = Direction.NONE,
        val note: String? = null,
    )

    /** Which way a delta went, expressed as a word and an arrow, never colour alone. */
    enum class Direction(val symbol: String, val label: String) {
        UP("▲", "higher"),
        DOWN("▼", "lower"),
        SAME("=", "unchanged"),
        NONE("", ""),
    }

    data class State(
        val available: List<Investigation> = emptyList(),
        val leftId: Long? = null,
        val rightId: Long? = null,
        val left: Investigation? = null,
        val right: Investigation? = null,
        val leftSummary: InvestigationSummary? = null,
        val rightSummary: InvestigationSummary? = null,
        val leftSeries: List<SeriesPoint> = emptyList(),
        val rightSeries: List<SeriesPoint> = emptyList(),
        val comparability: Comparability? = null,
        val rows: List<Row> = emptyList(),
        val isLoading: Boolean = false,
        val error: String? = null,
    ) {
        val isPaired: Boolean get() = left != null && right != null
        val leftChart: List<Float?> get() = leftSeries.map { it.cpuPercent }
        val rightChart: List<Float?> get() = rightSeries.map { it.cpuPercent }
    }

    private val leftId = MutableStateFlow<Long?>(null)
    private val rightId = MutableStateFlow<Long?>(null)
    private val loaded = MutableStateFlow(Loaded())
    private val loading = MutableStateFlow(false)
    private val errors = MutableStateFlow<String?>(null)

    private data class Loaded(
        val left: Investigation? = null,
        val right: Investigation? = null,
        val leftSummary: InvestigationSummary? = null,
        val rightSummary: InvestigationSummary? = null,
        val leftSeries: List<SeriesPoint> = emptyList(),
        val rightSeries: List<SeriesPoint> = emptyList(),
    )

    val state: StateFlow<State> = combine(
        investigations.observeAll(),
        leftId,
        rightId,
        loaded,
        combine(loading, errors) { l, e -> l to e },
    ) { all, left, right, data, status ->
        val comparable = all.filter { !it.isRunning }
        val verdict = if (data.left != null && data.right != null) {
            classify(data.left, data.right)
        } else {
            null
        }
        State(
            available = comparable,
            leftId = left,
            rightId = right,
            left = data.left,
            right = data.right,
            leftSummary = data.leftSummary,
            rightSummary = data.rightSummary,
            leftSeries = data.leftSeries,
            rightSeries = data.rightSeries,
            comparability = verdict,
            rows = if (data.left != null && data.right != null && verdict != null) {
                buildRows(data, verdict)
            } else {
                emptyList()
            },
            isLoading = status.first,
            error = status.second,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = State(),
    )

    fun selectLeft(id: Long) {
        leftId.value = id
        reload()
    }

    fun selectRight(id: Long) {
        rightId.value = id
        reload()
    }

    fun swap() {
        val previous = leftId.value
        leftId.value = rightId.value
        rightId.value = previous
        reload()
    }

    fun clear() {
        leftId.value = null
        rightId.value = null
        loaded.value = Loaded()
    }

    private fun reload() {
        val left = leftId.value
        val right = rightId.value
        loading.value = true
        viewModelScope.launch {
            val result = runCatching {
                Loaded(
                    left = left?.let { investigations.get(it) },
                    right = right?.let { investigations.get(it) },
                    leftSummary = left?.let { investigations.buildSummary(it) },
                    rightSummary = right?.let { investigations.buildSummary(it) },
                    leftSeries = left?.let { investigations.getSeries(it) }.orEmpty(),
                    rightSeries = right?.let { investigations.getSeries(it) }.orEmpty(),
                )
            }
            result.onSuccess { loaded.value = it }
                .onFailure { errors.value = it.message ?: "One of the recordings could not be read" }
            loading.value = false
        }
    }

    /**
     * Decides how far these two recordings can be compared.
     *
     * Access level is checked first because it is the difference that most changes what
     * the numbers mean: a recording taken with Shizuku sees processes a normal-access
     * recording cannot, and its "highest CPU" may name a process the other never saw.
     */
    private fun classify(left: Investigation, right: Investigation): Comparability {
        if (left.accessLevelName != right.accessLevelName) return Comparability.WEAK
        val sameCadence = left.sampleIntervalMillis == right.sampleIntervalMillis
        val longer = maxOf(left.durationMillis, right.durationMillis).coerceAtLeast(1L)
        val shorter = minOf(left.durationMillis, right.durationMillis)
        val similarLength = shorter.toDouble() / longer.toDouble() >= 0.9
        return if (sameCadence && similarLength) Comparability.DIRECT else Comparability.RATES_ONLY
    }

    /**
     * Builds the comparison table.
     *
     * Totals — event counts, sample counts — are included but marked as
     * duration-dependent whenever the two recordings are not the same length, rather
     * than being silently omitted or silently compared.
     */
    private fun buildRows(data: Loaded, verdict: Comparability): List<Row> {
        val left = data.left ?: return emptyList()
        val right = data.right ?: return emptyList()
        val durationDependent = verdict != Comparability.DIRECT
        val rows = mutableListOf<Row>()

        rows += Row(
            label = "Duration",
            left = Formatters.durationCoarse(left.durationMillis),
            right = Formatters.durationCoarse(right.durationMillis),
            delta = null,
            note = if (durationDependent) "These lengths differ" else null,
        )
        rows += Row(
            label = "Sample interval",
            left = Formatters.duration(left.sampleIntervalMillis),
            right = Formatters.duration(right.sampleIntervalMillis),
            delta = null,
            note = if (left.sampleIntervalMillis != right.sampleIntervalMillis) {
                "Different cadence: the finer recording had more chances to catch a spike"
            } else {
                null
            },
        )
        rows += Row(
            label = "Samples",
            left = left.snapshotCount.toString(),
            right = right.snapshotCount.toString(),
            delta = null,
        )

        rows += intRow(
            label = "Events",
            left = left.eventCount,
            right = right.eventCount,
            note = if (durationDependent) "Depends on duration" else null,
        )

        rows += floatRow(
            label = "Average CPU",
            left = averageOf(data.leftSeries) { it.cpuPercent },
            right = averageOf(data.rightSeries) { it.cpuPercent },
            format = { Formatters.percentValue(it) },
        )
        rows += floatRow(
            label = "Peak CPU",
            left = data.leftSeries.mapNotNull { it.cpuPercent }.maxOrNull(),
            right = data.rightSeries.mapNotNull { it.cpuPercent }.maxOrNull(),
            format = { Formatters.percentValue(it) },
        )
        rows += floatRow(
            label = "Average memory used",
            left = averageOf(data.leftSeries) { it.memoryUsedBytes.toFloat() },
            right = averageOf(data.rightSeries) { it.memoryUsedBytes.toFloat() },
            format = { Formatters.bytes(it.toLong()) },
        )
        rows += floatRow(
            label = "Peak memory used",
            left = data.leftSeries.maxOfOrNull { it.memoryUsedBytes.toFloat() },
            right = data.rightSeries.maxOfOrNull { it.memoryUsedBytes.toFloat() },
            format = { Formatters.bytes(it.toLong()) },
        )

        rows += intRow(
            label = "Battery drained",
            left = data.leftSummary?.batteryDrainPercent,
            right = data.rightSummary?.batteryDrainPercent,
            format = { it.toString() + "%" },
            note = if (durationDependent) "Depends on duration" else null,
        )

        rows += floatRow(
            label = "Average temperature",
            left = averageOf(data.leftSeries) {
                it.batteryTemperatureDeciCelsius?.toFloat()
            },
            right = averageOf(data.rightSeries) {
                it.batteryTemperatureDeciCelsius?.toFloat()
            },
            format = { Formatters.temperature(it.toInt()) },
        )

        rows += Row(
            label = "Highest CPU process",
            left = data.leftSummary?.highestCpuProcess?.label ?: "Nothing measurable",
            right = data.rightSummary?.highestCpuProcess?.label ?: "Nothing measurable",
            delta = null,
        )

        EventGroup.entries.forEach { group ->
            val l = data.leftSummary?.eventCounts?.get(group) ?: 0
            val r = data.rightSummary?.eventCounts?.get(group) ?: 0
            if (l > 0 || r > 0) {
                rows += intRow(
                    label = group.label + " events",
                    left = l,
                    right = r,
                    note = if (durationDependent) "Depends on duration" else null,
                )
            }
        }

        return rows
    }

    private fun averageOf(series: List<SeriesPoint>, select: (SeriesPoint) -> Float?): Float? {
        val present = series.mapNotNull(select)
        return if (present.isEmpty()) null else present.average().toFloat()
    }

    private fun intRow(
        label: String,
        left: Int?,
        right: Int?,
        format: (Int) -> String = { it.toString() },
        note: String? = null,
    ): Row = Row(
        label = label,
        left = left?.let(format) ?: NOT_MEASURED,
        right = right?.let(format) ?: NOT_MEASURED,
        delta = if (left != null && right != null && left != right) {
            format(kotlin.math.abs(right - left))
        } else {
            null
        },
        direction = direction(left?.toFloat(), right?.toFloat()),
        note = note,
    )

    private fun floatRow(
        label: String,
        left: Float?,
        right: Float?,
        format: (Float) -> String,
        note: String? = null,
    ): Row = Row(
        label = label,
        left = left?.let(format) ?: NOT_MEASURED,
        right = right?.let(format) ?: NOT_MEASURED,
        delta = if (left != null && right != null) {
            val diff = kotlin.math.abs(right - left)
            if (diff < 0.0001f) null else format(diff)
        } else {
            null
        },
        direction = direction(left, right),
        note = note,
    )

    private fun direction(left: Float?, right: Float?): Direction = when {
        left == null || right == null -> Direction.NONE
        right > left -> Direction.UP
        right < left -> Direction.DOWN
        else -> Direction.SAME
    }

    fun dismissError() {
        errors.value = null
    }

    companion object {
        const val NOT_MEASURED = "Not measured"
    }
}
