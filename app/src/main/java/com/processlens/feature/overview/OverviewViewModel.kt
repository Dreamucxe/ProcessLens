package com.processlens.feature.overview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.processlens.core.common.AccessLevel
import com.processlens.core.common.Observed
import com.processlens.core.common.valueOrNull
import com.processlens.domain.model.Availability
import com.processlens.domain.model.Favorite
import com.processlens.domain.model.InvestigationEvent
import com.processlens.domain.model.SystemCapabilities
import com.processlens.domain.model.UserSettings
import com.processlens.domain.repository.FavoritesRepository
import com.processlens.domain.repository.InvestigationRepository
import com.processlens.domain.repository.SettingsRepository
import com.processlens.domain.repository.SystemRepository
import com.processlens.domain.repository.SystemState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Dashboard state (Sections 5, 6).
 *
 * Holds a bounded history of the samples it has actually seen, so the dashboard can
 * draw a trend without a second polling loop. The history is **only** what this
 * screen observed while it was open — there is no back-filling and no interpolation.
 * A sample whose CPU reading was restricted is stored as `null` and drawn as a gap
 * (Section 42).
 *
 * The other thing this class now guarantees is that its state **resolves**. Issue #1
 * was a starved `combine()` upstream, fixed in the repository, but the reason it was
 * invisible for so long is that nothing here ever questioned a silent source: a
 * five-arity `combine` publishes nothing until every input has spoken, a thrown
 * collector took the whole flow down without a trace, and the screen's only reaction
 * to "no state yet" was a spinner with no deadline. Three things therefore hold here
 * now, and the dashboard depends on all three:
 *
 *  - a throw lands in [State.loadFailure] as an [Observed.Failed] rather than ending
 *    the screen's story;
 *  - collection is restartable, so the retry the user is offered is a real one;
 *  - the capability placeholder is distinguishable from a real "no elevated access"
 *    answer, so the dashboard does not accuse the device of restrictions it has not
 *    yet been asked about.
 */
