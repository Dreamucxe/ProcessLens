package com.processlens.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.processlens.domain.model.SystemCapabilities
import com.processlens.domain.model.UserSettings
import com.processlens.domain.repository.InvestigationRepository
import com.processlens.domain.repository.SettingsRepository
import com.processlens.domain.repository.SystemRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The shell's own state: theme, recording indicator, first-run overlay.
 *
 * Separate from [SettingsViewModel] because it is scoped to the activity and lives
 * for the whole session, whereas the settings screen's view model comes and goes.
 * Both read the same repository, so they cannot disagree.
 */
@HiltViewModel
class AppShellViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val investigationRepository: InvestigationRepository,
    systemRepository: SystemRepository,
) : ViewModel() {

    data class State(
        /**
         * Defaults are used until the first read completes. That is a real default —
         * the same one the repository would return — not a placeholder, so the theme
         * never flashes a wrong colour and then corrects itself.
         */
        val settings: UserSettings = UserSettings(),
        val isRecording: Boolean = false,
        val showOnboarding: Boolean = false,
        val accessSummary: String = "",
        val capabilities: SystemCapabilities? = null,
    )

    val state: StateFlow<State> = combine(
        settingsRepository.observe(),
        investigationRepository.observeActive(),
        systemRepository.observeCapabilities(),
    ) { settings, active, capabilities ->
        State(
            settings = settings,
            isRecording = active?.isRunning == true,
            showOnboarding = !settings.onboardingCompleted,
            accessSummary = summarise(capabilities),
            capabilities = capabilities,
        )
    }.stateIn(
        scope = viewModelScope,
        // Kept alive briefly across configuration changes so a rotation does not
        // re-probe capabilities, which costs a Shizuku round trip.
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = State(),
    )

    fun completeOnboarding() {
        viewModelScope.launch { settingsRepository.markOnboardingComplete() }
    }

    /**
     * One honest sentence about what this device will allow.
     *
     * Phrased from the runtime probe, never from the build-time SDK (Section 47): two
     * devices on the same API level can differ, and this text is the first thing a
     * user reads about what the app can actually see.
     */
    private fun summarise(capabilities: SystemCapabilities): String {
        val full = capabilities.fullCount
        val limited = capabilities.limitedCount
        val unavailable = capabilities.unavailableCount
        val total = full + limited + unavailable
        if (total == 0) return "Checking what this device allows…"
        return buildString {
            append("Android ")
            append(capabilities.apiLevel)
            append(" · ")
            append(capabilities.accessLevel.label)
            append(" access · ")
            append(full)
            append(" of ")
            append(total)
            append(" capabilities fully available")
            if (limited > 0) {
                append(", ")
                append(limited)
                append(" limited")
            }
        }
    }
}
