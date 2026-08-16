package com.processlens.feature.permissions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.processlens.core.common.FuzzyMatch
import com.processlens.core.common.Observed
import com.processlens.domain.model.AppInfo
import com.processlens.domain.model.PermissionGrant
import com.processlens.domain.model.PermissionGroup
import com.processlens.domain.model.PermissionInfo
import com.processlens.domain.repository.AppRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import javax.inject.Inject

/**
 * The device-wide permission inspector (Sections 21, 30, 32).
 *
 * [com.processlens.feature.applications.AppDetailScreen] answers "what does this app
 * hold?". This screen answers the reverse — "which apps hold the microphone?" — which
 * is the question a forensic tool is actually asked, and the one the platform's own
 * settings answer only one group at a time.
 *
 * ### Why it is a scan and not a query
 *
 * Android exposes no reverse index. `PackageManager` will tell you the permissions of
 * a package you name; there is no supported call that lists the holders of a
 * permission. The only honest implementation is to read every visible package and
 * invert the result locally, which is what [scan] does.
 *
 * That has a real cost, so Section 43 shapes it:
 *
 *  - **User-installed packages only, by default.** Roughly forty packages instead of
 *    three hundred. System packages hold most permissions by design and would drown
 *    the answer the user came for; the toggle is there when they want them.
 *  - **Progress is reported and the job is cancellable.** A sweep of hundreds of
 *    Binder round trips is not instant and pretending otherwise would leave the screen
 *    looking hung.
 *  - **The scan yields periodically** so it cannot monopolise its dispatcher, and it
 *    is a single pass — no per-group re-reads.
 *
 * ### What it will not do
 *
 * It does not change grants. No application can revoke another application's
 * permission on stock Android, at any access level short of a platform-signed
 * installer, so Section 21's "do not claim to change permissions unless Android
 * actually allows the requested operation" leaves exactly one honest affordance: hand
 * off to the platform's own per-app settings page, which the app detail screen does.
 *
 * Packages whose permissions cannot be read are *counted*, not skipped silently
 * (Section 42) — from API 30 package visibility can hide a package that is genuinely
 * installed, and a total that quietly omitted it would understate the answer.
 */
