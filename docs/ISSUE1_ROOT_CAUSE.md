# Issue #1 — "Not loading until probed SU: Reading system state"

Root-cause note. Written before the fix, from four independent reads of the source.

## Summary

**The SELinux denials are not the cause.** They are real, and they are a second defect worth
fixing, but the hang happens on every device regardless of them.

Overview hangs because `OverviewViewModel.state` is a `combine()` of five flows, and one of those
flows — `observeCapabilities()` — never emits on a cold start. `kotlinx` `combine` is all-or-nothing:
it publishes nothing until *every* source has emitted at least once. So the state never leaves
`initialValue = State()`, where `system == null`, and `OverviewScreen` renders `LoadingBlock()`
— default label `"Reading system state"` — forever.

The reporter's own hypothesis ("the non-root collector treats denied reads as not-ready and retries
forever") is **refuted**. Every `/proc` and `/sys` read funnels through `ProcFsReader.readFile`,
which catches `SecurityException` and EACCES-bearing `IOException` and returns a terminal
`Observed.Restricted`. No read site retries, blocks, or asserts non-null. A denied `loadavg` and a
denied thermal zone still produce a complete `SystemState`. Those samples were being computed
correctly the whole time — and silently discarded by the starved `combine`.

## The causal chain

1. `SystemRepositoryImpl.kt:51` — `private val capabilities = MutableStateFlow<SystemCapabilities?>(null)`
2. `SystemRepositoryImpl.kt:111-112` — `observeCapabilities() = capabilities.asStateFlow().filterNotNull().distinctUntilChanged()`.
   `filterNotNull()` swallows the seeded `null`, so the flow is **silent until something writes the field**.
3. The only writer is `refreshCapabilities()` (`:114-118`). **Nothing on the cold-start path calls it.**
   `ProcessLensApplication` only installs the crash handler; `MainActivity.onCreate` only calls
   `setContent`; `OverviewViewModel` has no `init {}` block; `ProcessLensNavHost` has no `LaunchedEffect`.
   All ten real call sites are user-initiated actions on the Access, Capabilities, Network or Settings screens.
4. `OverviewViewModel.kt:86-92` — a 5-arity `combine` whose second source is that silent flow. The four
   chained `.combine` operators for favourites, history, refreshing and errors are all *downstream* of
   the gate, so their emissions cannot produce output either.
5. `OverviewViewModel.kt:112-118` — `stateIn(..., initialValue = State())` therefore publishes `State()`
   once and never again. `State.system` is `null`; `isFirstLoad` is defined as `system == null` (`:58`).
6. `OverviewScreen.kt:129-133` — `if (system == null) { LoadingBlock(); return@Column }`. An
   unconditional gate with no deadline, no error branch and no retry affordance.
   `LoadingBlock`'s default label is `"Reading system state"` (`ScreenScaffold.kt:203`).

`ScreenHeader` is composed *above* that early return, which is why the title still shows and the app
looks alive rather than frozen — matching "no crash, no FATAL EXCEPTION".

## Why "Probe root" appears to fix it

It is not the root grant. Navigating to Settings → Access constructs `AccessViewModel`, whose
`init { refresh() }` (`AccessViewModel.kt:104-106, 127-132`) calls `invalidateAccess()` then
`refreshCapabilities()`. That writes `capabilities.value`, and because `SystemRepositoryImpl` is a
`@Singleton` the flow stays non-null for the rest of the process — so Overview's `combine` finally
receives its missing input and the dashboard renders. **Merely opening the Access screen is enough;
the button is incidental.** Relaunching resets the in-memory singleton to `null`, which is exactly why
the hang reproduces on every launch.

Proof that root is not involved: `probeRoot()` sets `probedState = GRANTED`, then its own
`refreshCapabilitiesOnly()` calls `invalidateAccess()` → `CompositeSystemObserver.invalidate()` →
`RootShell.invalidate()`, which wipes the grant (`RootShell.kt:73-75`) *before* routing is
re-evaluated. `resolve()` then sees `BINARY_PRESENT.isUsable == false` (`Capability.kt:188`) and keeps
the standard observer — yet Overview renders anyway.

## Blast radius

