package com.processlens.testing

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput

/**
 * Shared plumbing for the instrumented UI tests (Section 57).
 *
 * Two things shape every helper here.
 *
 * The first is that there is not a single `testTag` in the production sources, and that
 * is deliberate: a test that finds a node by an invisible tag can pass while the screen
 * is unreadable. Everything below locates nodes the way a person does — by the text on
 * screen or by the accessibility description a screen reader would announce — so a
 * failure here is also an accessibility failure (Section 48).
 *
 * The second is that these tests run against the *real* app: real `/proc` reads, real
 * Room database, real DataStore. Nothing is stubbed, so no assertion may depend on a
 * particular figure — a device where CPU is unreadable is a valid device, and the app is
 * correct to say so. What the tests assert is structure, navigation and wording, plus
 * the handful of facts Android guarantees on every device (an app can always see its own
 * process; a recording it just wrote is in its own database).
 */

/** Cold start on a slow device, plus Hilt graph construction. */
const val LAUNCH_TIMEOUT_MS = 30_000L

/** Ordinary navigation and recomposition. Long enough to absorb a slow first frame. */
const val UI_TIMEOUT_MS = 10_000L

/** A recording writes its first sample one interval in; the default interval is 2 s. */
const val RECORDING_TIMEOUT_MS = 25_000L

/** The onboarding overlay's only button (Section 52: nothing is gated behind it). */
const val ONBOARDING_DISMISS = "Start looking"

fun ComposeTestRule.present(matcher: SemanticsMatcher): Boolean =
    onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

/**
 * Waits for [matcher] and returns the first match.
 *
 * `onFirst` rather than `onNode` because several legitimate nodes can carry the same
 * text — "Processes" is both a navigation label and a screen title — and a test that
 * only needs *a* match should not fail on the duplicate.
 */
fun ComposeTestRule.awaitNode(
    matcher: SemanticsMatcher,
    timeoutMillis: Long = UI_TIMEOUT_MS,
): SemanticsNodeInteraction {
    waitUntil(timeoutMillis) { present(matcher) }
    return onAllNodes(matcher).onFirst()
}

fun ComposeTestRule.awaitText(
    text: String,
    timeoutMillis: Long = UI_TIMEOUT_MS,
): SemanticsNodeInteraction = awaitNode(hasText(text), timeoutMillis)

fun ComposeTestRule.awaitTextContaining(
    text: String,
    timeoutMillis: Long = UI_TIMEOUT_MS,
): SemanticsNodeInteraction = awaitNode(hasText(text, substring = true), timeoutMillis)

/** Finds a node by what a screen reader would say, which is how icon-only controls are found. */
fun ComposeTestRule.awaitDescription(
    description: String,
    timeoutMillis: Long = UI_TIMEOUT_MS,
): SemanticsNodeInteraction =
    awaitNode(hasContentDescription(description, substring = true), timeoutMillis)

fun ComposeTestRule.awaitGone(matcher: SemanticsMatcher, timeoutMillis: Long = UI_TIMEOUT_MS) {
    waitUntil(timeoutMillis) { !present(matcher) }
}

/**
 * Gets the app to a usable dashboard, dismissing onboarding if this is a first run.
 *
 * The overlay cannot be waited for unconditionally — it appears once per install, so
 * whichever test runs first sees it and the rest do not. It also cannot be ignored,
 * because it sits over the whole app and would swallow every tap. Both the overlay and
 * the dashboard are driven by one state emission in `MainActivity`, so a single idle
 * wait settles which of them is on screen.
 */
fun ComposeTestRule.launchApp() {
    waitUntil(LAUNCH_TIMEOUT_MS) {
        present(hasText(ONBOARDING_DISMISS)) || present(hasText("Overview"))
    }
    waitForIdle()
    if (present(hasText(ONBOARDING_DISMISS))) {
        onAllNodes(hasText(ONBOARDING_DISMISS)).onFirst().performClick()
        awaitGone(hasText(ONBOARDING_DISMISS))
    }
    awaitText("Overview")
}

