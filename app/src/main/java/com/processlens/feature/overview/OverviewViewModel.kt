package com.processlens.feature.overview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.processlens.core.common.Observed
import com.processlens.core.common.valueOrNull
import com.processlens.domain.model.Favorite
import com.processlens.domain.model.InvestigationEvent
import com.processlens.domain.model.SystemCapabilities
import com.processlens.domain.model.UserSettings
import com.processlens.domain.repository.FavoritesRepository
import com.processlens.domain.repository.InvestigationRepository
import com.processlens.domain.repository.SettingsRepository
import com.processlens.domain.repository.SystemRepository
import com.processlens.domain.repository.SystemState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Dashboard state (Sections 5, 6).
 *
 * Holds a bounded history of the samples it has actually seen, so the dashboard can
 * draw a trend without a second polling loop. The history is **only** what this
 * screen observed while it was open — there is no back-filling and no interpolation.
 * A sample whose CPU reading was restricted is stored as `null` and drawn as a gap
 * (Section 42).
 */
@HiltViewModel
class OverviewViewModel @Inject constructor(
    private val systemRepository: SystemRepository,
    private val settingsRepository: SettingsRepository,
    investigationRepository: InvestigationRepository,
    favoritesRepository: FavoritesRepository,
) : ViewModel() {

    data class State(
        val system: SystemState? = null,
        val capabilities: SystemCapabilities? = null,
        val settings: UserSettings = UserSettings(),
        val recentEvents: List<InvestigationEvent> = emptyList(),
        val favorites: List<Favorite> = emptyList(),
        val isRecording: Boolean = false,
        val activeInvestigationId: Long? = null,
        /** Oldest first. Null entries are samples where CPU was not readable. */
        val cpuHistory: List<Float?> = emptyList(),
        val memoryHistory: List<Float?> = emptyList(),
        val isRefreshing: Boolean = false,
        val error: String? = null,
    ) {
        val isFirstLoad: Boolean get() = system == null
    }

    private val history = MutableStateFlow(History())

    private data class History(
        val cpu: List<Float?> = emptyList(),
        val memory: List<Float?> = emptyList(),
    ) {
        /**
         * 90 points at the default two-second rate is three minutes of context, which
         * is what a dashboard trend needs. Bounded on purpose: an unbounded list on a
         * screen left open overnight is a slow leak, and Section 43 makes the tool's
         * own footprint a requirement rather than an afterthought.
         */
        fun plus(cpuPercent: Float?, memoryFraction: Float?): History = History(
            cpu = (cpu + cpuPercent).takeLast(CAPACITY),
            memory = (memory + memoryFraction).takeLast(CAPACITY),
        )

        companion object {
            const val CAPACITY = 90
        }
    }

    private val refreshing = MutableStateFlow(false)
    private val errors = MutableStateFlow<String?>(null)

    val state: StateFlow<State> = combine(
        systemRepository.observeSystemState().onEachRecordHistory(),
        systemRepository.observeCapabilities(),
        settingsRepository.observe(),
        investigationRepository.observeActive(),
        investigationRepository.observeRecentEvents(RECENT_EVENT_LIMIT),
    ) { system, capabilities, settings, active, events ->
        Quint(system, capabilities, settings, active, events)
    }.combine(favoritesRepository.observeAll()) { quint, favorites ->
        quint to favorites
    }.combine(history) { (quint, favorites), hist ->
        State(
            system = quint.system,
            capabilities = quint.capabilities,
            settings = quint.settings,
            recentEvents = quint.events,
            favorites = favorites.take(FAVORITE_LIMIT),
            isRecording = quint.active?.isRunning == true,
            activeInvestigationId = quint.active?.id,
            cpuHistory = hist.cpu,
            memoryHistory = hist.memory,
        )
    }.combine(refreshing) { state, isRefreshing ->
        state.copy(isRefreshing = isRefreshing)
    }.combine(errors) { state, error ->
        state.copy(error = error)
    }.stateIn(
        scope = viewModelScope,
        // The polling flow is cold: this stops it when the screen leaves and lets it
        // survive a rotation without a restart.
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = State(),
    )

    private data class Quint(
        val system: SystemState,
        val capabilities: SystemCapabilities,
        val settings: UserSettings,
        val active: com.processlens.domain.model.Investigation?,
        val events: List<InvestigationEvent>,
    )

    /**
     * Appends each observed sample to the history as it passes through.
     *
     * Done as a side-effect on the flow rather than in a separate collector so the
     * history and the displayed reading can never come from different ticks.
     */
    private fun Flow<SystemState>.onEachRecordHistory(): Flow<SystemState> = onEach { state ->
        history.value = history.value.plus(
            cpuPercent = state.cpu.overallPercent.valueOrNull,
            memoryFraction = state.memory.usedFraction * 100f,
        )
    }

    /** Manual refresh, for the pull gesture and for `RefreshRate.MANUAL`. */
    fun refresh() {
        viewModelScope.launch {
            refreshing.value = true
            errors.value = null
            try {
                val fresh = systemRepository.readSystemState()
                history.value = history.value.plus(
                    cpuPercent = fresh.cpu.overallPercent.valueOrNull,
                    memoryFraction = fresh.memory.usedFraction * 100f,
                )
            } catch (t: Throwable) {
                errors.value = t.message ?: "Could not read system state"
            } finally {
                refreshing.value = false
            }
        }
    }

    /** Re-probes access after the user grants Shizuku or changes a setting. */
    fun revalidateAccess() {
        viewModelScope.launch {
            runCatching { systemRepository.invalidateAccess() }
            runCatching { systemRepository.refreshCapabilities() }
        }
    }

    fun dismissError() {
        errors.value = null
    }

    private companion object {
        const val RECENT_EVENT_LIMIT = 6
        const val FAVORITE_LIMIT = 6
    }
}

/**
 * Convenience for the dashboard's "how sure are we?" line: the number of headline
 * figures that are real readings rather than restrictions.
 */
fun countAvailable(vararg observations: Observed<*>): Int =
    observations.count { it is Observed.Value }
