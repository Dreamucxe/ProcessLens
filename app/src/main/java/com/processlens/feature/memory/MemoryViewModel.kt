package com.processlens.feature.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.processlens.core.common.valueOrNull
import com.processlens.domain.model.ProcessInfo
import com.processlens.domain.model.SystemCapabilities
import com.processlens.domain.repository.ProcessRepository
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
 * Memory screen state (Section 8).
 *
 * The history is stored in bytes rather than as a fraction, because the memory screen's
 * chart is labelled in real units — a percentage would hide that "70% used" means
 * something very different on a 3 GB device than on a 12 GB one.
 */
@HiltViewModel
class MemoryViewModel @Inject constructor(
    private val systemRepository: SystemRepository,
    processRepository: ProcessRepository,
) : ViewModel() {

    data class State(
        val system: SystemState? = null,
        val capabilities: SystemCapabilities? = null,
        /** Used bytes per sample, oldest first. */
        val history: List<Float?> = emptyList(),
        val topConsumers: List<ProcessInfo> = emptyList(),
        val error: String? = null,
    )

    private val history = MutableStateFlow<List<Float?>>(emptyList())
    private val errors = MutableStateFlow<String?>(null)

    val state: StateFlow<State> = combine(
        systemRepository.observeSystemState().recordHistory(),
        systemRepository.observeCapabilities(),
        history,
        errors,
        processRepository.observeProcesses(),
    ) { system, capabilities, hist, error, processes ->
        State(
            system = system,
            capabilities = capabilities,
            history = hist,
            topConsumers = processes.processes
                .filter { it.memoryBytes.valueOrNull != null }
                .sortedByDescending { it.memoryBytes.valueOrNull }
                .take(TOP_LIMIT),
            error = error,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = State(),
    )

    private fun Flow<SystemState>.recordHistory(): Flow<SystemState> = onEach { state ->
        // Total/available always come from ActivityManager, which never fails, so this
        // series has no gaps by construction — unlike CPU.
        history.value = (history.value + state.memory.usedBytes.toFloat()).takeLast(CAPACITY)
    }

    fun refresh() {
        viewModelScope.launch {
            errors.value = null
            runCatching { systemRepository.readSystemState() }
                .onFailure { errors.value = it.message ?: "Could not read memory state" }
        }
    }

    fun dismissError() {
        errors.value = null
    }

    private companion object {
        const val CAPACITY = 120
        const val TOP_LIMIT = 8
    }
}
