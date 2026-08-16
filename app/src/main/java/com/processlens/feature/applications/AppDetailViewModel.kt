package com.processlens.feature.applications

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.processlens.core.common.Observed
import com.processlens.domain.model.AppComponents
import com.processlens.domain.model.AppProfile
import com.processlens.domain.model.FavoriteType
import com.processlens.domain.model.ObservationHistory
import com.processlens.domain.model.PermissionInfo
import com.processlens.domain.model.ServiceInfo
import com.processlens.domain.model.SystemCapabilities
import com.processlens.domain.repository.AppRepository
import com.processlens.domain.repository.FavoritesRepository
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
 * One application in depth (Sections 19–22).
 *
 * Four separate reads with four separate failure modes, kept separate on purpose:
 * the manifest (always readable), the permission grants (always readable), the running
 * services (`getRunningServices` is capped and filtered from API 26 onward), and the
 * live process metrics (usually restricted for other apps). Merging them into one
 * "loaded" flag would mean a single failure hid three successes.
 *
 * The recorded history is the Section 22 behaviour profile. It is built only from
 * samples this app actually took, and it is absent — not zero — for a package that has
 * never been seen running.
 */
@HiltViewModel
class AppDetailViewModel @Inject constructor(
    private val appRepository: AppRepository,
    private val favoritesRepository: FavoritesRepository,
    systemRepository: SystemRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val packageName: String =
        Routes.decode(savedStateHandle.get<String>(Routes.ARG_PACKAGE).orEmpty())

    enum class Tab(val label: String) {
        OVERVIEW("Overview"),
        PERMISSIONS("Permissions"),
        COMPONENTS("Components"),
        SERVICES("Services"),
    }

    data class State(
        val packageName: String = "",
        val profile: AppProfile? = null,
        val history: ObservationHistory? = null,
        val components: Observed<AppComponents>? = null,
        val permissions: Observed<List<PermissionInfo>>? = null,
        val services: Observed<List<ServiceInfo>>? = null,
        val capabilities: SystemCapabilities? = null,
        val isFavorite: Boolean = false,
        val tab: Tab = Tab.OVERVIEW,
        val isLoading: Boolean = true,
        val notInstalled: Boolean = false,
        val error: String? = null,
    )

    private val profile = MutableStateFlow<AppProfile?>(null)
    private val components = MutableStateFlow<Observed<AppComponents>?>(null)
    private val permissions = MutableStateFlow<Observed<List<PermissionInfo>>?>(null)
    private val services = MutableStateFlow<Observed<List<ServiceInfo>>?>(null)
    private val tab = MutableStateFlow(Tab.OVERVIEW)
    private val loading = MutableStateFlow(true)
    private val notInstalled = MutableStateFlow(false)
    private val errors = MutableStateFlow<String?>(null)

    val state: StateFlow<State> = combine(
        combine(profile, components, permissions, services) { p, c, perm, s -> Reads(p, c, perm, s) },
        appRepository.observeHistory(packageName),
        systemRepository.observeCapabilities(),
        combine(tab, loading, notInstalled, errors) { t, l, missing, e -> Ui(t, l, missing, e) },
        favoritesRepository.observeIsFavorite(FavoriteType.APPLICATION, packageName),
    ) { reads, history, capabilities, ui, favorite ->
        State(
            packageName = packageName,
            profile = reads.profile,
            history = history,
            components = reads.components,
            permissions = reads.permissions,
            services = reads.services,
            capabilities = capabilities,
            isFavorite = favorite,
            tab = ui.tab,
            isLoading = ui.loading,
            notInstalled = ui.notInstalled,
            error = ui.error,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = State(packageName = packageName),
    )

    private data class Reads(
        val profile: AppProfile?,
        val components: Observed<AppComponents>?,
        val permissions: Observed<List<PermissionInfo>>?,
        val services: Observed<List<ServiceInfo>>?,
    )

    private data class Ui(
        val tab: Tab,
        val loading: Boolean,
        val notInstalled: Boolean,
        val error: String?,
    )

    init {
        loadStatic()
        viewModelScope.launch { pollProfile() }
    }

    /**
     * The manifest-derived reads.
     *
     * Done once: components and declared permissions cannot change while the app is
     * installed, and a package update would restart this process anyway.
     */
    private fun loadStatic() {
        viewModelScope.launch {
            components.value = runCatching { appRepository.getComponents(packageName) }
                .getOrElse { Observed.Failed("Could not read the manifest", it.message) }
            permissions.value = runCatching { appRepository.getPermissions(packageName) }
                .getOrElse { Observed.Failed("Could not read permissions", it.message) }
        }
    }

    /**
     * Live state, re-read on a timer.
     *
     * Slower than the dashboard's cadence: a service list and a package's process set
     * change on human timescales, and `getRunningServices` is not a cheap call.
     */
    private suspend fun pollProfile() {
        while (coroutineContext.isActive) {
            val read = runCatching { appRepository.getProfile(packageName) }
            read.onSuccess { value ->
                profile.value = value
                notInstalled.value = false
            }.onFailure { failure ->
                // getProfile errors when the package is gone or invisible, which is a
                // real answer about the package rather than a fault in this screen.
                if (profile.value == null) notInstalled.value = true
                errors.value = failure.message
            }
            services.value = runCatching { appRepository.getServices(packageName) }
                .getOrElse { Observed.Failed("Could not read running services", it.message) }
            loading.value = false
            delay(POLL_MILLIS)
        }
    }

    fun selectTab(next: Tab) {
        tab.value = next
    }

    fun toggleFavorite() {
        val label = profile.value?.app?.label ?: packageName
        viewModelScope.launch {
            favoritesRepository.toggle(FavoriteType.APPLICATION, packageName, label)
        }
    }

    fun dismissError() {
        errors.value = null
    }

    private companion object {
        const val POLL_MILLIS = 4_000L
    }
}
