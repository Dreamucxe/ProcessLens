package com.processlens.feature.investigate

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.processlens.MainActivity
import com.processlens.testing.RECORDING_TIMEOUT_MS
import com.processlens.testing.awaitDescription
import com.processlens.testing.awaitGone
import com.processlens.testing.awaitNode
import com.processlens.testing.awaitText
import com.processlens.testing.ensureNotRecording
import com.processlens.testing.launchApp
import com.processlens.testing.openInvestigateTab
import com.processlens.testing.present
import com.processlens.testing.recordBriefly
import com.processlens.testing.startRecording
import com.processlens.testing.uniqueRecordingName
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Starting and stopping a real investigation (Sections 14, 24, 57).
 *
 * Nothing here is simulated. Tapping "Start recording" creates a row in the app's own
 * Room database, starts the foreground service, and samples the live system every two
 * seconds; stopping it closes the row and writes the stop event. What the test asserts is
 * therefore the part that is true on any device — that the screen changes to describe a
 * recording in progress, that progress is reported in words rather than only by a
 * spinner, that the finished recording is listed afterwards, and that deleting one asks
 * first.
 *
 * The sample *count* is asserted only as "no longer zero". How many samples land in a
 * given second is a property of the device, not of the app.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class InvestigationFlowTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() {
        hiltRule.inject()
        composeRule.launchApp()
        composeRule.openInvestigateTab()
        composeRule.ensureNotRecording()
    }

    @After
    fun tearDown() {
        // A recording is owned by a service, so an assertion that fails mid-recording
        // would otherwise leave it running into the next test — and, worse, on the
        // device afterwards.
        composeRule.openInvestigateTab()
        composeRule.ensureNotRecording()
    }

    @Test
    fun theSetupCardStatesItsCadenceBeforeAnythingIsRecorded() {
        composeRule.awaitText("New investigation").assertIsDisplayed()
        // Section 42: the interval is part of what the samples mean, so it is stated
        // where the recording is started rather than only in Settings.
        composeRule.awaitNode(hasText("Samples every", substring = true)).assertIsDisplayed()
        composeRule.awaitText("Start recording").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theSetupCardSaysWhatThisDeviceWillBeAbleToSee() {
        // Section 42 again, but before the fact: a blind spot declared up front is a
        // limitation, and the same blind spot found afterwards is a surprise.
        composeRule.awaitText("What this recording can see").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aRecordingStartsRunsAndReportsItsProgress() {
        val name = uniqueRecordingName()

        composeRule.startRecording(name)

        // The screen now describes a recording rather than offering to start one.
        composeRule.awaitText("Stop and summarise").assertIsDisplayed()
        assertThat(composeRule.present(hasText("Start recording"))).isFalse()

        // Progress in words, not only a bar (Section 49). These are DetailRows, whose
        // spoken form is "label: value" — which is also how a screen reader reads them.
        composeRule.awaitDescription("Elapsed").assertIsDisplayed()
        composeRule.awaitDescription("Samples taken").assertIsDisplayed()
        composeRule.awaitDescription("Events derived").assertIsDisplayed()

        // Section 49: the recording state is carried by the navigation label as well as
        // by the red dot next to it.
        composeRule.awaitNode(isSelectable() and hasText("Recording")).assertIsDisplayed()

        // A sample actually lands, which is the difference between a recording and a
        // timer. The counter starts at zero and is incremented only after the snapshot
        // has been committed to the database.
        composeRule.awaitGone(
            hasContentDescription("Samples taken: 0", substring = true),
            RECORDING_TIMEOUT_MS,
        )

        composeRule.awaitText("Stop and summarise").performClick()

        // Stopping returns the setup card and files the recording in the history list.
        composeRule.awaitText("New investigation", RECORDING_TIMEOUT_MS).assertIsDisplayed()
        composeRule.awaitDescription(name, RECORDING_TIMEOUT_MS).assertIsDisplayed()
    }

    @Test
    fun theLiveCardNamesItsScope() {
        composeRule.startRecording(uniqueRecordingName())

        // Nothing was scoped, so it says so rather than leaving the reader to assume.
        composeRule.awaitText("Whole system").assertIsDisplayed()
        composeRule.awaitDescription("Planned").assertIsDisplayed()
    }

    @Test
    fun aQuietTimelineIsPresentedAsAResult() {
        composeRule.startRecording(uniqueRecordingName())

        composeRule.awaitText("Events so far").performScrollTo().assertIsDisplayed()
        // Section 42: no event is not the same as no data, and the screen says which.
        composeRule.awaitNode(
            hasText("A quiet timeline is a real result", substring = true),
        ).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun deletingARecordingAsksFirstAndKeepMeansKeep() {
        val name = uniqueRecordingName()
        composeRule.recordBriefly(name)

        composeRule.awaitText("Delete").performScrollTo().performClick()

        // Section 26: the only copy is on this device, and the dialog says so.
        composeRule.awaitText("Delete this recording?").assertIsDisplayed()
        composeRule.awaitNode(
            hasText("Nothing was ever uploaded", substring = true),
        ).assertIsDisplayed()

        composeRule.awaitText("Keep").performClick()

        // Declining the dialog deletes nothing. Asserted on the recording this test
        // made itself, so it holds whichever row's Delete was tapped.
        composeRule.awaitGone(hasText("Delete this recording?"))
        composeRule.awaitDescription(name).assertIsDisplayed()
    }
}
