package com.processlens.feature.processdetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.processlens.core.common.Observed
import com.processlens.core.common.valueOrNull
import com.processlens.domain.model.AppInfo
import com.processlens.domain.model.FavoriteType
import com.processlens.domain.model.ProcessInfo
import com.processlens.domain.model.SystemCapabilities
import com.processlens.domain.model.ThreadInfo
import com.processlens.domain.model.UserSettings
import com.processlens.domain.repository.AppRepository
import com.processlens.domain.repository.FavoritesRepository
import com.processlens.domain.repository.ProcessRepository
import com.processlens.domain.repository.SettingsRepository
import com.processlens.domain.repository.SystemRepository
import com.processlens.feature.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

/**
 * One process in depth (Section 10).
 *
 * Keeps its own short metric history so the detail screen can show a live sparkline
 * without a second polling loop, on the same bounded-list principle as the dashboard.
 *
 * Note what this view model does **not** expose: any method that terminates a process.
 * Section 0.1 forbids a bare kill button, and more fundamentally a normal app cannot
 * kill another process on any supported API level — `killBackgroundProcesses` needs a
 * signature permission and only ever affects our own package. The screen therefore
 * offers the platform's own app-info screen, which is the real mechanism a user has.
 */
@HiltViewModel
class ProcessDetailViewModel @Inject constructor(
    private val processRepository: ProcessRepository,
    private val appRepository: AppRepository,
    private val favoritesRepository: FavoritesRepository,
    systemRepository: SystemRepository,
    settingsRepository: SettingsRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val processId: String =
        Routes.decode(savedStateHandle.get<String>(Routes.ARG_PROCESS_ID).orEmpty())

    data class State(
        val process: ProcessInfo? = null,
        val app: AppInfo? = null,
        val threads: Observed<List<ThreadInfo>>? = null,
        val capabilities: SystemCapabilities? = null,
        val settings: UserSettings = UserSettings(),
        val isFavorite: Boolean = false,
        val cpuHistory: List<Float?> = emptyList(),
        val memoryHistory: List<Float?> = emptyList(),
        val isLoading: Boolean = true,
        val gone: Boolean = false,
        val error: String? = null,
    )

    private val process = MutableStateFlow<ProcessInfo?>(null)
    private val app = MutableStateFlow<AppInfo?>(null)
    private val threads = MutableStateFlow<Observed<List<ThreadInfo>>?>(null)
    private val loading = MutableStateFlow(true)
    private val gone = MutableStateFlow(false)
    private val errors = MutableStateFlow<String?>(null)
    private val cpuHistory = MutableStateFlow<List<Float?>>(emptyList())
    private val memoryHistory = MutableStateFlow<List<Float?>>(emptyList())

    val state: StateFlow<State> = combine(
        combine(process, app, threads, loading) { p, a, t, l -> Quad(p, a, t, l) },
        systemRepository.observeCapabilities(),
        settingsRepository.observe(),
        combine(cpuHistory, memoryHistory, gone, errors) { c, m, g, e -> Hist(c, m, g, e) },
        favoritesRepository.observeIsFavorite(FavoriteType.PROCESS, processId),
    ) { quad, capabilities, settings, hist, favorite ->
        State(
            process = quad.process,
            app = quad.app,
            threads = quad.threads,
            capabilities = capabilities,
            settings = settings,
            isFavorite = favorite,
            cpuHistory = hist.cpu,
            memoryHistory = hist.memory,
            isLoading = quad.loading,
            gone = hist.gone,
            error = hist.error,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = State(),
    )

    private data class Quad(
        val process: ProcessInfo?,
        val app: AppInfo?,
        val threads: Observed<List<ThreadInfo>>?,
        val loading: Boolean,
    )

    private data class Hist(
        val cpu: List<Float?>,
        val memory: List<Float?>,
        val gone: Boolean,
        val error: String?,
    )

    init {
        viewModelScope.launch { poll() }
    }

    /**
     * Re-reads this one process on a timer.
     *
     * Deliberately a single-process read rather than a filter over the whole list: the
     * detail screen needs one row, and walking a few hundred /proc entries every two
     * seconds to find it would make ProcessLens the heaviest process on the device
     * (Section 43).
     */
    private suspend fun poll() {
        var first = true
        while (coroutineContext.isActive) {
            val found = runCatching { processRepository.getProcess(processId) }
                .onFailure { errors.value = it.message ?: "Could not read this process" }
                .getOrNull()

            if (found == null) {
                // A process that has exited is a real observation, not an error.
                if (!first) gone.value = true
                loading.value = false
            } else {
                process.value = found
                gone.value = false
                loading.value = false
                appendHistory(found)
                if (first) {
                    loadApp(found)
                    loadThreads(found)
                }
            }
            first = false
            delay(POLL_MILLIS)
        }
    }

    private fun appendHistory(info: ProcessInfo) {
        cpuHistory.value = (cpuHistory.value + info.cpuPercent.valueOrNull).takeLast(CAPACITY)
        memoryHistory.value = (
            memoryHistory.value + info.memoryBytes.valueOrNull?.let { it / 1_048_576f }
            ).takeLast(CAPACITY)
    }

    private fun loadApp(info: ProcessInfo) {
        val pkg = info.packageName ?: return
        viewModelScope.launch {
            app.value = runCatching { appRepository.getApp(pkg) }.getOrNull()
        }
    }

    private fun loadThreads(info: ProcessInfo) {
        val pid = info.pid.valueOrNull
        if (pid == null) {
            threads.value = Observed.platform(
                "Threads need a real process identifier, which is not readable here",
            )
            return
        }
        viewModelScope.launch {
            threads.value = runCatching { processRepository.getThreads(pid) }
                .getOrElse { Observed.Failed("Could not read threads", it.message) }
        }
    }

    fun toggleFavorite() {
        val info = process.value ?: return
        viewModelScope.launch {
            favoritesRepository.toggle(FavoriteType.PROCESS, processId, info.displayName)
        }
    }

    fun dismissError() {
        errors.value = null
    }

    /**
     * What, honestly, can be done about this process at the current access level.
     *
     * Used to choose between an explanation and a real action — never to render a
     * control that does nothing (Section 2). ProcessLens deliberately has no
     * terminate operation: the repository layer exposes none, because on every
     * supported API level `killBackgroundProcesses` requires a signature permission
     * and `Process.killProcess` only reaches our own UID. The user's real mechanism
     * is the platform's own app-info screen, so that is what the screen offers
     * (Section 59's "closest legitimate alternative").
     */
    fun stopAvailability(info: ProcessInfo?): StopAvailability = when {
        info == null -> StopAvailability.Unknown
        info.isOwnProcess -> StopAvailability.OwnProcess
        info.packageName != null -> StopAvailability.SystemSettingsOnly
        else -> StopAvailability.NoRoute
    }

    enum class StopAvailability {
        /** Our own process. The OS would allow it; the user gains nothing by it. */
        OwnProcess,

        /** Force-stop belongs to the platform. Its app-info screen is the real route. */
        SystemSettingsOnly,

        /** A process with no owning package — nothing a user-space app can offer. */
        NoRoute,
        Unknown,
    }

    private companion object {
        const val POLL_MILLIS = 2_000L
        const val CAPACITY = 60
    }
}
