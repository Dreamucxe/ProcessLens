package com.processlens.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.processlens.core.common.DefaultDispatcher
import com.processlens.core.common.FuzzyMatch
import com.processlens.core.common.valueOrNull
import com.processlens.domain.model.AppInfo
import com.processlens.domain.model.Investigation
import com.processlens.domain.model.ProcessInfo
import com.processlens.domain.repository.AppRepository
import com.processlens.domain.repository.InvestigationRepository
import com.processlens.domain.repository.ProcessRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Global fuzzy search (Section 30).
 *
 * Searches only what ProcessLens can genuinely enumerate: installed packages, whatever
 * of the process table this device allows, and the recordings stored locally. Nothing is
 * fetched, and there is no index — the corpus is read once when the screen opens.
 *
 * The process corpus deserves a note. On API 28 and above an ordinary app sees only its
 * own process, so a search for a process name can come back empty while the process is
 * running. The screen states this rather than presenting an empty result as an answer.
 */
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val apps: AppRepository,
    private val processes: ProcessRepository,
    private val investigations: InvestigationRepository,
    @DefaultDispatcher private val computation: CoroutineDispatcher,
) : ViewModel() {

    enum class Scope(val label: String) {
        ALL("Everything"),
        APPS("Apps"),
        PROCESSES("Processes"),
        RECORDINGS("Recordings"),
    }

    /** One hit. [score] is only meaningful relative to the other hits in the same list. */
    sealed interface Hit {
        val score: Int
        val title: String
        val subtitle: String

        data class App(
            val app: AppInfo,
            override val score: Int,
            override val title: String,
            override val subtitle: String,
        ) : Hit

        data class Process(
            val process: ProcessInfo,
            override val score: Int,
            override val title: String,
            override val subtitle: String,
        ) : Hit

        data class Recording(
            val investigation: Investigation,
            override val score: Int,
            override val title: String,
            override val subtitle: String,
        ) : Hit
    }

    data class State(
        val query: String = "",
        val scope: Scope = Scope.ALL,
        val appHits: List<Hit.App> = emptyList(),
        val processHits: List<Hit.Process> = emptyList(),
        val recordingHits: List<Hit.Recording> = emptyList(),
        val corpus: Corpus = Corpus(),
        val isLoading: Boolean = true,
        val error: String? = null,
    ) {
        val hitCount: Int get() = appHits.size + processHits.size + recordingHits.size
        val hasQuery: Boolean get() = query.isNotBlank()
        val isEmptyResult: Boolean get() = hasQuery && hitCount == 0
    }

    /** What was actually searched, so an empty result can be described honestly. */
    data class Corpus(
        val appCount: Int = 0,
        val processCount: Int = 0,
        val recordingCount: Int = 0,
        val processListComplete: Boolean = true,
        val processLimitation: String? = null,
        val readAt: Long? = null,
    )

    private val queryState = MutableStateFlow("")
    private val scopeState = MutableStateFlow(Scope.ALL)
    private val corpusState = MutableStateFlow(Loaded())
    private val loading = MutableStateFlow(true)
    private val errors = MutableStateFlow<String?>(null)

    private data class Loaded(
        val apps: List<AppInfo> = emptyList(),
        val processes: List<ProcessInfo> = emptyList(),
        val recordings: List<Investigation> = emptyList(),
        val processListComplete: Boolean = true,
        val processLimitation: String? = null,
        val readAt: Long? = null,
    )

    /**
     * The text in the field, undebounced.
     *
     * Kept separate from [state] so typing never waits on the search: the field reads
     * this, the ranking reads the debounced copy.
     */
    val query: StateFlow<String> = queryState.asStateFlow()

    val state: StateFlow<State> = combine(
        queryState.debounce(DEBOUNCE_MILLIS),
        scopeState,
        corpusState,
        loading,
        errors,
    ) { text, scope, loaded, isLoading, error ->
        val trimmed = text.trim()
        State(
            query = text,
            scope = scope,
            appHits = if (trimmed.isEmpty() || !scope.includes(Scope.APPS)) {
                emptyList()
            } else {
                rankApps(trimmed, loaded.apps)
            },
            processHits = if (trimmed.isEmpty() || !scope.includes(Scope.PROCESSES)) {
                emptyList()
            } else {
                rankProcesses(trimmed, loaded.processes)
            },
            recordingHits = if (trimmed.isEmpty() || !scope.includes(Scope.RECORDINGS)) {
                emptyList()
            } else {
                rankRecordings(trimmed, loaded.recordings)
            },
            corpus = Corpus(
                appCount = loaded.apps.size,
                processCount = loaded.processes.size,
                recordingCount = loaded.recordings.size,
                processListComplete = loaded.processListComplete,
                processLimitation = loaded.processLimitation,
                readAt = loaded.readAt,
            ),
            isLoading = isLoading,
            error = error,
        )
    }.flowOn(computation).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = State(),
    )

    init {
        reload()
    }

    fun setQuery(value: String) {
        queryState.value = value
    }

    fun setScope(value: Scope) {
        scopeState.value = value
    }

    fun clear() {
        queryState.value = ""
    }

    /**
     * Reads the corpus once.
     *
     * Deliberately not a poll. A search screen that ran a two-second process poll behind
     * it would burn battery for the whole time a user spends typing, which is the exact
     * behaviour ProcessLens exists to find in other apps (Section 43).
     */
    fun reload() {
        loading.value = true
        viewModelScope.launch {
            val appList = runCatching { apps.observeApps(includeSystem = true).first() }
                .getOrDefault(emptyList())
            val processResult = runCatching { processes.readProcesses() }.getOrNull()
            val recordings = runCatching { investigations.observeAll().first() }
                .getOrDefault(emptyList())

            corpusState.value = Loaded(
                apps = appList,
                processes = processResult?.processes.orEmpty(),
                recordings = recordings,
                processListComplete = processResult?.isCompleteList ?: false,
                processLimitation = processResult?.limitationNote,
                readAt = System.currentTimeMillis(),
            )
            loading.value = false
        }
    }

    private fun rankApps(query: String, corpus: List<AppInfo>): List<Hit.App> =
        corpus.mapNotNull { app ->
            val score = FuzzyMatch.bestScore(query, app.label, app.packageName)
            if (score <= 0) {
                null
            } else {
                Hit.App(
                    app = app,
                    score = score,
                    title = app.label,
                    subtitle = app.packageName,
                )
            }
        }.sortedWith(compareByDescending<Hit.App> { it.score }.thenBy { it.title })
            .take(LIMIT_PER_SECTION)

    private fun rankProcesses(query: String, corpus: List<ProcessInfo>): List<Hit.Process> =
        corpus.mapNotNull { process ->
            val score = FuzzyMatch.bestScore(
                query,
                process.processName,
                process.packageName,
                process.appLabel,
            )
            if (score <= 0) {
                null
            } else {
                Hit.Process(
                    process = process,
                    score = score,
                    title = process.displayName,
                    subtitle = subtitleFor(process),
                )
            }
        }.sortedWith(compareByDescending<Hit.Process> { it.score }.thenBy { it.title })
            .take(LIMIT_PER_SECTION)

    /**
     * The one-line description under a process hit.
     *
     * The PID is printed only when it was actually observed. A hit discovered by name
     * alone — which is what /proc gives when the numeric directory is unreadable — says
     * so rather than showing a plausible-looking number.
     */
    private fun subtitleFor(process: ProcessInfo): String {
        val pid = process.pid.valueOrNull
        val prefix = if (pid != null) "PID $pid" else "PID not readable"
        return prefix + "  ·  " + process.importance.label
    }

    private fun rankRecordings(query: String, corpus: List<Investigation>): List<Hit.Recording> =
        corpus.mapNotNull { investigation ->
            val score = FuzzyMatch.bestScore(
                query,
                investigation.name,
                investigation.targetPackage,
                investigation.deviceLabel,
            )
            if (score <= 0) {
                null
            } else {
                Hit.Recording(
                    investigation = investigation,
                    score = score,
                    title = investigation.name,
                    subtitle = investigation.state.label + "  ·  " +
                        investigation.snapshotCount + " samples",
                )
            }
        }.sortedWith(compareByDescending<Hit.Recording> { it.score }.thenBy { it.title })
            .take(LIMIT_PER_SECTION)

    fun dismissError() {
        errors.value = null
    }

    private fun Scope.includes(other: Scope): Boolean = this == Scope.ALL || this == other

    companion object {
        private const val DEBOUNCE_MILLIS = 140L
        const val LIMIT_PER_SECTION = 40
    }
}
