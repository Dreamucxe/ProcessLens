package com.processlens.feature.investigate

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.processlens.domain.model.AppInfo
import com.processlens.domain.model.Investigation
import com.processlens.domain.model.InvestigationEvent
import com.processlens.domain.model.SystemCapabilities
import com.processlens.domain.model.UserSettings
import com.processlens.domain.repository.AppRepository
import com.processlens.domain.repository.InvestigationRepository
import com.processlens.domain.repository.SettingsRepository
import com.processlens.domain.repository.SystemRepository
import com.processlens.domain.usecase.InvestigationRecorder
import com.processlens.domain.usecase.RecordingState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Investigation Mode (Section 14).
 *
 * This ViewModel does not run the recording. It prepares a row, hands the id to the
 * foreground service, and then watches [InvestigationRecorder.state]. That split is
 * deliberate: a recording that lived in a ViewModel would die when the user navigated
 * away, and a five-minute investigation that silently stops after thirty seconds is a
 * worse outcome than one that never started.
 *
 * The screen is told what the recording *will* be able to see before it begins, from
 * the live capability matrix. Discovering afterwards that WakeLocks were never
 * observable is the kind of surprise Section 42 is meant to prevent.
 */
@HiltViewModel
class InvestigateViewModel @Inject constructor(
    private val recorder: InvestigationRecorder,
    private val investigations: InvestigationRepository,
    private val settings: SettingsRepository,
    appRepository: AppRepository,
    systemRepository: SystemRepository,
) : ViewModel() {

    /**
     * Duration choices.
     *
     * `UNTIL_STOPPED` is a real option rather than a very large number, because a
     * recording with no deadline and one with a 24-hour deadline behave differently
     * when the process is killed.
     */
    enum class Duration(val label: String, val minutes: Int?) {
        TWO("2 min", 2),
        FIVE("5 min", 5),
        FIFTEEN("15 min", 15),
        THIRTY("30 min", 30),
        UNTIL_STOPPED("Until I stop it", null),
    }

    data class State(
        val recording: RecordingState = RecordingState(),
        val settings: UserSettings = UserSettings(),
        val capabilities: SystemCapabilities? = null,
        val history: List<Investigation> = emptyList(),
        val liveEvents: List<InvestigationEvent> = emptyList(),
        val apps: List<AppInfo> = emptyList(),
        val name: String = "",
        val targetPackage: String? = null,
        val duration: Duration = Duration.FIVE,
        val isStarting: Boolean = false,
        val showAppPicker: Boolean = false,
        val appQuery: String = "",
        val error: String? = null,
        /** Set when a recording has just been started, so the caller can navigate. */
        val startedId: Long? = null,
    ) {
        val isRecording: Boolean get() = recording.isRecording
        val targetLabel: String?
            get() = targetPackage?.let { pkg ->
                apps.firstOrNull { it.packageName == pkg }?.label ?: pkg
            }
    }

    private val name = MutableStateFlow("")
    private val targetPackage = MutableStateFlow<String?>(null)
    private val duration = MutableStateFlow(Duration.FIVE)
    private val starting = MutableStateFlow(false)
    private val showPicker = MutableStateFlow(false)
    private val appQuery = MutableStateFlow("")
    private val errors = MutableStateFlow<String?>(null)
    private val startedId = MutableStateFlow<Long?>(null)

    private val apps: StateFlow<List<AppInfo>> = appRepository.observeApps(includeSystem = false)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Events from the recording currently in progress.
     *
     * Keyed off the recorder's live id rather than a stored "active" flag, so that a
     * stale row left behind by a killed process cannot make the screen show old events
     * as though they were happening now. When nothing is recording the list is empty
     * rather than the previous recording's events.
     */
    private val liveEvents: StateFlow<List<InvestigationEvent>> = combine(
        recorder.state.map { it.investigationId }.distinctUntilChanged(),
        investigations.observeRecentEvents(LIVE_EVENT_LIMIT),
    ) { id, recent ->
        if (id == null) emptyList() else recent.filter { it.investigationId == id }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private data class Form(
        val name: String,
        val targetPackage: String?,
        val duration: Duration,
        val starting: Boolean,
        val startedId: Long?,
    )

    private data class Picker(
        val show: Boolean,
        val query: String,
        val error: String?,
    )

    val state: StateFlow<State> = combine(
        combine(recorder.state, settings.observe(), systemRepository.observeCapabilities()) {
                recording, prefs, capabilities ->
            Triple(recording, prefs, capabilities)
        },
        combine(name, targetPackage, duration, starting, startedId) { n, pkg, d, s, id ->
            Form(n, pkg, d, s, id)
        },
        combine(showPicker, appQuery, errors) { show, q, e -> Picker(show, q, e) },
        investigations.observeAll(),
        combine(apps, liveEvents) { list, events -> list to events },
    ) { live, form, picker, history, appsAndEvents ->
        State(
            recording = live.first,
            settings = live.second,
            capabilities = live.third,
            history = history,
            liveEvents = appsAndEvents.second,
            apps = appsAndEvents.first,
            name = form.name,
            targetPackage = form.targetPackage,
            duration = form.duration,
            isStarting = form.starting,
            showAppPicker = picker.show,
            appQuery = picker.query,
            error = picker.error,
            startedId = form.startedId,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = State(),
    )

    init {
        // A recording interrupted by process death leaves a RECORDING row behind. Mark
        // those INTERRUPTED on launch: their data is real and worth keeping, but the
        // list must not claim they are still running.
        viewModelScope.launch { runCatching { investigations.reconcileStaleRecordings() } }
    }

    fun setName(value: String) {
        name.value = value
    }

    fun setDuration(value: Duration) {
        duration.value = value
    }

    fun setTarget(packageName: String?) {
        targetPackage.value = packageName
        showPicker.value = false
        appQuery.value = ""
    }

    fun openAppPicker() {
        showPicker.value = true
    }

    fun closeAppPicker() {
        showPicker.value = false
        appQuery.value = ""
    }

    fun setAppQuery(value: String) {
        appQuery.value = value
    }

    /**
     * Creates the row and returns its id, or null on failure.
     *
     * The caller starts the service with the returned id — this ViewModel deliberately
     * does not, so that the Android-specific act of starting a foreground service stays
     * in the composition layer where the context lives.
     *
     * `UNTIL_STOPPED` passes a null duration through unchanged rather than substituting
     * the settings default: the user has explicitly asked for no deadline.
     */
    suspend fun prepare(): Long? {
        if (starting.value || recorder.state.value.isRecording) return null
        starting.value = true
        errors.value = null
        return runCatching {
            recorder.prepare(
                name = name.value.trim(),
                targetPackage = targetPackage.value,
                durationMinutes = duration.value.minutes,
            )
        }.onSuccess { id ->
            startedId.value = id
            name.value = ""
        }.onFailure {
            errors.value = it.message ?: "The recording could not be prepared"
        }.also { starting.value = false }.getOrNull()
    }

    fun clearStarted() {
        startedId.value = null
    }

    fun stop() {
        viewModelScope.launch {
            runCatching { recorder.stop() }
                .onFailure { errors.value = it.message ?: "The recording could not be stopped" }
        }
    }

    fun rename(id: Long, newName: String) {
        viewModelScope.launch {
            runCatching { investigations.rename(id, newName.trim()) }
                .onFailure { errors.value = it.message }
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            runCatching { investigations.delete(id) }
                .onFailure { errors.value = it.message ?: "It could not be deleted" }
        }
    }

    /** The two most recent completed recordings, for the comparison entry point. */
    suspend fun twoMostRecent(): List<Investigation> =
        runCatching {
            investigations.observeAll().first().filter { !it.isRunning }.take(2)
        }.getOrDefault(emptyList())

    fun dismissError() {
        errors.value = null
    }

    private companion object {
        const val LIVE_EVENT_LIMIT = 40
    }
}