@HiltViewModel
class OverviewViewModel @Inject constructor(
    private val systemRepository: SystemRepository,
    private val settingsRepository: SettingsRepository,
    private val investigationRepository: InvestigationRepository,
    private val favoritesRepository: FavoritesRepository,
) : ViewModel() {

    data class State(
        val system: SystemState? = null,
        val capabilities: SystemCapabilities? = null,
        val settings: UserSettings = UserSettings(),
        val recentEvents: List<InvestigationEvent> = emptyList(),
        val favorites: List<Favorite> = emptyList(),
        val isRecording: Boolean = false,
        val activeInvestigationId: Long? = null,
        /** Oldest first. Null entries are samples where CPU was not readable. */
        val cpuHistory: List<Float?> = emptyList(),
        val memoryHistory: List<Float?> = emptyList(),
        val isRefreshing: Boolean = false,
        val error: String? = null,
        /**
         * Set when the state pipeline itself threw.
         *
         * [Observed.Failed] rather than a bare `String` on purpose: the screen then
         * renders it through the same Section 42 components as any other unreadable
         * value, reason and technical-details expander included, instead of growing a
         * second vocabulary for "this went wrong" that only the dashboard speaks.
         */
        val loadFailure: Observed.Failed? = null,
        /** True while [revalidateAccess] is in flight, so a re-probe looks like one. */
        val isRevalidating: Boolean = false,
        /** The user has closed the elevated-access notice for this screen's lifetime. */
        val isAccessNoticeDismissed: Boolean = false,
    ) {
        val isFirstLoad: Boolean get() = system == null
    }

    private val history = MutableStateFlow(History())

    private data class History(
        val cpu: List<Float?> = emptyList(),
        val memory: List<Float?> = emptyList(),
    ) {
        /**
         * 90 points at the default two-second rate is three minutes of context, which
         * is what a dashboard trend needs. Bounded on purpose: an unbounded list on a
         * screen left open overnight is a slow leak, and Section 43 makes the tool's
         * own footprint a requirement rather than an afterthought.
         */
        fun plus(cpuPercent: Float?, memoryFraction: Float?): History = History(
            cpu = (cpu + cpuPercent).takeLast(CAPACITY),
            memory = (memory + memoryFraction).takeLast(CAPACITY),
        )

        companion object {
            const val CAPACITY = 90
        }
    }

    private val refreshing = MutableStateFlow(false)
    private val errors = MutableStateFlow<String?>(null)
    private val notices = MutableStateFlow(Notices())

    /** The bits of state the user's own actions own, kept in one flow so the chain
     * of `combine` operators below does not grow an operator per boolean. */
    private data class Notices(
        val isRevalidating: Boolean = false,
        val accessNoticeDismissed: Boolean = false,
    )

    /**
     * Collection is restartable (issue #1).
     *
     * The `catch` in [observeDashboardState] resolves a thrown pipeline into something
     * the screen can render — but a caught flow is a *finished* flow. `stateIn` keeps
     * the last value and nothing upstream ever runs again, so a retry control wired to
     * a finished pipeline is the same dead end wearing a different hat. Bumping this
     * counter cancels whatever is left of the previous collection and starts the
     * sources from scratch, which is what makes the retry on the unresolved state and
     * the re-probe in the access notice do something a user can observe.
     */
    private val attempts = MutableStateFlow(0)

    /**
     * The last state the pipeline managed to build.
     *
     * Read only by the `catch`, so a failure that arrives after content was already on
     * screen degrades to "the figures you can see, plus the error" instead of throwing
     * the dashboard back to an empty frame.
     */
    private val lastBuilt = MutableStateFlow(State())

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<State> = attempts
        .flatMapLatest { observeDashboardState() }
        .combine(refreshing) { state, isRefreshing ->
            state.copy(isRefreshing = isRefreshing)
        }.combine(errors) { state, error ->
            state.copy(error = error)
        }.combine(notices) { state, notice ->
            state.copy(
                isRevalidating = notice.isRevalidating,
                isAccessNoticeDismissed = notice.accessNoticeDismissed,
            )
        }.stateIn(
            scope = viewModelScope,
            // The polling flow is cold: this stops it when the screen leaves and lets it
            // survive a rotation without a restart.
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = State(),
        )

    /**
     * One collection of the five sources, ending in a state the screen can always draw.
     *
     * The three user-driven flows (refresh, errors, notices) are deliberately *outside*
     * this function: they must not be cancelled and rebuilt when [attempts] restarts
     * the sources, or a retry would silently clear the very error message that prompted
     * it.
     *
     * The `catch` is the point of the whole thing. Before it there was no `catch` and no
     * `try` around any flow collection anywhere in this app, which means a throwing
     * upstream — an OEM `/proc` read that raises something `ProcFsReader` does not
     * expect, a database query that fails mid-collection — took this flow down in
     * silence and left the screen on `initialValue`, i.e. the permanent "Reading system
     * state" of issue #1. Swallowing it would have been worse than the crash.
     */
    private fun observeDashboardState(): Flow<State> = combine(
        systemRepository.observeSystemState().onEachRecordHistory(),
        systemRepository.observeCapabilities(),
        settingsRepository.observe(),
        investigationRepository.observeActive(),
        investigationRepository.observeRecentEvents(RECENT_EVENT_LIMIT),
    ) { system, capabilities, settings, active, events ->
        Quint(system, capabilities, settings, active, events)
    }.combine(favoritesRepository.observeAll()) { quint, favorites ->
        quint to favorites
    }.combine(history) { (quint, favorites), hist ->
        State(
            system = quint.system,
            capabilities = quint.capabilities,
            settings = quint.settings,
            recentEvents = quint.events,
            favorites = favorites.take(FAVORITE_LIMIT),
            isRecording = quint.active?.isRunning == true,
            activeInvestigationId = quint.active?.id,
            cpuHistory = hist.cpu,
            memoryHistory = hist.memory,
        )
    }.onEach { built ->
        lastBuilt.value = built
    }.catch { cause ->
        // Both channels, on purpose. `errors` is the existing banner and is what the
        // user reads; `loadFailure` is the structured copy the unresolved state needs
        // and the only one that survives a tap on "Dismiss".
        errors.value = cause.message?.takeIf { it.isNotBlank() }
            ?: "The dashboard stopped receiving system state"
        emit(loadFailureState(lastBuilt.value, cause))
    }

    private data class Quint(
        val system: SystemState,
        val capabilities: SystemCapabilities,
        val settings: UserSettings,
        val active: com.processlens.domain.model.Investigation?,
        val events: List<InvestigationEvent>,
    )

    /**
     * Appends each observed sample to the history as it passes through.
     *
     * Done as a side-effect on the flow rather than in a separate collector so the
     * history and the displayed reading can never come from different ticks.
     */
    private fun Flow<SystemState>.onEachRecordHistory(): Flow<SystemState> = onEach { state ->
        history.value = history.value.plus(
            cpuPercent = state.cpu.overallPercent.valueOrNull,
            memoryFraction = state.memory.usedFraction * 100f,
        )
    }

    /** Manual refresh, for the pull gesture and for `RefreshRate.MANUAL`. */
    fun refresh() {
        viewModelScope.launch {
            refreshing.value = true
            errors.value = null
            try {
                val fresh = systemRepository.readSystemState()
                history.value = history.value.plus(
                    cpuPercent = fresh.cpu.overallPercent.valueOrNull,
                    memoryFraction = fresh.memory.usedFraction * 100f,
                )
            } catch (t: Throwable) {
                errors.value = t.message ?: "Could not read system state"
            } finally {
                refreshing.value = false
            }
        }
    }

    /**
     * Re-probes access, and re-collects everything that depends on the answer.
     *
     * This method existed before issue #1 was filed and had no callers — which is a
     * large part of why the hang was permanent, because it is the one action that
     * writes the capability flow the dashboard was waiting on. It now backs two
     * controls: the retry on the unresolved state, and the re-probe in the access
     * notice.
     *
     * Three things it does that the dead version did not. It reports progress, because
     * a probe that takes a second and says nothing is indistinguishable from a dead
     * button. It surfaces its own failure through the existing banner rather than
     * dropping it in a `runCatching`. And it bumps [attempts] on the way out, so a tap
     * after the pipeline has already terminated restarts the sources instead of
     * politely doing nothing.
     */
    fun revalidateAccess() {
        if (notices.value.isRevalidating) return
        notices.value = notices.value.copy(isRevalidating = true)
        viewModelScope.launch {
            try {
                systemRepository.invalidateAccess()
                systemRepository.refreshCapabilities()
                errors.value = null
            } catch (t: Throwable) {
                errors.value = t.message ?: "Could not re-probe this device's access level"
            } finally {
                notices.value = notices.value.copy(isRevalidating = false)
                attempts.value += 1
            }
        }
    }

    fun dismissError() {
        errors.value = null
    }

    /**
     * Closes the elevated-access notice.
     *
     * Held here rather than in the composition so it survives a rotation: a banner that
     * reappears every time the device turns is not dismissible in any sense the user
     * recognises. It is deliberately *not* persisted — the honest scope of the dismissal
     * is "I have read this", not "never tell me about this device again", and the next
     * launch re-probes anyway.
     */
    fun dismissAccessNotice() {
        notices.value = notices.value.copy(accessNoticeDismissed = true)
    }

    private companion object {
        const val RECENT_EVENT_LIMIT = 6
        const val FAVORITE_LIMIT = 6
    }
}

