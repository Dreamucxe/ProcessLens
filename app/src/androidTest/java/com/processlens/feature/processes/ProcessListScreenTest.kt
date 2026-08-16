package com.processlens.feature.processes

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.processlens.MainActivity
import com.processlens.testing.awaitDescription
import com.processlens.testing.awaitGone
import com.processlens.testing.awaitNode
import com.processlens.testing.awaitText
import com.processlens.testing.awaitTextContaining
import com.processlens.testing.filterProcesses
import com.processlens.testing.launchApp
import com.processlens.testing.openTab
import com.processlens.testing.ownProcessRow
import com.processlens.testing.present
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The process list (Sections 11, 42, 57).
 *
 * The one row this test may assume exists is ProcessLens' own process: `/proc/self` is
 * readable to every app on every Android version, so it is listed at every access level.
 * Everything beyond that varies by device and by whether Shizuku is connected, and the
 * app's job is to say which — so the assertions here are about the controls, the
 * filtering, and the wording that keeps an unreadable metric from reading as a zero.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ProcessListScreenTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() {
        hiltRule.inject()
        composeRule.launchApp()
        composeRule.openTab("Processes")
        composeRule.awaitText("Processes")
    }

    @Test
    fun theListOpensWithItsOwnControls() {
        composeRule.awaitDescription("Filter this list").assertIsDisplayed()
        composeRule.awaitDescription("Process tree").assertIsDisplayed()
        composeRule.awaitDescription("Refresh now").assertIsDisplayed()
    }

    @Test
    fun everyFilterIsOffered() {
        // Read from the enum rather than hardcoded, so renaming a filter fails this test
        // instead of silently passing it.
        for (filter in ProcessFilter.entries) {
            assertThat(composeRule.present(hasText(filter.label))).isTrue()
        }
    }

    @Test
    fun everySortIsOffered() {
        for (sort in ProcessSort.entries) {
            assertThat(composeRule.present(hasText(sort.label))).isTrue()
        }
    }

    @Test
    fun theActiveSortIsAnnouncedAndNotOnlyColoured() {
        // Section 49: a chip that is only tinted differently tells a screen reader
        // nothing, so selection is carried in the state description too.
        composeRule.awaitNode(
            hasText(ProcessSort.CPU.label) and hasStateDescription("Selected"),
        ).assertIsDisplayed()
    }

    @Test
    fun changingTheSortKeepsTheListUsable() {
        composeRule.awaitText(ProcessSort.NAME.label).performClick()

        composeRule.awaitNode(
            hasText(ProcessSort.NAME.label) and hasStateDescription("Selected"),
        ).assertIsDisplayed()
        // The list is still there afterwards: sorting must not empty it.
        composeRule.awaitNode(ownProcessRow).assertIsDisplayed()

        composeRule.awaitText(ProcessSort.CPU.label).performClick()
    }

    @Test
    fun theAppsOwnProcessIsAlwaysListed() {
        // True at every access level on every device, which is what makes it a safe
        // assertion in a test that reads real system state.
        composeRule.filterProcesses("processlens")

        composeRule.awaitNode(ownProcessRow).assertIsDisplayed()
    }

    @Test
    fun aFilterThatMatchesNothingSaysSoInsteadOfShowingAnEmptyScreen() {
        composeRule.filterProcesses("zzzznosuchprocesszzzz")

        composeRule.awaitTextContaining("Nothing matches").assertIsDisplayed()
        // And offers the way out, rather than leaving the user in a dead end.
        composeRule.awaitText("Show all").assertIsDisplayed()
    }

    @Test
    fun theListStatesThatADashIsNotAZero() {
        // Section 42's rule at the point of reading. Asserted with the list filtered
        // down so the footer is composed rather than far below the fold.
        composeRule.filterProcesses("zzzznosuchprocesszzzz")

        composeRule.awaitTextContaining("They are not zero").assertIsDisplayed()
    }

    @Test
    fun closingTheFilterBoxClearsIt() {
        composeRule.filterProcesses("processlens")

        composeRule.awaitDescription("Close the filter box").performClick()

        // The box is gone, and with it the query: reopening must not resurrect a filter
        // the user cannot see.
        composeRule.awaitGone(hasSetTextAction())
        composeRule.awaitDescription("Filter this list").performClick()
        composeRule.awaitNode(hasSetTextAction()).assertIsDisplayed()
        assertThat(composeRule.present(hasText("processlens"))).isFalse()
    }

    @Test
    fun theTreeIconOpensTheProcessTree() {
        composeRule.awaitDescription("Process tree").performClick()

        // The tree screen's own title. Section 12: on a device with no readable parent
        // PIDs this screen says so — either outcome is a real screen, and the test
        // asserts arrival rather than content.
        composeRule.awaitText("Process tree").assertIsDisplayed()
        composeRule.awaitDescription("Back").performClick()
        composeRule.awaitText("Processes").assertIsDisplayed()
    }

    @Test
    fun theProcessesTabStaysMarkedAsSelected() {
        composeRule.onAllNodes(isSelectable() and hasText("Processes")).onFirst().assertIsSelected()
    }
}