**Eleven** ViewModels `combine()` on `observeCapabilities()` and share the identical gate:
`AccessViewModel:79`, `AppDetailViewModel:88`, `BatteryViewModel:84`, `CapabilitiesViewModel:69`,
`CpuViewModel:55`, `InvestigateViewModel:131`, `MemoryViewModel:49`, `NetworkViewModel:85`,
`OverviewViewModel:88`, `ProcessDetailViewModel:82`, `AppShellViewModel:48`.

`AppShellViewModel` gates the theme and the first-run onboarding overlay app-wide; it survives only
because its `State()` default carries real defaults. `CapabilitiesViewModel` is the one screen whose
entire job is showing capabilities. Overview is simply the screen the user lands on first.

## The fix was already written and never wired up

`SystemCapabilities.unknown(apiLevel)` exists at `Capability.kt:158-170`. Its KDoc reads verbatim
*"Used only as an initial UI state before the first real evaluation"*, and `statuses = emptyMap()`
makes every `get()` fall back to the `"Not evaluated on this device."` status, so it is designed to be
safe to render. A grep for `unknown(` across `app/src` finds two hits: the declaration, and one
androidTest. **Zero production callers.** `OverviewViewModel.revalidateAccess()` (`:161-166`) — which
would have broken the deadlock — likewise has zero callers and is dead code.

A second piece of evidence that the placeholder is the intended design: `AppShellViewModel.summarise()`
already opens with `if (total == 0) return "Checking what this device allows…"`, and `total` is the sum
of the three `statuses` counts. That branch is reachable *only* from a capabilities object with an empty
`statuses` map — which is exactly what `unknown()` produces and what nothing was ever emitting. The
author wrote the handler for this state, wrote the value for this state, and then gated the flow so
neither could ever be used.

The empty-`statuses` signal is also how a consumer tells "not yet evaluated" apart from "evaluated,
and this device allows nothing" — a distinction the no-root banner depends on, since `unknown()`
reports `accessLevel = NORMAL` and `rootState = UNAVAILABLE` before any detection has run.

## Secondary defects found

| # | Defect | Location | Consequence |
|---|---|---|---|
| 2 | No session memo of a permanent denial. The 2 s poll re-reads `/proc/loadavg` and re-walks `/sys/class/thermal` on **every tick**. | `SystemRepositoryImpl.kt:61-73`, `ProcFsReader.kt:99-115, 309-338` | The 50 minutes of repeating `avc: denied` in the report. Wasted work and log spam, not a hang. The flow runs on the injected `Dispatchers.Default`, whose workers are named `DefaultDispatcher-worker-N` — truncated to `comm="DefaultDispatch"` in the denial lines. |
| 3 | Both shells drain stdout to EOF, *then* stderr, *then* consult the timeout. | `RootShell.kt:103-111`, `ShizukuShell.kt:131-143` | `timeoutMillis` is unreachable. A child that fills the 64 KiB stderr pipe blocks on write, so stdout never reaches EOF and the read never returns — a genuine deadlock. An `su` awaiting an unanswered Magisk prompt blocks forever, not for 10 s. |
| 4 | `invalidateAccess()` wipes a just-obtained root grant before routing re-evaluates. | `RootShell.kt:73-75` via `CompositeSystemObserver.kt:89-92` | Root never actually engages, even after a successful grant. |
| 5 | No `.catch {}` anywhere in the codebase; no try/catch around any flow collection. | all ViewModels | A throwing collector would fail silently and leave the same permanent spinner. |

## Fix plan

| Item | Requirement | Files |
|---|---|---|
| F1 | R2, R5 — loading always resolves | `data/repository/SystemRepositoryImpl.kt` |
| F2 | R1, R3, R7 — terminal per-metric unavailability, public APIs, one log line per metric | new `core/system/RestrictionCache.kt`, `core/system/ProcFsReader.kt`, `core/system/StandardAndroidObserver.kt` |
| F3 | R4 — real timeout, concurrent drain, destroy on timeout | new `core/system/ProcessRunner.kt`, `core/system/RootShell.kt`, `core/system/ShizukuShell.kt` |
| F4 | defect 4 — keep a proven grant across a route invalidation | `core/system/CompositeSystemObserver.kt` |
| F5 | R6 — N/A wording, no-root banner, reachable re-probe | `feature/overview/OverviewScreen.kt`, `feature/overview/OverviewViewModel.kt`, `core/designsystem/ScreenScaffold.kt` |
