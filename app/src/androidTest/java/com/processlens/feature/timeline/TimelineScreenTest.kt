package com.processlens.feature.timeline

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.processlens.MainActivity
import com.processlens.testing.RECORDING_TIMEOUT_MS
import com.processlens.testing.awaitDescription
import com.processlens.testing.awaitText
import com.processlens.testing.ensureNotRecording
import com.processlens.testing.launchApp
import com.processlens.testing.openInvestigateTab
import com.processlens.testing.recordBriefly
import com.processlens.testing.uniqueRecordingName
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Reading back a recording (Sections 16, 25, 26, 57).
 *
 * The recording opened here is one this test made seconds earlier, with at least one
 * sample confirmed written — so the timeline is being read from real stored measurements
 * of the device the test is running on.
 *
 * Two things are worth pinning down beyond "the screen opens". The first is that the
 * conditions the samples were taken under travel with them: access level, API level,
 * sample interval and device are on the screen, because the same CPU figure means
 * different things at different access levels. The second is Section 16's wording rule —
 * a two-second sampling cadence establishes coincidence, and the screen must not present
 * it as cause.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class TimelineScreenTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    private lateinit var recordingName: String

    @Before
    fun setUp() {
        hiltRule.inject()
        composeRule.launchApp()
        composeRule.openInvestigateTab()

        recordingName = uniqueRecordingName()
        composeRule.recordBriefly(recordingName)
        composeRule.awaitDescription(recordingName).performScrollTo().performClick()
        // The timeline is titled with the recording's name, so this is the point at
        // which the row is known to have opened.
        composeRule.awaitText(recordingName, RECORDING_TIMEOUT_MS)
    }

    @Test
    fun theTimelineOpensOnTheRecordingItWasAskedFor() {
        composeRule.awaitText(recordingName).assertIsDisplayed()
        composeRule.awaitText("The recording").assertIsDisplayed()
    }

    @Test
    fun theConditionsTheSamplesWereTakenUnderTravelWithThem() {
        // Section 42: a figure without its provenance cannot be judged, and provenance
        // for a stored recording means the level and the device it was read on.
        for (row in listOf("State", "Started", "Sample interval", "Samples", "Access level", "Device")) {
            composeRule.awaitDescription(row).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun theChartIsShownForARecordingThatHasSamples() {
        // At least one sample was written before this recording was stopped, so the
        // chart card is on screen — titled with the metric it is drawing. Its own
        // subtitle states how many of those samples carried a readable figure, which is
        // how a gap stays visible as a gap.
        composeRule.awaitText("CPU").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun everyRecordingCanBeExported() {
        // Section 26. All three formats are offered here rather than only the default,
        // and the card says what a file contains before one is written.
        composeRule.awaitText("Export this recording").performScrollTo().assertIsDisplayed()
        for (format in listOf("JSON", "CSV", "Plain text")) {
            composeRule.awaitText(format).performScrollTo().assertIsDisplayed()
        }
        composeRule.awaitText("What the file contains").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theRecordingsOwnLifecycleIsOnItsTimeline() {
        composeRule.awaitText("Events").performScrollTo().assertIsDisplayed()

        // Start and stop are written as events by the recorder itself, so a completed
        // recording always has these two whatever the device did in between — which is
        // what makes them safe to assert. They are found by spoken description because
        // that is where the severity word lives: Section 49 forbids leaving the severity
        // to the colour of the dot.
        composeRule.awaitDescription("Info: Investigation started").performScrollTo()
            .assertIsDisplayed()
        composeRule.awaitDescription("Info: Investigation completed").performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun backReturnsToTheInvestigationList() {
        composeRule.awaitDescription("Back").performClick()

        composeRule.awaitText("Investigate").assertIsDisplayed()
        composeRule.awaitText("Recordings").performScrollTo().assertIsDisplayed()
        composeRule.ensureNotRecording()
    }
}
