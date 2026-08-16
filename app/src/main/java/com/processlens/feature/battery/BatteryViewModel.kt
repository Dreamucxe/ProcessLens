package com.processlens.feature.battery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.processlens.core.common.Observed
import com.processlens.domain.model.AppBatteryUsage
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
 * Battery screen state (Section 17).
 *
 * Section 17 is the strictest part of the specification: *never fabricate battery
 * health percentages* and *do not fabricate precise battery figures*. The consequence
 * for this view model is that it computes almost nothing. Level, temperature, voltage
 * and current arrive as [Observed] from the platform and are passed through untouched;
 * per-app attribution comes from `dumpsys batterystats`, which is the platform's own
 * arithmetic against its own power profile, and is only reachable with elevated
 * access.
 *
 * The one derived figure is the observed drain over this session — and it is derived
 * from two real level readings taken at two real timestamps, labelled as such, and
 * withheld until there is an actual change to report. There is deliberately no
 * "time remaining" estimate anywhere: that would require a discharge model this app
 * has no basis for.
 */
@HiltViewModel
class BatteryViewModel @Inject constructor(
    private val systemRepository: SystemRepository,
) : ViewModel() {

    data class State(
        val system: SystemState? = null,
        val capabilities: SystemCapabilities? = null,
        /** Battery percentage per sample, oldest first. */
        val levelHistory: List<Float?> = emptyList(),
        /** Deci-Celsius per sample. Null entries are samples with no reading. */
        val temperatureHistory: List<Float?> = emptyList(),
        val perApp: Observed<List<AppBatteryUsage>>? = null,
        val isLoadingPerApp: Boolean = false,
        /** First (level, timestamp) this screen saw, for the observed-drain figure. */
        val firstReading: Pair<Int, Long>? = null,
        val error: String? = null,
    ) {
        /**
         * Percentage points lost since this screen opened, or null when nothing has
         * changed yet or the battery has been charging.
         *
         * Returns null rather than 0 on purpose: "0% drain" after four seconds is a
         * meaningless claim, and Section 17 would rather show nothing.
         */
        val observedDrainPercent: Int?
            get() {
                val (startLevel, _) = firstReading ?: return null
                val now = system?.battery?.levelPercent ?: return null
                val delta = startLevel - now
                return if (delta > 0) delta else null
            }

        val observedWindowMillis: Long?
            get() = firstReading?.let { (system?.timestamp ?: 0L) - it.second }
    }

    private val levelHistory = MutableStateFlow<List<Float?>>(emptyList())
    private val temperatureHistory = MutableStateFlow<List<Float?>>(emptyList())
    private val perApp = MutableStateFlow<Observed<List<AppBatteryUsage>>?>(null)
    private val loadingPerApp = MutableStateFlow(false)
    private val firstReading = MutableStateFlow<Pair<Int, Long>?>(null)
    private val errors = MutableStateFlow<String?>(null)

    val state: StateFlow<State> = combine(
        systemRepository.observeSystemState().recordHistory(),
        systemRepository.observeCapabilities(),
        combine(levelHistory, temperatureHistory) { l, t -> l to t },
        combine(perApp, loadingPerApp) { p, loading -> p to loading },
        combine(firstReading, errors) { f, e -> f to e },
    ) { system, capabilities, histories, appUsage, misc ->
        State(
            system = system,
            capabilities = capabilities,
            levelHistory = histories.first,
            temperatureHistory = histories.second,
            perApp = appUsage.first,
            isLoadingPerApp = appUsage.second,
            firstReading = misc.first,
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
        val battery = state.battery
        if (firstReading.value == null) {
            firstReading.value = battery.levelPercent to state.timestamp
        }
        levelHistory.value =
            (levelHistory.value + battery.levelPercent.toFloat()).takeLast(CAPACITY)
        temperatureHistory.value = (
            temperatureHistory.value +
                battery.temperatureDeciCelsius.let { obs ->
                    (obs as? Observed.Value)?.value?.toFloat()
                }
            ).takeLast(CAPACITY)
    }

    /**
     * Reads the platform's own per-app attribution.
     *
     * A single one-shot read rather than a poll: `dumpsys batterystats` is expensive
     * (hundreds of milliseconds and a large string to parse) and its figures move on
     * the scale of minutes, so polling it would make ProcessLens the drain it is
     * trying to attribute (Section 43).
     */
    fun loadPerApp() {
        viewModelScope.launch {
            loadingPerApp.value = true
            perApp.value = runCatching { systemRepository.getBatteryUsage() }
                .getOrElse { Observed.Failed("Could not read battery statistics", it.message) }
            loadingPerApp.value = false
        }
    }

    fun refresh() {
        viewModelScope.launch {
            errors.value = null
            runCatching { systemRepository.readSystemState() }
                .onFailure { errors.value = it.message ?: "Could not read battery state" }
        }
    }

    fun dismissError() {
        errors.value = null
    }

    private companion object {
        const val CAPACITY = 120
    }
}