/**
 * How long the dashboard waits for its first sample before it stops pretending to be
 * busy and starts explaining itself.
 *
 * Not a timeout on anything: nothing is cancelled when it expires, and a sample that
 * arrives late still renders. It only bounds how long a *spinner* is an honest answer.
 * Four seconds is comfortably longer than a healthy cold read (the repository's poll
 * loop emits before its first `delay`, so the first sample lands in well under a
 * second) and short enough that a user has not yet decided the app is broken.
 */
const val FIRST_SAMPLE_DEADLINE_MILLIS = 4_000L

/** What the dashboard draws where its content goes. */
enum class OverviewLoadPhase {
    /** There is a sample. Draw the dashboard. */
    CONTENT,

    /** No sample yet, and the wait is still young enough for a spinner to be true. */
    WAITING,

    /** No sample, and no longer any reason to expect one silently. Explain and offer out. */
    UNRESOLVED,
}

/**
 * The loading decision, as a function rather than a shape in a composable.
 *
 * Deliberately not an [Observed]: that type models a *reading* that is absent and the
 * reason it is absent, and "the first sample has not arrived yet" is neither a reading
 * nor a restriction. Pretending otherwise would mean inventing an `Observed` case to
 * mean "pending", which would weaken the one type in this app that is never allowed to
 * be vague.
 *
 * [deadlineElapsed] comes from the screen because only the composition knows how long
 * it has been on screen. Everything else is state, which is why this is testable
 * without a Compose harness.
 */
fun OverviewViewModel.State.loadPhase(deadlineElapsed: Boolean): OverviewLoadPhase = when {
    system != null -> OverviewLoadPhase.CONTENT
    loadFailure != null || deadlineElapsed -> OverviewLoadPhase.UNRESOLVED
    else -> OverviewLoadPhase.WAITING
}

/**
 * Folds a thrown pipeline into a renderable state.
 *
 * Pure, and takes the previous state so the dashboard keeps whatever it had: a failure
 * three minutes into a session should cost the user the trend they were reading, not
 * the whole screen. [Observed.Failed] rather than [Observed.Restricted] because this is
 * a fault, not a platform limit — Section 42 draws that line, and labelling a crashed
 * collector "restricted by Android" would blame the device for the app's own bug.
 */
fun loadFailureState(previous: OverviewViewModel.State, cause: Throwable): OverviewViewModel.State =
    previous.copy(
        isRefreshing = false,
        loadFailure = Observed.Failed(
            detail = cause.message?.takeIf { it.isNotBlank() }
                ?: cause::class.java.simpleName,
            cause = cause::class.java.name,
        ),
    )

/** Heading for the unresolved state. Separate from the prose so both are testable. */
fun unresolvedTitle(failure: Observed.Failed?): String =
    if (failure != null) "System state could not be read" else "No reading has arrived yet"