@HiltViewModel
class PermissionsViewModel @Inject constructor(
    private val appRepository: AppRepository,
) : ViewModel() {

    /** One application that holds at least one permission in the enclosing group. */
    data class Holder(
        val packageName: String,
        val label: String,
        val isSystemApp: Boolean,
        /** Only the held permissions, and only those in this group. */
        val held: List<PermissionInfo>,
    ) {
        /** Held *and* revocable by the user in Android's settings. */
        val revocable: List<PermissionInfo> get() = held.filter { it.isDangerous }

        val hasRestricted: Boolean
            get() = held.any { it.grant == PermissionGrant.RESTRICTED }

        /** "Camera, Record audio" — the specific grants, never just a count. */
        val heldSummary: String
            get() = held.joinToString(", ") { it.label?.takeIf { l -> l.isNotBlank() } ?: it.shortName }
    }

    data class GroupSummary(
        val group: PermissionGroup,
        val holders: List<Holder>,
    ) {
        val holderCount: Int get() = holders.size

        /** Holders with something the user could actually turn off. */
        val revocableHolderCount: Int get() = holders.count { it.revocable.isNotEmpty() }

        val restrictedHolderCount: Int get() = holders.count { it.hasRestricted }
    }

    data class Scan(
        val groups: List<GroupSummary>,
        val packagesRead: Int,
        /** Installed but unreadable — package visibility, or a PackageManager failure. */
        val packagesUnreadable: Int,
        val includedSystemApps: Boolean,
    ) {
        val totalPackages: Int get() = packagesRead + packagesUnreadable
    }

    data class Progress(val done: Int, val total: Int) {
        val fraction: Float get() = if (total <= 0) 0f else done.toFloat() / total
    }

    data class State(
        val scan: Scan? = null,
        val progress: Progress? = null,
        val includeSystem: Boolean = false,
        val groupFilter: PermissionGroup? = null,
        val query: String = "",
        val error: String? = null,
    ) {
        val isScanning: Boolean get() = progress != null

        /** True only before the first scan has produced anything. */
        val isFirstLoad: Boolean get() = scan == null

        /**
         * Groups after the filter and the query, dropping any that end up empty.
         *
         * A group with no holders is omitted rather than shown as "0 apps": on this
         * device, right now, nothing holds it, and an empty section invites the reader
         * to wonder whether the scan failed.
         */
        val visible: List<GroupSummary>
            get() {
                val groups = scan?.groups ?: return emptyList()
                return groups.mapNotNull { summary ->
                    if (groupFilter != null && summary.group != groupFilter) return@mapNotNull null
                    if (query.isBlank()) return@mapNotNull summary
                    val matched = summary.holders
                        .map { it to FuzzyMatch.bestScore(query, it.label, it.packageName) }
                        .filter { it.second > 0 }
                        .sortedWith(
                            compareByDescending<Pair<Holder, Int>> { it.second }
                                .thenBy { it.first.label.lowercase() },
                        )
                        .map { it.first }
                    if (matched.isEmpty()) null else summary.copy(holders = matched)
                }
            }

        /** Groups that have holders, for the filter chips. */
        val availableGroups: List<PermissionGroup>
            get() = scan?.groups?.map { it.group }.orEmpty()
    }

    private val scan = MutableStateFlow<Scan?>(null)
    private val progress = MutableStateFlow<Progress?>(null)
    private val includeSystem = MutableStateFlow(false)
    private val groupFilter = MutableStateFlow<PermissionGroup?>(null)
    private val query = MutableStateFlow("")
    private val errors = MutableStateFlow<String?>(null)

    private var scanJob: Job? = null

    val state: StateFlow<State> = combine(
        scan,
        progress,
        includeSystem,
        combine(groupFilter, query) { group, q -> group to q },
        errors,
    ) { result, prog, system, filters, error ->
        State(
            scan = result,
            progress = prog,
            includeSystem = system,
            groupFilter = filters.first,
            query = filters.second,
            error = error,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = State(),
    )

    init {
        rescan()
    }

    fun setQuery(value: String) {
        query.value = value
    }

    fun setGroup(value: PermissionGroup?) {
        groupFilter.value = value
    }

    /**
     * Toggles system packages and re-runs the sweep.
     *
     * A local toggle, not the shared `showSystemProcesses` preference: on this screen
     * the setting decides how many hundred Binder calls happen, which is a different
     * kind of decision from "should the process list include system rows", and a user
     * who wants system processes listed elsewhere has not asked for a five-times-longer
     * scan here.
     */
    fun toggleSystemApps() {
        includeSystem.value = !includeSystem.value
        rescan()
    }

    fun rescan() {
        val system = includeSystem.value
        scanJob?.cancel()
        errors.value = null
        scanJob = viewModelScope.launch {
            runCatching { scan(system) }
                .onFailure { throwable ->
                    // A cancelled scan is not a failure — the user changed the toggle.
                    if (throwable is kotlinx.coroutines.CancellationException) throw throwable
                    errors.value = throwable.message ?: "The permission scan could not be completed"
                }
            progress.value = null
        }
    }

    fun dismissError() {
        errors.value = null
    }

    /**
     * One pass over the visible packages, inverted into groups.
     *
     * `AUTO_GRANTED` is included alongside `GRANTED` and `RESTRICTED` because a
     * normal-protection permission the app holds is still a capability it has — the
     * distinction the UI draws is whether the user can *revoke* it, not whether it
     * counts. `DENIED`, `UNKNOWN` and signature permissions the app does not hold are
     * excluded: this screen answers "who holds this", and a manifest request that was
     * never granted is not a holding.
     */
    private suspend fun scan(includeSystemApps: Boolean) {
        val apps: List<AppInfo> = appRepository.observeApps(includeSystemApps).first()
        progress.value = Progress(done = 0, total = apps.size)

        val byGroup = linkedMapOf<PermissionGroup, MutableList<Holder>>()
        var read = 0
        var unreadable = 0

        apps.forEachIndexed { index, app ->
            when (val observed = appRepository.getPermissions(app.packageName)) {
                is Observed.Value -> {
                    read++
                    observed.value
                        .filter { it.grant.isHeld }
                        .groupBy { it.group }
                        .forEach { (group, permissions) ->
                            byGroup.getOrPut(group) { mutableListOf() }.add(
                                Holder(
                                    packageName = app.packageName,
                                    label = app.label,
                                    isSystemApp = app.isSystemApp,
                                    held = permissions.sortedWith(
                                        compareByDescending<PermissionInfo> { it.isDangerous }
                                            .thenBy { it.shortName },
                                    ),
                                ),
                            )
                        }
                }
                else -> unreadable++
            }

            progress.value = Progress(done = index + 1, total = apps.size)
            // Cooperative: a sweep of several hundred packages must not hold the
            // dispatcher long enough to be felt elsewhere in the app (Section 43).
            if (index % 8 == 7) yield()
        }

        scan.value = Scan(
            // Declaration order, which is Section 21's order — Location, Camera,
            // Microphone, Storage, Notifications, Sensors, Phone … — rather than
            // whichever group the first scanned package happened to hold.
            groups = PermissionGroup.entries.mapNotNull { group ->
                val holders = byGroup[group] ?: return@mapNotNull null
                if (holders.isEmpty()) {
                    null
                } else {
                    GroupSummary(
                        group = group,
                        holders = holders.sortedWith(
                            // User apps first: on a scan that includes system packages,
                            // the app the user installed is the one they are looking for.
                            compareBy<Holder> { it.isSystemApp }
                                .thenByDescending { it.revocable.size }
                                .thenBy { it.label.lowercase() },
                        ),
                    )
                }
            },
            packagesRead = read,
            packagesUnreadable = unreadable,
            includedSystemApps = includeSystemApps,
        )
    }
}

/**
 * Held means the app has it now, whatever the platform's route to granting it was.
 *
 * `RESTRICTED` counts: the permission is in the app's grant set and the UI needs to say
 * so, with the caveat that the appop behind it is revoked. Treating it as not-held
 * would hide a grant that a future OS update or appop change could make live.
 */
private val PermissionGrant.isHeld: Boolean
    get() = this == PermissionGrant.GRANTED ||
        this == PermissionGrant.RESTRICTED ||
        this == PermissionGrant.AUTO_GRANTED
