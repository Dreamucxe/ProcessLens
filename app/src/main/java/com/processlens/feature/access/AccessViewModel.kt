package com.processlens.feature.access

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.processlens.core.common.AccessLevel
import com.processlens.core.permissions.PermissionChecker
import com.processlens.core.system.RootShell
import com.processlens.core.system.ShizukuShell
import com.processlens.domain.model.RootState
import com.processlens.domain.model.ShizukuState
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
 * Access level (Sections 27, 28, 41).
 *
 * The three ways ProcessLens can be granted more visibility, and honestly what each one
 * buys. Two rules govern everything here:
 *
 * 1. Shizuku is *not* root. It runs commands as the shell user (UID 2000), which can
 *    read the process table and dumpsys but cannot read another app's private storage
 *    or write to system partitions. Section 27 is explicit that this must not be blurred.
 * 2. No command runs without the user asking. The root probe is `id`, and the diagnostic
 *    commands are all reads — this app has no destructive path to offer, by construction.
 */
@HiltViewModel
class AccessViewModel @Inject constructor(
    private val shizuku: ShizukuShell,
    private val root: RootShell,
    private val systemRepository: SystemRepository,
    private val permissions: PermissionChecker,
) : ViewModel() {

    data class State(
        val capabilities: SystemCapabilities? = null,
        val shizukuState: ShizukuState = ShizukuState.NOT_INSTALLED,
        val rootState: RootState = RootState.UNAVAILABLE,
        val hasUsageAccess: Boolean = false,
        val hasPhoneState: Boolean = false,
        val hasNotifications: Boolean = false,
        val isBatteryOptimisationIgnored: Boolean = false,
        val isRequestingShizuku: Boolean = false,
        val isProbingRoot: Boolean = false,
        val lastProbeMessage: String? = null,
        val error: String? = null,
    ) {
        val effectiveLevel: AccessLevel
            get() = capabilities?.accessLevel ?: AccessLevel.NORMAL
    }

    private val shizukuState = MutableStateFlow(ShizukuState.NOT_INSTALLED)
    private val rootState = MutableStateFlow(RootState.UNAVAILABLE)
    private val grants = MutableStateFlow(Grants())
    private val busy = MutableStateFlow(Busy())
    private val errors = MutableStateFlow<String?>(null)

    private data class Grants(
        val usage: Boolean = false,
        val phone: Boolean = false,
        val notifications: Boolean = false,
        val batteryOptimisation: Boolean = false,
    )

    private data class Busy(
        val shizuku: Boolean = false,
        val root: Boolean = false,
        val message: String? = null,
    )

    val state: StateFlow<State> = combine(
        systemRepository.observeCapabilities(),
        shizukuState,
        rootState,
        grants,
        combine(busy, errors) { b, e -> b to e },
    ) { capabilities, shizukuValue, rootValue, grantValues, status ->
        State(
            capabilities = capabilities,
            shizukuState = shizukuValue,
            rootState = rootValue,
            hasUsageAccess = grantValues.usage,
            hasPhoneState = grantValues.phone,
            hasNotifications = grantValues.notifications,
            isBatteryOptimisationIgnored = grantValues.batteryOptimisation,
            isRequestingShizuku = status.first.shizuku,
            isProbingRoot = status.first.root,
            lastProbeMessage = status.first.message,
            error = status.second,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = State(),
    )

    init {
        refresh()
    }

    /**
     * Re-reads every access signal.
     *
     * Called on entry and after returning from a settings screen, because the user may
     * have changed a grant while this app was in the background and a stale "denied" is
     * as misleading as a stale "granted".
     */
    fun refresh() {
        shizukuState.value = runCatching { shizuku.state() }
            .getOrDefault(ShizukuState.NOT_INSTALLED)
        rootState.value = runCatching { root.state() }.getOrDefault(RootState.UNAVAILABLE)
        grants.value = Grants(
            usage = runCatching { permissions.hasUsageAccess() }.getOrDefault(false),
            phone = runCatching { permissions.hasPhoneStatePermission() }.getOrDefault(false),
            notifications = runCatching { permissions.hasNotificationPermission() }
                .getOrDefault(false),
            batteryOptimisation = runCatching { permissions.isIgnoringBatteryOptimisations() }
                .getOrDefault(false),
        )
        viewModelScope.launch {
            runCatching {
                systemRepository.invalidateAccess()
                systemRepository.refreshCapabilities()
            }.onFailure { errors.value = it.message }
        }
    }

    /**
     * Asks Shizuku for permission.
     *
     * The dialog is Shizuku's, not ours, and the returned state is the answer — there is
     * no path here that reports success without the binder having confirmed it.
     */
    fun requestShizuku() {
        if (busy.value.shizuku) return
        busy.value = busy.value.copy(shizuku = true, message = null)
        viewModelScope.launch {
            val result = runCatching { shizuku.requestPermission() }
            result.onSuccess { next ->
                shizukuState.value = next
                busy.value = Busy(
                    message = when (next) {
                        ShizukuState.RUNNING_PERMISSION_GRANTED ->
                            "Shizuku granted. ProcessLens can now read the full process " +
                                "table and the platform's own service and power dumps."

                        ShizukuState.RUNNING_PERMISSION_DENIED ->
                            "Shizuku denied the request. Nothing changed."

                        ShizukuState.RUNNING_PERMISSION_UNKNOWN ->
                            "No answer came back from Shizuku. Open the Shizuku app and " +
                                "check that it is still running."

                        ShizukuState.INSTALLED_NOT_RUNNING ->
                            "Shizuku is installed but its service is not running. Start it " +
                                "from the Shizuku app first."

                        ShizukuState.NOT_INSTALLED ->
                            "Shizuku is not installed on this device."

                        ShizukuState.VERSION_UNSUPPORTED ->
                            "This Shizuku version predates v11 and uses a permission model " +
                                "ProcessLens cannot use."
                    },
                )
                refreshCapabilitiesOnly()
            }.onFailure {
                busy.value = Busy(message = null)
                errors.value = it.message ?: "The Shizuku request failed"
            }
        }
    }

    /**
     * Probes for root by running `id` and looking for uid=0.
     *
     * This is the whole probe. It writes nothing, changes nothing, and if the su binary
     * prompts, the user's own root manager decides — this app never assumes a grant it
     * has not seen.
     */
    fun probeRoot() {
        if (busy.value.root) return
        busy.value = busy.value.copy(root = true, message = null)
        viewModelScope.launch {
            val result = runCatching { root.probe() }
            result.onSuccess { next ->
                rootState.value = next
                busy.value = Busy(
                    message = when (next) {
                        RootState.GRANTED ->
                            "Root granted. ProcessLens will use it only for the read-only " +
                                "diagnostics listed below."

                        RootState.DENIED ->
                            "The root request was denied or timed out. Nothing changed."

                        RootState.BINARY_PRESENT ->
                            "A su binary is present but did not return a root shell."

                        RootState.UNAVAILABLE ->
                            "No su binary was found. This device is not rooted, or root " +
                                "is hidden from this app."
                    },
                )
                refreshCapabilitiesOnly()
            }.onFailure {
                busy.value = Busy(message = null)
                errors.value = it.message ?: "The root probe failed"
            }
        }
    }

    /** Drops the cached matrix so the next read reflects the new access level. */
    private suspend fun refreshCapabilitiesOnly() {
        runCatching {
            systemRepository.invalidateAccess()
            systemRepository.refreshCapabilities()
        }.onFailure { errors.value = it.message }
    }

    fun dismissMessage() {
        busy.value = busy.value.copy(message = null)
    }

    fun dismissError() {
        errors.value = null
    }
}
