package com.processlens.feature.processes

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.processlens.core.common.Observed
import com.processlens.domain.model.ThreadInfo
import com.processlens.domain.repository.ProcessRepository
import com.processlens.domain.repository.ProcessTreeNode
import com.processlens.feature.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The process tree (Section 12).
 *
 * The tree is [Observed] as a whole, not per node: either real parent PIDs were
 * readable and the structure is genuine, or they were not and there is no tree. It is
 * never approximated from process names, because a name-based tree would assert
 * relationships Android did not tell us about (Section 42).
 */
@HiltViewModel
class ProcessTreeViewModel @Inject constructor(
    private val processRepository: ProcessRepository,
) : ViewModel() {

    data class State(
        val tree: Observed<List<ProcessTreeNode>>? = null,
        val expanded: Set<String> = emptySet(),
        val isLoading: Boolean = true,
    ) {
        val nodeCount: Int
            get() = (tree as? Observed.Value)?.value?.sumOf { 1 + it.descendantCount } ?: 0
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true)
            val tree = runCatching { processRepository.getProcessTree() }
                .getOrElse { Observed.Failed("Could not build the tree", it.message) }
            _state.value = _state.value.copy(
                tree = tree,
                isLoading = false,
                // Roots start expanded: a tree whose every branch is collapsed shows
                // the user nothing they did not already have on the flat list.
                expanded = (tree as? Observed.Value)
                    ?.value
                    ?.map { it.process.id }
                    ?.toSet()
                    ?: emptySet(),
            )
        }
    }

    fun toggle(id: String) {
        val current = _state.value.expanded
        _state.value = _state.value.copy(
            expanded = if (id in current) current - id else current + id,
        )
    }
}

/**
 * Threads of one process (Section 11).
 *
 * The PID arrives as a navigation argument rather than being looked up, because the
 * caller already had a real PID in hand — re-deriving it here would risk showing
 * threads of a recycled PID after the original process died.
 */
@HiltViewModel
class ThreadsViewModel @Inject constructor(
    private val processRepository: ProcessRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val pid: Int = savedStateHandle.get<Int>(Routes.ARG_PID) ?: -1

    data class State(
        val pid: Int = -1,
        val threads: Observed<List<ThreadInfo>>? = null,
        val isLoading: Boolean = true,
    )

    private val _state = MutableStateFlow(State(pid = pid))
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true)
            val threads = if (pid <= 0) {
                Observed.Failed("No process identifier was supplied")
            } else {
                runCatching { processRepository.getThreads(pid) }
                    .getOrElse { Observed.Failed("Could not read threads", it.message) }
            }
            _state.value = _state.value.copy(threads = threads, isLoading = false)
        }
    }
}
