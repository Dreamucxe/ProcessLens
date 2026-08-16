package com.processlens.feature.processes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.processlens.core.common.FuzzyMatch
import com.processlens.core.common.Observed
import com.processlens.core.common.valueOrNull
import com.processlens.domain.model.ProcessImportance
import com.processlens.domain.model.ProcessInfo
import com.processlens.domain.model.UserSettings
import com.processlens.domain.repository.ProcessListResult
import com.processlens.domain.repository.ProcessRepository
import com.processlens.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Process browser state (Sections 9, 10).
 *
 * Sorting and filtering happen here rather than in the composable so a recomposition
 * does not re-sort a thousand rows, and so the ordering is unit-testable without a
 * device.
 *
 * One deliberate subtlety: rows whose metric is [Observed.Restricted] sort **last**
 * in every numeric order, regardless of direction. They are not zero, and putting
 * them at the top of an ascending CPU sort would read as "these processes are idle",
 * which is exactly the false impression Section 42 forbids.
 */
@HiltViewModel
class ProcessListViewModel @Inject constructor(
    private val processRepository: ProcessRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    data class State(
        val result: ProcessListResult? = null,
        val visible: List<ProcessInfo> = emptyList(),
        val settings: UserSettings = UserSettings(),
        val sort: ProcessSort = ProcessSort.CPU,
        val descending: Boolean = true,
        val filter: ProcessFilter = ProcessFilter.ALL,
        val query: String = "",
        val isRefreshing: Boolean = false,
        val error: String? = null,
    ) {
        val isFirstLoad: Boolean get() = result == null
        val totalCount: Int get() = result?.processes?.size ?: 0
        val hiddenCount: Int get() = (totalCount - visible.size).coerceAtLeast(0)
    }

    private val sort = MutableStateFlow(ProcessSort.CPU)
    private val descending = MutableStateFlow(true)
    private val filter = MutableStateFlow(ProcessFilter.ALL)
    private val query = MutableStateFlow("")
    private val refreshing = MutableStateFlow(false)
    private val errors = MutableStateFlow<String?>(null)
    private val manual = MutableStateFlow<ProcessListResult?>(null)

    val state: StateFlow<State> = combine(
        processRepository.observeProcesses(),
        settingsRepository.observe(),
        sort,
        descending,
        combine(filter, query, refreshing, errors, manual) { f, q, r, e, m -> Quint(f, q, r, e, m) },
    ) { result, settings, sortBy, desc, extra ->
        val effective = extra.manual ?: result
        State(
            result = effective,
            visible = arrange(
                processes = effective.processes,
                sort = sortBy,
                descending = desc,
                filter = extra.filter,
                query = extra.query,
                showSystem = settings.showSystemProcesses,
            ),
            settings = settings,
            sort = sortBy,
            descending = desc,
            filter = extra.filter,
            query = extra.query,
            isRefreshing = extra.refreshing,
            error = extra.error,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = State(),
    )

    private data class Quint(
        val filter: ProcessFilter,
        val query: String,
        val refreshing: Boolean,
        val error: String?,
        val manual: ProcessListResult?,
    )

    fun setSort(value: ProcessSort) {
        if (sort.value == value) {
            descending.value = !descending.value
        } else {
            sort.value = value
            // Sensible default per column: biggest CPU first, but names A–Z.
            descending.value = value.defaultDescending
        }
    }

    fun setFilter(value: ProcessFilter) {
        filter.value = value
    }

    fun setQuery(value: String) {
        query.value = value
    }

    fun refresh() {
        viewModelScope.launch {
            refreshing.value = true
            errors.value = null
            try {
                manual.value = processRepository.readProcesses()
            } catch (t: Throwable) {
                errors.value = t.message ?: "Could not read the process list"
            } finally {
                refreshing.value = false
            }
        }
    }

    fun dismissError() {
        errors.value = null
    }

    companion object {

        /**
         * Filter, then search, then sort. Pure and top-level so the ordering rules are
         * directly testable.
         */
        fun arrange(
            processes: List<ProcessInfo>,
            sort: ProcessSort,
            descending: Boolean,
            filter: ProcessFilter,
            query: String,
            showSystem: Boolean,
        ): List<ProcessInfo> {
            var working = processes.asSequence()

            if (!showSystem) working = working.filter { !it.isSystem }

            working = when (filter) {
                ProcessFilter.ALL -> working
                ProcessFilter.USER -> working.filter { !it.isSystem }
                ProcessFilter.SYSTEM -> working.filter { it.isSystem }
                ProcessFilter.FOREGROUND -> working.filter { it.importance.isForeground }
                ProcessFilter.BACKGROUND -> working.filter { it.importance.isBackground }
                ProcessFilter.CACHED -> working.filter { it.importance == ProcessImportance.CACHED }
                ProcessFilter.WITH_METRICS -> working.filter { it.hasMetrics }
            }

            val trimmed = query.trim()
            if (trimmed.isNotEmpty()) {
                // Fuzzy, and ranked by score: typing "gms" should surface
                // com.google.android.gms above a process that merely contains those
                // letters far apart.
                return working
                    .mapNotNull { process ->
                        val score = FuzzyMatch.bestScore(
                            trimmed,
                            process.displayName,
                            process.processName,
                            process.packageName,
                        )
                        if (score > 0) process to score else null
                    }
                    .sortedWith(compareByDescending<Pair<ProcessInfo, Int>> { it.second }
                        .thenBy { it.first.displayName.lowercase() })
                    .map { it.first }
                    .toList()
            }

            val comparator = sort.comparator(descending)
            return working.sortedWith(comparator).toList()
        }
    }
}

/**
 * Sort columns (Section 9).
 *
 * Each numeric column extracts a nullable key. Null means "not readable", and the
 * comparator always sinks nulls to the bottom — see [ProcessListViewModel].
 */
enum class ProcessSort(val label: String, val defaultDescending: Boolean) {
    CPU("CPU", true),
    MEMORY("Memory", true),
    NAME("Name", false),
    PID("PID", false),
    THREADS("Threads", true),
    START_TIME("Started", true),
    IMPORTANCE("State", false),
    ;

    fun comparator(descending: Boolean): Comparator<ProcessInfo> = when (this) {
        CPU -> numeric(descending) { it.cpuPercent.valueOrNull?.toDouble() }
        MEMORY -> numeric(descending) { it.memoryBytes.valueOrNull?.toDouble() }
        THREADS -> numeric(descending) { it.threadCount.valueOrNull?.toDouble() }
        PID -> numeric(descending) { it.pid.valueOrNull?.toDouble() }
        START_TIME -> numeric(descending) { it.startTimeMillis.valueOrNull?.toDouble() }
        NAME -> text(descending) { it.displayName.lowercase() }
        IMPORTANCE -> {
            val base = compareBy<ProcessInfo> { it.importance.order }
                .thenBy { it.displayName.lowercase() }
            if (descending) base.reversed() else base
        }
    }

    private inline fun numeric(
        descending: Boolean,
        crossinline key: (ProcessInfo) -> Double?,
    ): Comparator<ProcessInfo> = Comparator { a, b ->
        val ka = key(a)
        val kb = key(b)
        when {
            ka == null && kb == null -> a.displayName.compareTo(b.displayName, ignoreCase = true)
            // Unreadable metrics sink to the bottom in both directions. A restricted
            // value is not a small value.
            ka == null -> 1
            kb == null -> -1
            else -> {
                val c = ka.compareTo(kb)
                if (descending) -c else c
            }
        }
    }

    private inline fun text(
        descending: Boolean,
        crossinline key: (ProcessInfo) -> String,
    ): Comparator<ProcessInfo> = Comparator { a, b ->
        val c = key(a).compareTo(key(b))
        if (descending) -c else c
    }
}

enum class ProcessFilter(val label: String) {
    ALL("All"),
    USER("User apps"),
    SYSTEM("System"),
    FOREGROUND("Foreground"),
    BACKGROUND("Background"),
    CACHED("Cached"),
    WITH_METRICS("With metrics"),
}
