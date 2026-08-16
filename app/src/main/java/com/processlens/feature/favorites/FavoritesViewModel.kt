package com.processlens.feature.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.processlens.core.common.Formatters
import com.processlens.domain.model.Favorite
import com.processlens.domain.model.FavoriteType
import com.processlens.domain.repository.AppRepository
import com.processlens.domain.repository.FavoritesRepository
import com.processlens.domain.repository.InvestigationRepository
import com.processlens.domain.repository.ProcessRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Favourites (Section 31).
 *
 * A pin is a bookmark, not a subscription: pinning a process does not keep it alive, and
 * pinning an app does not stop it being uninstalled. So each pin is *resolved* against
 * the device before it is drawn, and one that no longer points anywhere says so.
 *
 * The alternative — drawing a pinned process as though it were still running — would be
 * fabrication of exactly the kind Section 42 forbids: the row would look identical
 * whether the process was there or not.
 */
@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val favorites: FavoritesRepository,
    private val apps: AppRepository,
    private val processes: ProcessRepository,
    private val investigations: InvestigationRepository,
) : ViewModel() {

    /**
     * What the lookup found.
     *
     * [PRESENT] and [UNCHECKABLE] are deliberately distinct from [NOT_RUNNING]. On API 28
     * and above an app cannot enumerate other processes, so "no process of this app was
     * found" is usually a statement about ProcessLens' access rather than about the app.
     * Collapsing those two into one label would be the fabrication Section 42 forbids.
     */
    enum class Presence(val label: String, val detail: String) {
        PRESENT("Confirmed", "Still on this device"),
        RUNNING("Running", "A process of it was found in the list ProcessLens can read"),
        NOT_RUNNING("Not running", "Absent from a complete process list"),
        GONE("No longer here", "Uninstalled, deleted, or renamed since it was pinned"),
        UNRESOLVED("Unchecked", "Not looked up yet — pull the refresh action"),
        UNCHECKABLE(
            "Run state unknown",
            "The process list is incomplete at this access level, so ProcessLens cannot " +
                "say whether this is running",
        ),
    }

    data class Entry(
        val favorite: Favorite,
        val presence: Presence,
        val secondary: String? = null,
    )

    data class State(
        val entries: List<Entry> = emptyList(),
        val resolvedAt: Long? = null,
        val isResolving: Boolean = false,
        val processListReadable: Boolean = true,
        val error: String? = null,
    ) {
        val isEmpty: Boolean get() = entries.isEmpty()

        fun byType(type: FavoriteType): List<Entry> = entries.filter { it.favorite.type == type }

        val liveCount: Int get() = entries.count { it.presence == Presence.RUNNING }
        val goneCount: Int get() = entries.count { it.presence == Presence.GONE }
    }

    private val resolution = MutableStateFlow(Resolution())
    private val resolving = MutableStateFlow(false)
    private val errors = MutableStateFlow<String?>(null)

    /**
     * The lookup results, keyed the same way favourites are.
     *
     * Kept as a separate map rather than merged into the favourites list so a pin that
     * has not been resolved yet is distinguishable from one that resolved to "gone".
     */
    private data class Resolution(
        val presence: Map<String, Presence> = emptyMap(),
        val secondary: Map<String, String> = emptyMap(),
        val at: Long? = null,
        val processListReadable: Boolean = true,
    )

    val state: StateFlow<State> = combine(
        favorites.observeAll(),
        resolution,
        resolving,
        errors,
    ) { all, resolved, isResolving, error ->
        State(
            entries = all.map { favorite ->
                val key = cacheKey(favorite)
                Entry(
                    favorite = favorite,
                    presence = resolved.presence[key] ?: Presence.UNRESOLVED,
                    secondary = resolved.secondary[key],
                )
            },
            resolvedAt = resolved.at,
            isResolving = isResolving,
            processListReadable = resolved.processListReadable,
            error = error,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = State(),
    )

    init {
        resolve()
    }

    private fun cacheKey(favorite: Favorite): String = favorite.type.name + ":" + favorite.key

    /**
     * Looks every pin up once.
     *
     * One read of the process list, one of the investigation list, and one package query
     * per pinned app — rather than a poll. Favourites is a navigation screen; keeping a
     * two-second process poll running behind it would be ProcessLens becoming the
     * problem it investigates (Section 43).
     */
    fun resolve() {
        if (resolving.value) return
        resolving.value = true
        viewModelScope.launch {
            val pins = runCatching { favorites.observeAll().first() }.getOrDefault(emptyList())
            val presence = mutableMapOf<String, Presence>()
            val secondary = mutableMapOf<String, String>()

            val processResult = runCatching { processes.readProcesses() }.getOrNull()
            val complete = processResult?.isCompleteList == true
            val running = processResult?.processes.orEmpty()

            val investigationIds = runCatching {
                investigations.observeAll().first().map { it.id }.toSet()
            }.getOrDefault(emptySet())

            for (pin in pins) {
                val key = cacheKey(pin)
                when (pin.type) {
                    FavoriteType.APPLICATION -> {
                        val app = runCatching { apps.getApp(pin.key) }.getOrNull()
                        if (app == null) {
                            presence[key] = Presence.GONE
                            secondary[key] = pin.key
                        } else {
                            val count = running.count { it.packageName == pin.key }
                            presence[key] = when {
                                count > 0 -> Presence.RUNNING
                                complete -> Presence.NOT_RUNNING
                                else -> Presence.PRESENT
                            }
                            secondary[key] = when {
                                count > 0 -> Formatters.count(count, "process", "processes")
                                complete -> app.packageName
                                else -> "Installed; run state not readable here"
                            }
                        }
                    }

                    FavoriteType.PROCESS -> {
                        val match = running.firstOrNull { it.id == pin.key }
                        if (match != null) {
                            presence[key] = Presence.RUNNING
                            secondary[key] = match.processName
                        } else {
                            // Absent from a *complete* list means gone. Absent from a
                            // partial list means only that this access level did not show
                            // it, which is a different statement and gets a different word.
                            presence[key] = if (complete) {
                                Presence.NOT_RUNNING
                            } else {
                                Presence.UNCHECKABLE
                            }
                            secondary[key] = pin.key
                        }
                    }

                    FavoriteType.INVESTIGATION -> {
                        val id = pin.key.toLongOrNull()
                        presence[key] = if (id != null && investigationIds.contains(id)) {
                            Presence.PRESENT
                        } else {
                            Presence.GONE
                        }
                    }
                }
            }

            resolution.value = Resolution(
                presence = presence,
                secondary = secondary,
                at = System.currentTimeMillis(),
                processListReadable = complete,
            )
            resolving.value = false
        }
    }

    fun remove(favorite: Favorite) {
        viewModelScope.launch {
            runCatching { favorites.remove(favorite.type, favorite.key) }
                .onFailure { errors.value = it.message ?: "The pin could not be removed" }
        }
    }

    /** Removes every pin whose target no longer exists. */
    fun removeGone() {
        val gone = state.value.entries.filter { it.presence == Presence.GONE }
        if (gone.isEmpty()) return
        viewModelScope.launch {
            gone.forEach { entry ->
                runCatching { favorites.remove(entry.favorite.type, entry.favorite.key) }
            }
            resolve()
        }
    }

    fun dismissError() {
        errors.value = null
    }
}