/**
 * Taps a bottom-bar or rail destination (Section 33).
 *
 * `isSelectable` is what separates a navigation item from everything else that shares
 * its wording: the dashboard's "Processes" quick action is clickable but not selectable,
 * and only the navigation item carries a selected state.
 */
fun ComposeTestRule.openTab(label: String) {
    awaitNode(isSelectable() and hasText(label)).performClick()
    waitForIdle()
}

/** The Investigate tab, whose label reads "Recording" while a recording is running. */
fun ComposeTestRule.openInvestigateTab() {
    awaitNode(
        isSelectable() and (hasText("Investigate") or hasText("Recording")),
    ).performClick()
    waitForIdle()
}

/**
 * The app's own process row.
 *
 * Every Android version lets an app read `/proc/self`, so this row exists at every
 * access level on every device — it is the only process a test may assume is listed.
 * Matching either the row's spoken description or its visible package text keeps the
 * matcher working whichever of the two the row ends up exposing.
 */
val ownProcessRow: SemanticsMatcher =
    (
        hasContentDescription("ProcessLens", substring = true) or
            hasText("com.processlens", substring = true)
        ) and hasClickAction()

/** Opens the process list's filter box and types [query] into it. */
fun ComposeTestRule.filterProcesses(query: String) {
    awaitDescription("Filter this list").performClick()
    awaitNode(hasSetTextAction()).performTextInput(query)
    waitForIdle()
}

// ------------------------------------------------------------------------ recording

/**
 * Leaves the Investigate screen showing its setup card.
 *
 * A recording survives the test that started it — it is held by a foreground service,
 * not by the activity — so a test that assumes the setup card is on screen has to close
 * out whatever a previous test left running.
 */
fun ComposeTestRule.ensureNotRecording() {
    if (present(hasText("Stop and summarise"))) {
        awaitText("Stop and summarise").performClick()
    }
    awaitText("New investigation", RECORDING_TIMEOUT_MS)
}

/**
 * Fills in the form and starts a real recording, returning once it is running.
 *
 * "Until I stop it" rather than a timed duration: a five-minute recording that finishes
 * on its own halfway through an assertion would fail the test for a reason that has
 * nothing to do with the test.
 */
fun ComposeTestRule.startRecording(name: String) {
    ensureNotRecording()

    // Duration first, while the layout is still settled — typing into the name field
    // raises the IME, which resizes the window and moves everything below it.
    awaitText("Until I stop it").performScrollTo().performClick()

    // `performTextInput` takes focus itself, so there is no separate tap on the field.
    awaitNode(hasSetTextAction()).performTextInput(name)
    waitForIdle()

    awaitText("Start recording").performScrollTo().performClick()
    // The live card only appears once the recorder's loop is actually running, so this
    // is the point at which recording is known to have begun rather than been asked for.
    awaitText("Stop and summarise", RECORDING_TIMEOUT_MS)
}

/**
 * Records until at least one sample has been written, then stops.
 *
 * The wait is on the sample counter leaving zero rather than on a fixed sleep: the
 * counter is incremented after the snapshot is committed, so this returns when there is
 * genuinely something in the database to open a timeline on.
 */
fun ComposeTestRule.recordBriefly(name: String) {
    startRecording(name)
    awaitGone(hasContentDescription("Samples taken: 0", substring = true), RECORDING_TIMEOUT_MS)

    awaitText("Stop and summarise").performClick()
    awaitText("New investigation", RECORDING_TIMEOUT_MS)
    // The finished recording is in the history list, newest first.
    awaitDescription(name, RECORDING_TIMEOUT_MS)
}

/** A recording name no other run can collide with. */
fun uniqueRecordingName(): String = "UI test " + System.currentTimeMillis()
