package com.processlens.feature.capabilities

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.processlens.domain.model.Availability
import com.processlens.domain.model.CapabilityGroup
import com.processlens.domain.model.CapabilityStatus
import com.processlens.domain.model.DeviceInfo
import com.processlens.domain.model.SystemCapabilities
import com.processlens.domain.repository.SystemRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The capability matrix (Section 47).
 *
 * This screen is the app explaining itself. Every "Not available" elsewhere in
 * ProcessLens has a row here saying which capability was missing, why, and what — if
 * anything — would unlock it.
 *
 * The matrix is *probed*, not inferred from `Build.VERSION.SDK_INT`. Two devices on the
 * same API level can differ: one exposes a CPU temperature zone and the other does not;
 * one enforces hidepid on /proc and the other does not. Section 47 requires the runtime
 * answer, and a build-time guess would be a fabrication dressed as a capability check.
 */
@HiltViewModel
class CapabilitiesViewModel @Inject constructor(
    private val systemRepository: SystemRepository,
) : ViewModel() {

    data class State(
        val capabilities: SystemCapabilities? = null,
        val device: DeviceInfo? = null,
        val groupFilter: CapabilityGroup? = null,
        val availabilityFilter: Availability? = null,
        val isRefreshing: Boolean = false,
        val error: String? = null,
    ) {
        val isLoading: Boolean get() = capabilities == null

        /** Groups, in declaration order, after the two filters are applied. */
        val visible: List<Pair<CapabilityGroup, List<CapabilityStatus>>>
            get() {
                val matrix = capabilities ?: return emptyList()
                return CapabilityGroup.entries.mapNotNull { group ->
                    if (groupFilter != null && groupFilter != group) return@mapNotNull null
                    val rows = matrix.statuses.values
                        .filter { it.capability.group == group }
                        .filter { availabilityFilter == null || it.availability == availabilityFilter }
                        .sortedBy { it.capability.displayName }
                    if (rows.isEmpty()) null else group to rows
                }
            }
    }

    private val groupFilter = MutableStateFlow<CapabilityGroup?>(null)
    private val availabilityFilter = MutableStateFlow<Availability?>(null)
    private val device = MutableStateFlow<DeviceInfo?>(null)
    private val refreshing = MutableStateFlow(false)
    private val errors = MutableStateFlow<String?>(null)

    val state: StateFlow<State> = combine(
        systemRepository.observeCapabilities(),
        device,
        groupFilter,
        availabilityFilter,
        combine(refreshing, errors) { r, e -> r to e },
    ) { capabilities, deviceInfo, group, availability, status ->
        State(
            capabilities = capabilities,
            device = deviceInfo,
            groupFilter = group,
            availabilityFilter = availability,
            isRefreshing = status.first,
            error = status.second,
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
    }

    fun setGroup(value: CapabilityGroup?) {
        groupFilter.value = value
    }

    fun setAvailability(value: Availability?) {
        availabilityFilter.value = value
    }

    /**
     * Re-probes everything.
     *
     * Access is invalidated first so the probe genuinely re-runs rather than returning
     * the cached answer — the usual reason a user taps this is that they just changed a
     * grant in Android's settings.
     */
    fun refresh() {
        if (refreshing.value) return
        refreshing.value = true
        viewModelScope.launch {
            runCatching {
                systemRepository.invalidateAccess()
                systemRepository.refreshCapabilities()
                device.value = systemRepository.getDeviceInfo()
            }.onFailure { errors.value = it.message ?: "The probe could not be re-run" }
            refreshing.value = false
        }
    }

    fun dismissError() {
        errors.value = null
    }
}