/**
 * The prose under [unresolvedTitle].
 *
 * Both branches say the same load-bearing thing in different words: nothing is being
 * hidden. A reader who has watched a spinner has every reason to assume the app knows
 * something it is not saying, and the only cure is to state that there is no sample at
 * all (Section 42).
 */
fun unresolvedExplanation(failure: Observed.Failed?): String = if (failure != null) {
    "The flow this dashboard reads from stopped with an error, so there is no sample to " +
        "show. Nothing is being withheld: the reading was never taken."
} else {
    "Nothing arrived within ${FIRST_SAMPLE_DEADLINE_MILLIS / 1_000} seconds. The figures " +
        "are not being withheld — no sample has reached this screen at all. Re-probing " +
        "this device re-reads its access level and starts the sampling again."
}

/**
 * True once the capability matrix holds a real answer.
 *
 * This distinction is load-bearing and easy to lose. `SystemCapabilities.unknown()` —
 * the placeholder the repository seeds its flow with so that twelve `combine`s resolve
 * on the first frame — reports `rootState = UNAVAILABLE` and `accessLevel = NORMAL`,
 * which is indistinguishable from a device that was probed and genuinely has neither.
 * Reading it as a confirmed "no root" would flash "running without elevated access" on
 * every single launch, including on rooted devices, a second before the real answer
 * lands.
 *
 * What the placeholder *does* leave behind is an empty `statuses` map: every `get()`
 * falls through to "Not evaluated on this device", and no detector ever produces an
 * empty matrix. Emptiness is therefore the signal, and it is the only one available
 * without adding a state to a model this screen does not own.
 */
fun isCapabilityMatrixEvaluated(capabilities: SystemCapabilities?): Boolean =
    capabilities != null && capabilities.statuses.isNotEmpty()

/**
 * Whether to offer the non-blocking "no elevated access" notice.
 *
 * Gated on [isCapabilityMatrixEvaluated] so it cannot fire against the placeholder, and
 * on `NORMAL` rather than on `rootState` alone: a device running through Shizuku is not
 * "without elevated access", and telling its user to go and find root would be wrong as
 * well as annoying.
 */
fun showElevatedAccessNotice(
    capabilities: SystemCapabilities?,
    isDismissed: Boolean,
): Boolean = !isDismissed &&
    isCapabilityMatrixEvaluated(capabilities) &&
    capabilities?.accessLevel == AccessLevel.NORMAL

/**
 * The elevated levels that would expose at least one of [observations].
 *
 * Reads each restriction's own [Observed.Restricted.unlockedBy], which is exactly what
 * that field is for, so the hint is the data layer's claim rather than the screen's
 * guess. A figure this kernel simply does not publish carries `unlockedBy = null` by
 * construction and therefore contributes nothing: Section 42 forbids telling a user
 * that root would produce a sensor their hardware does not have.
 *
 * `NORMAL` is filtered out because "normal access would expose this" is not a thing to
 * suggest to someone already running at normal access.
 */
fun unlockedByLevels(vararg observations: Observed<*>): List<AccessLevel> = observations
    .filterIsInstance<Observed.Restricted>()
    .mapNotNull { it.unlockedBy }
    .filter { it != AccessLevel.NORMAL }
    .distinct()
    .sortedBy { it.rank }

/**
 * The same question asked of the probed capability matrix.
 *
 * The per-metric answer above is the precise one and wins where it exists, but most
 * platform refusals cannot name a level honestly at the point of the read — whether a
 * shell would actually succeed is only knowable by asking one. The matrix *has* asked,
 * which is why `CapabilityStatus.unlockedBy` is populated where `Observed` is not, and
 * why the notice's wording comes from here.
 */
fun unlockedByLevels(capabilities: SystemCapabilities): List<AccessLevel> = capabilities
    .statuses
    .values
    .asSequence()
    .filter { it.availability != Availability.FULL }
    .mapNotNull { it.unlockedBy }
    .filter { it != AccessLevel.NORMAL }
    .distinct()
    .sortedBy { it.rank }
    .toList()

/**
 * How many probed capabilities elevated access would actually improve.
 *
 * Counts `LIMITED` as well as `UNAVAILABLE`: a partial answer that a shell would
 * complete is a real gain, and reporting only the total refusals would undersell the
 * offer. Counts nothing that names no level, so the number cannot exceed what is true.
 */
fun countUnlockable(capabilities: SystemCapabilities): Int = capabilities.statuses.values
    .count { it.availability != Availability.FULL && it.unlockedBy != null }

/**
 * Convenience for the dashboard's "how sure are we?" line: the number of headline
 * figures that are real readings rather than restrictions.
 */
fun countAvailable(vararg observations: Observed<*>): Int =
    observations.count { it is Observed.Value }
