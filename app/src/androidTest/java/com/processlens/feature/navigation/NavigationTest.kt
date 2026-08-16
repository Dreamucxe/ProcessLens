package com.processlens.feature.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.processlens.MainActivity
import com.processlens.testing.awaitNode
import com.processlens.testing.awaitText
import com.processlens.testing.ensureNotRecording
import com.processlens.testing.launchApp
import com.processlens.testing.openInvestigateTab
import com.processlens.testing.openTab
import com.processlens.testing.present
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Top-level navigation (Sections 33, 49, 57).
 *
 * The bar and the rail are the same five [TopLevelDestination]s built by the same
 * composable path — `selectable` with `Role.Tab` and a visible label — so these tests hold
 * on a phone and on a tablet without being written twice. That shared implementation is
 * also why the destinations are read from the enum here: adding a sixth tab without a
 * screen behind it, or renaming one, fails this test.
 *
 * The Investigate tab is the exception the helpers exist for: its label reads "Recording"
 * while a recording is running, because Section 49 does not allow the red dot to be the
 * only sign that something is going on.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class NavigationTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() {
        hiltRule.inject()
        composeRule.launchApp()
        // Independent of test order: the Investigate tab is labelled "Recording" while a
        // recording is running, and a recording outlives the test that started it.
        composeRule.openInvestigateTab()
        composeRule.ensureNotRecording()
        composeRule.openTab(TopLevelDestination.OVERVIEW.label)
        composeRule.awaitText("Overview")
    }

    @Test
    fun everyTopLevelDestinationIsOffered() {
        for (destination in TopLevelDestination.entries) {
            assertThat(composeRule.present(isSelectable() and hasText(destination.label)))
                .isTrue()
        }
    }

    @Test
    fun everyTopLevelDestinationOpensItsScreen() {
        // The screen each tab lands on, identified by its own title. Apps is deliberately
        // not "Apps": the tab is short because the bar is narrow, the screen is not.
        val screens = listOf(
            TopLevelDestination.OVERVIEW to "Overview",
            TopLevelDestination.PROCESSES to "Processes",
            TopLevelDestination.INVESTIGATE to "Investigate",
            TopLevelDestination.APPS to "Applications",
            TopLevelDestination.SETTINGS to "Settings",
        )
        // Fails if a destination is added without being covered here.
        assertThat(screens.map { it.first }).containsExactlyElementsIn(TopLevelDestination.entries)

        for ((destination, title) in screens) {
            composeRule.openTab(destination.label)

            composeRule.awaitText(title).assertIsDisplayed()
            composeRule.awaitNode(isSelectable() and hasText(destination.label))
                .assertIsSelected()
        }
    }

    @Test
    fun theSelectedTabIsReportedInSemanticsRatherThanOnlyInColour() {
        // Section 49. `Role.Tab` plus a selected state is what a screen reader announces
        // as "selected"; a tinted icon is not announced at all.
        composeRule.openTab(TopLevelDestination.PROCESSES.label)

        composeRule.awaitNode(
            isSelectable() and hasText(TopLevelDestination.PROCESSES.label),
        ).assertIsSelected()

        // And exactly one tab claims it, so the announcement is unambiguous.
        val selected = TopLevelDestination.entries.filter {
            composeRule.present(isSelectable() and isSelected() and hasText(it.label))
        }
        assertThat(selected).containsExactly(TopLevelDestination.PROCESSES)
    }

    @Test
    fun returningToATabDoesNotLoseWhereYouWere() {
        // Navigating away from a tab and back must not drop the user into a deeper
        // screen they never asked for, nor reset the tab they were on.
        composeRule.openTab(TopLevelDestination.APPS.label)
        composeRule.awaitText("Applications").assertIsDisplayed()

        composeRule.openTab(TopLevelDestination.OVERVIEW.label)
        composeRule.awaitText("Overview").assertIsDisplayed()

        composeRule.openTab(TopLevelDestination.APPS.label)
        composeRule.awaitText("Applications").assertIsDisplayed()
        composeRule.awaitNode(
            isSelectable() and hasText(TopLevelDestination.APPS.label),
        ).assertIsSelected()
    }
}
