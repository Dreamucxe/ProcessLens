package com.processlens.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.processlens.core.common.Formatters
import com.processlens.domain.model.AccentColor
import com.processlens.domain.model.AnimationIntensity
import com.processlens.domain.model.DeviceInfo
import com.processlens.domain.model.ExportFormat
import com.processlens.domain.model.RefreshRate
import com.processlens.domain.model.ThemeMode
import com.processlens.domain.model.UserSettings
import com.processlens.domain.repository.InvestigationRepository
import com.processlens.domain.repository.SettingsRepository
import com.processlens.domain.repository.SystemRepository
import com.processlens.domain.usecase.ExportInvestigation
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Settings (Sections 40, 41).
 *
 * Every control here writes through [SettingsRepository.update], which persists to the
 * single-row settings table — so a preference survives a process death, and a schema
 * migration cannot silently reset it (Section 44).
 *
 * Two of these settings are not preferences in the usual sense and are treated
 * accordingly. Turning off Shizuku or root does not merely hide a badge: it stops
 * ProcessLens from using that access at all, and the figures it unlocked go back to
 * reading "Not available". The screen says so rather than letting the user discover it.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val investigations: InvestigationRepository,
    private val systemRepository: SystemRepository,
    private val exporter: ExportInvestigation,
) : ViewModel() {

    data class State(
        val settings: UserSettings = UserSettings(),
        val device: DeviceInfo? = null,
        val recordingCount: Int = 0,
        val storedSampleCount: Int = 0,
        /** Bytes held by previously written exports in the app's own cache. */
        val exportCacheBytes: Long = 0L,
        val savingError: String? = null,
        val notice: String? = null,
    ) {
        val thresholdsAreOrdered: Boolean
            get() = settings.cpuWarningThreshold < settings.cpuCriticalThreshold
    }

    private val device = MutableStateFlow<DeviceInfo?>(null)
    private val notices = MutableStateFlow(Notice())
    private val exportBytes = MutableStateFlow(0L)

    private data class Notice(val message: String? = null, val error: String? = null)

    val state: StateFlow<State> = combine(
        settings.observe(),
        device,
        investigations.observeAll(),
        notices,
        exportBytes,
    ) { prefs, deviceInfo, recordings, notice, cacheBytes ->
        State(
            settings = prefs,
            device = deviceInfo,
            recordingCount = recordings.size,
            storedSampleCount = recordings.sumOf { it.snapshotCount },
            exportCacheBytes = cacheBytes,
            savingError = notice.error,
            notice = notice.message,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = State(),
    )

    init {
        viewModelScope.launch {
            device.value = runCatching { systemRepository.getDeviceInfo() }.getOrNull()
        }
        refreshExportSize()
    }

    /**
     * Re-measures the export cache.
     *
     * Called on open and after a clear rather than polled: the size only changes when
     * this screen or an export does something, and a directory walk on a timer is
     * exactly the kind of idle work Section 43 asks this app not to do.
     */
    private fun refreshExportSize() {
        viewModelScope.launch {
            exportBytes.value = runCatching { exporter.cacheUsedBytes() }.getOrDefault(0L)
        }
    }

    /**
     * Deletes the export files.
     *
     * Only the files. The recordings they were rendered from stay, which is why the
     * confirmation wording says so — deleting an export is not deleting evidence.
     */
    fun clearExportCache() {
        viewModelScope.launch {
            val removed = runCatching { exporter.clearExports() }
                .onFailure {
                    notices.value = Notice(error = "The export files could not be deleted.")
                }
                .getOrNull() ?: return@launch
            exportBytes.value = 0L
            notices.value = Notice(
                message = if (removed == 0) {
                    "There were no export files to delete."
                } else {
                    Formatters.count(removed, "export file") +
                        " deleted. The recordings themselves are untouched."
                },
            )
        }
    }

    // ------------------------------------------------------------------ appearance

    fun setThemeMode(value: ThemeMode) = edit { it.copy(themeMode = value) }

    fun setAccent(value: AccentColor) = edit { it.copy(accentColor = value) }

    fun setDynamicColor(value: Boolean) = edit { it.copy(useDynamicColor = value) }

    fun setGlass(value: Boolean) = edit { it.copy(glassEffectEnabled = value) }

    fun setAnimationIntensity(value: AnimationIntensity) =
        edit { it.copy(animationIntensity = value) }

    fun setReducedMotion(value: Boolean) = edit { it.copy(reducedMotion = value) }

    fun setHaptics(value: Boolean) = edit { it.copy(hapticsEnabled = value) }

    fun setHighContrast(value: Boolean) = edit { it.copy(highContrast = value) }

    // ------------------------------------------------------------------ monitoring

    fun setRefreshRate(value: RefreshRate) = edit { it.copy(refreshRate = value) }

    fun setCpuPolling(value: Boolean) = edit { it.copy(cpuPollingEnabled = value) }

    fun setMemoryPolling(value: Boolean) = edit { it.copy(memoryPollingEnabled = value) }

    fun setBatteryPolling(value: Boolean) = edit { it.copy(batteryPollingEnabled = value) }

    fun setNetworkPolling(value: Boolean) = edit { it.copy(networkPollingEnabled = value) }

    /**
     * How many system polls pass between process-list reads.
     *
     * Clamped to 1..10 rather than accepting anything: 0 would divide by zero in the
     * polling loop, and beyond 10 the process list is stale enough to be misleading.
     */
    fun setProcessMultiplier(value: Int) =
        edit { it.copy(processListPollMultiplier = value.coerceIn(1, 10)) }

    // --------------------------------------------------------------- investigation

    fun setDefaultDuration(minutes: Int) =
        edit { it.copy(defaultDurationMinutes = minutes.coerceIn(1, 240)) }

    /**
     * The warning threshold, kept strictly below the critical one.
     *
     * If a user drags warning above critical, every sample above the warning line would
     * also be critical and the warning tier would silently stop existing. Pushing
     * critical up instead keeps both tiers meaningful.
     */
    fun setCpuWarning(value: Int) = edit { prefs ->
        val warning = value.coerceIn(1, 99)
        prefs.copy(
            cpuWarningThreshold = warning,
            cpuCriticalThreshold = prefs.cpuCriticalThreshold.coerceAtLeast(warning + 1)
                .coerceAtMost(100),
        )
    }

    fun setCpuCritical(value: Int) = edit { prefs ->
        val critical = value.coerceIn(2, 100)
        prefs.copy(
            cpuCriticalThreshold = critical,
            cpuWarningThreshold = prefs.cpuWarningThreshold.coerceAtMost(critical - 1)
                .coerceAtLeast(1),
        )
    }

    fun setMemoryIncreaseMb(value: Int) =
        edit { it.copy(memoryIncreaseWarningMb = value.coerceIn(10, 4_096)) }

    fun setBatteryTemperatureWarning(deciCelsius: Int) =
        edit { it.copy(batteryTemperatureWarningDeciCelsius = deciCelsius.coerceIn(300, 600)) }

    fun setAutomaticEventDetection(value: Boolean) =
        edit { it.copy(automaticEventDetection = value) }

    fun setSampleInterval(millis: Long) =
        edit { it.copy(investigationSampleIntervalMillis = millis.coerceIn(1_000L, 60_000L)) }

    // -------------------------------------------------------------------- privacy

    fun setLocalOnly(value: Boolean) = edit { it.copy(localOnlyMode = value) }

    fun setIncludeSystemAppsInExport(value: Boolean) =
        edit { it.copy(includeSystemAppsInExport = value) }

    fun setExportFormat(value: ExportFormat) = edit { it.copy(exportFormat = value) }

    // ------------------------------------------------------------------- advanced

    /**
     * Turning Shizuku off is a real withdrawal of access, not a cosmetic toggle.
     *
     * The access cache is invalidated and the capability matrix re-probed immediately, so
     * the app's own view of what it can see changes at the same moment the setting does.
     */
    fun setShizukuEnabled(value: Boolean) = edit(
        notice = if (value) {
            null
        } else {
            "Shizuku is no longer used. Figures it unlocked now read \"Not available\"."
        },
        reprobe = true,
    ) { it.copy(shizukuEnabled = value) }

    fun setRootEnabled(value: Boolean) = edit(
        notice = if (value) {
            "Root will be used for read-only diagnostics only. Nothing is run until you " +
                "probe for it on the access screen."
        } else {
            "Root is no longer used."
        },
        reprobe = true,
    ) { it.copy(rootEnabled = value) }

    fun setShowOwnUsage(value: Boolean) = edit { it.copy(showOwnResourceUsage = value) }

    fun setShowSystemProcesses(value: Boolean) = edit { it.copy(showSystemProcesses = value) }

    // ---------------------------------------------------------------------- reset

    /** Restores defaults without touching recordings, favourites, or profiles. */
    fun resetToDefaults() {
        viewModelScope.launch {
            runCatching {
                settings.update { current ->
                    // onboardingCompleted is deliberately preserved: resetting preferences
                    // should not re-run first-run setup the user has already dismissed.
                    UserSettings(onboardingCompleted = current.onboardingCompleted)
                }
                systemRepository.invalidateAccess()
                systemRepository.refreshCapabilities()
            }.onSuccess {
                notices.value = Notice(
                    message = "Preferences restored to defaults. Recordings, favourites " +
                        "and observation history were not touched.",
                )
            }.onFailure {
                notices.value = Notice(error = it.message ?: "Settings could not be reset")
            }
        }
    }

    fun dismissNotice() {
        notices.value = Notice()
    }

    /**
     * Persists one change.
     *
     * [reprobe] is off by default. Only the two access toggles change what ProcessLens is
     * willing to use, and re-running the whole capability probe every time someone picks
     * an accent colour would be exactly the wastefulness this app exists to find
     * (Section 43).
     */
    private fun edit(
        notice: String? = null,
        reprobe: Boolean = false,
        transform: (UserSettings) -> UserSettings,
    ) {
        viewModelScope.launch {
            runCatching { settings.update(transform) }
                .onSuccess {
                    if (notice != null) notices.value = Notice(message = notice)
                    if (reprobe) {
                        runCatching {
                            systemRepository.invalidateAccess()
                            systemRepository.refreshCapabilities()
                        }
                    }
                }
                .onFailure {
                    notices.value = Notice(error = it.message ?: "That setting was not saved")
                }
        }
    }
}
