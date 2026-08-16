package com.processlens.feature.applications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.processlens.core.common.FuzzyMatch
import com.processlens.domain.model.AppInfo
import com.processlens.domain.repository.AppRepository
import com.processlens.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Installed-application list (Section 20).
 *
 * "Show system apps" is read from settings rather than kept locally, because it is a
 * persisted user preference shared with the process list — a user who has said they
 * want to see system components should not have to say it again per screen.
 *
 * Ordering is by label, except when a query is present, in which case it is by fuzzy
 * relevance. Sorting a fuzzy search alphabetically would bury the obvious match.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AppListViewModel @Inject constructor(
    private val appRepository: AppRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    enum class Filter(val label: String) {
        ALL("All"),
        USER("Installed"),
        SYSTEM("System"),
        DISABLED("Disabled"),
        DEBUGGABLE("Debuggable"),
    }

    data class State(
        val apps: List<AppInfo>? = null,
        val visible: List<AppInfo> = emptyList(),
        val query: String = "",
        val filter: Filter = Filter.ALL,
        val includeSystem: Boolean = true,
        val error: String? = null,
    ) {
        val isLoading: Boolean get() = apps == null
        val totalCount: Int get() = apps?.size ?: 0
    }

    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow(Filter.ALL)
    private val errors = MutableStateFlow<String?>(null)
    private val reloadTicks = MutableStateFlow(0)

    private val includeSystem: StateFlow<Boolean> = settingsRepository.observe()
        .map { it.showSystemProcesses }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    private val apps: StateFlow<List<AppInfo>?> = combine(
        includeSystem,
        reloadTicks,
    ) { include, _ -> include }
        .flatMapLatest { include -> appRepository.observeApps(include) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val state: StateFlow<State> = combine(
        apps,
        query,
        filter,
        includeSystem,
        errors,
    ) { list, q, f, include, error ->
        State(
            apps = list,
            visible = list?.let { apply(it, q, f) }.orEmpty(),
            query = q,
            filter = f,
            includeSystem = include,
            error = error,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = State(),
    )

    /**
     * Filter, then rank.
     *
     * The label and the package name are both matched, because a user looking for
     * "gms" is searching the package and a user looking for "Photos" is searching the
     * label, and only one of the two is visible on screen.
     */
    private fun apply(list: List<AppInfo>, query: String, filter: Filter): List<AppInfo> {
        val filtered = list.filter { app ->
            when (filter) {
                Filter.ALL -> true
                Filter.USER -> !app.isSystemApp
                Filter.SYSTEM -> app.isSystemApp
                Filter.DISABLED -> !app.isEnabled
                Filter.DEBUGGABLE -> app.isDebuggable
            }
        }
        if (query.isBlank()) return filtered

        return filtered
            .map { it to FuzzyMatch.bestScore(query, it.label, it.packageName) }
            .filter { it.second > 0 }
            .sortedWith(
                compareByDescending<Pair<AppInfo, Int>> { it.second }
                    .thenBy { it.first.label.lowercase() },
            )
            .map { it.first }
    }

    fun setQuery(value: String) {
        query.value = value
    }

    fun setFilter(value: Filter) {
        filter.value = value
    }

    /** Toggles the persisted preference, so the process list agrees with this screen. */
    fun toggleSystemApps() {
        viewModelScope.launch {
            runCatching {
                settingsRepository.update { it.copy(showSystemProcesses = !it.showSystemProcesses) }
            }.onFailure { errors.value = it.message ?: "Could not save that preference" }
        }
    }

    fun refresh() {
        errors.value = null
        reloadTicks.value += 1
    }

    fun dismissError() {
        errors.value = null
    }
}
