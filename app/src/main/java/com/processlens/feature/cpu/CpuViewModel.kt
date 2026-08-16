package com.processlens.feature.cpu

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.processlens.core.common.valueOrNull
import com.processlens.domain.model.SystemCapabilities
import com.processlens.domain.model.UserSettings
import com.processlens.domain.repository.ProcessRepository
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.stateIn
import com.processlens.domain.model.ProcessInfo
import javax.inject.Inject

/**
 * CPU screen state (Section 7).
 *
 * Keeps per-core history as a list of lists so the per-core chart can show each core's
 * own trend. A core that went offline mid-session contributes a `null`, which the chart
 * renders as a gap — an offline core is not a core at 0% (Section 42).
 */
@HiltViewModel
class CpuViewModel @Inject constructor(
    private val systemRepository: SystemRepository,
    processRepository: ProcessRepository,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    data class State(
        val system: SystemState? = null,
        val capabilities: SystemCapabilities? = null,
        val settings: UserSettings = UserSettings(),
        val history: List<Float?> = emptyList(),
        /** Index by core, then by sample. Empty when per-core data is unavailable. */
        val perCoreHistory: List<List<Float?>> = emptyList(),
        val topConsumers: List<ProcessInfo> = emptyList(),
        val error: String? = null,
    )

    private val history = MutableStateFlow<List<Float?>>(emptyList())
    private val perCore = MutableStateFlow<List<List<Float?>>>(emptyList())
    private val errors = MutableStateFlow<String?>(null)

    val state: StateFlow<State> = combine(
        systemRepository.observeSystemState().recordHistory(),
        systemRepository.observeCapabilities(),
        settingsRepository.observe(),
        combine(history, perCore, errors) { h, p, e -> Triple(h, p, e) },
        processRepository.observeProcesses(),
    ) { system, capabilities, settings, hist, processes ->
        State(
            system = system,
            capabilities = capabilities,
            settings = settings,
            history = hist.first,
            perCoreHistory = hist.second,
            topConsumers = processes.processes
                .filter { it.cpuPercent.valueOrNull != null }
                .sortedByDescending { it.cpuPercent.valueOrNull }
                .take(TOP_LIMIT),
            error = hist.third,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = State(),
    )

    private fun Flow<SystemState>.recordHistory(): Flow<SystemState> = onEach { state ->
        history.value = (history.value + state.cpu.overallPercent.valueOrNull).takeLast(CAPACITY)

        val cores = state.cpu.perCorePercent.valueOrNull
        if (cores != null) {
            val existing = perCore.value
            perCore.value = List(cores.size) { index ->
                val previous = existing.getOrNull(index).orEmpty()
                (previous + cores[index]).takeLast(CAPACITY)
            }
        } else if (perCore.value.isNotEmpty()) {
            // Per-core reading stopped being available: append a gap to every core
            // rather than dropping the series, so the break is visible.
            perCore.value = perCore.value.map { (it + null).takeLast(CAPACITY) }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            errors.value = null
            runCatching { systemRepository.readSystemState() }
                .onFailure { errors.value = it.message ?: "Could not read CPU state" }
        }
    }

    fun dismissError() {
        errors.value = null
    }

    private companion object {
        const val CAPACITY = 120
        const val TOP_LIMIT = 5
    }
}
