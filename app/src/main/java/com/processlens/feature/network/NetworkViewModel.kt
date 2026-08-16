package com.processlens.feature.network

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.processlens.core.common.Observed
import com.processlens.core.common.valueOrNull
import com.processlens.domain.model.AppNetworkUsage
import com.processlens.domain.model.SystemCapabilities
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
 * Network screen state (Section 18).
 *
 * There are two entirely separate sources here and conflating them would be a
 * fabrication:
 *
 *  - `TrafficStats` gives *device-wide* byte counters since boot. Cheap, always
 *    available, and attributable to nothing in particular.
 *  - `NetworkStatsManager` gives *per-application* bytes over a time range, but only
 *    with usage access, and on Android 10+ the mobile-data half additionally needs the
 *    phone-state permission.
 *
 * So the screen never presents a per-app figure derived from the device total, and the
 * rate figures are labelled as sampled between two counter reads rather than as a
 * measured line speed.
 */
@HiltViewModel
class NetworkViewModel @Inject constructor(
    private val systemRepository: SystemRepository,
) : ViewModel() {

    /** Window offered for per-app attribution. Real ranges, no "since install". */
    enum class Window(val label: String, val durationMillis: Long) {
        HOUR("Last hour", 60L * 60_000L),
        DAY("Last 24 hours", 24L * 60L * 60_000L),
        WEEK("Last 7 days", 7L * 24L * 60L * 60_000L),
    }

    data class State(
        val system: SystemState? = null,
        val capabilities: SystemCapabilities? = null,
        /** Download rate in bytes/second per sample. Null entries are gaps. */
        val rxHistory: List<Float?> = emptyList(),
        val txHistory: List<Float?> = emptyList(),
        val window: Window = Window.DAY,
        val perApp: Observed<List<AppNetworkUsage>>? = null,
        val isLoadingPerApp: Boolean = false,
        val error: String? = null,
    ) {
        /**
         * The largest rate seen this session, used as the chart ceiling.
         *
         * A fixed ceiling would make every ordinary transfer look like a flat line at
         * the bottom of the chart; scaling to the observed peak is honest as long as
         * the axis is labelled, which it is.
         */
        val rateCeiling: Float
            get() = maxOf(
                rxHistory.filterNotNull().maxOrNull() ?: 0f,
                txHistory.filterNotNull().maxOrNull() ?: 0f,
                MIN_CEILING,
            )
    }

    private val rxHistory = MutableStateFlow<List<Float?>>(emptyList())
    private val txHistory = MutableStateFlow<List<Float?>>(emptyList())
    private val window = MutableStateFlow(Window.DAY)
    private val perApp = MutableStateFlow<Observed<List<AppNetworkUsage>>?>(null)
    private val loadingPerApp = MutableStateFlow(false)
    private val errors = MutableStateFlow<String?>(null)

    val state: StateFlow<State> = combine(
        systemRepository.observeSystemState().recordHistory(),
        systemRepository.observeCapabilities(),
        combine(rxHistory, txHistory) { rx, tx -> rx to tx },
        combine(perApp, loadingPerApp) { p, loading -> p to loading },
        combine(window, errors) { w, e -> w to e },
    ) { system, capabilities, histories, appUsage, misc ->
        State(
            system = system,
            capabilities = capabilities,
            rxHistory = histories.first,
            txHistory = histories.second,
            perApp = appUsage.first,
            isLoadingPerApp = appUsage.second,
            window = misc.first,
            error = misc.second,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = State(),
    )

    init {
        loadPerApp()
    }

    private fun Flow<SystemState>.recordHistory(): Flow<SystemState> = onEach { state ->
        // Null, not zero. A missing rate sample is a hole in the line; zero would be a
        // claim that no bytes moved (Section 42).
        rxHistory.value = (
            rxHistory.value +
                state.network.rxRateBytesPerSecond.valueOrNull?.toFloat()
            ).takeLast(CAPACITY)
        txHistory.value = (
            txHistory.value +
                state.network.txRateBytesPerSecond.valueOrNull?.toFloat()
            ).takeLast(CAPACITY)
    }

    fun selectWindow(next: Window) {
        if (window.value == next) return
        window.value = next
        loadPerApp()
    }

    /**
     * One-shot read of per-app statistics over the selected window.
     *
     * `NetworkStatsManager.querySummary` walks a stats database and is far too
     * expensive to poll (Section 43), and its figures move slowly enough that a manual
     * reload is the honest interaction.
     *
     * The repository takes an *absolute* start timestamp, so the window duration is
     * subtracted from the wall clock here rather than passed through.
     */
    fun loadPerApp() {
        viewModelScope.launch {
            loadingPerApp.value = true
            val since = System.currentTimeMillis() - window.value.durationMillis
            perApp.value = runCatching { systemRepository.getPerAppNetworkUsage(since) }
                .getOrElse {
                    Observed.Failed("Could not read network statistics", it.message)
                }
            loadingPerApp.value = false
        }
    }

    fun refresh() {
        viewModelScope.launch {
            errors.value = null
            runCatching { systemRepository.readSystemState() }
                .onFailure { errors.value = it.message ?: "Could not read network state" }
        }
    }

    /** Re-probes access after the user returns from the usage-access settings page. */
    fun recheckAccess() {
        viewModelScope.launch {
            runCatching {
                systemRepository.invalidateAccess()
                systemRepository.refreshCapabilities()
            }
            loadPerApp()
        }
    }

    fun dismissError() {
        errors.value = null
    }

    private companion object {
        const val CAPACITY = 120

        /** 16 KiB/s, so an idle line does not scale the chart to a single stray byte. */
        const val MIN_CEILING = 16_384f
    }
}
