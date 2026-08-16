package com.processlens.feature.overview

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.processlens.MainActivity
import com.processlens.testing.awaitDescription
import com.processlens.testing.awaitNode
import com.processlens.testing.awaitText
import com.processlens.testing.awaitTextContaining
import com.processlens.testing.launchApp
import com.processlens.testing.present
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The dashboard (Sections 5, 32, 43, 47, 57).
 *
 * These assertions are about what the dashboard offers and where its taps go, never
 * about a figure it displays: the app is under test on a real device, so a restricted
 * CPU reading is a correct outcome, not a failure. What must hold on every device is
 * that all nine Section 32 quick actions exist, that each one reaches a real screen
 * rather than a placeholder, and that the two cards which keep the app honest — what
 * this device actually allows, and what ProcessLens itself costs — are present.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class OverviewScreenTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() {
        hiltRule.inject()
        composeRule.launchApp()
    }

    @Test
    fun theDashboardOpensOnOverview() {
        composeRule.awaitText("Overview").assertIsDisplayed()
    }

    @Test
    fun theHeaderNamesTheAccessLevelItIsWorkingAt() {
        // Section 42: the reader has to be able to tell what the figures below were
        // read at, so the access level is in the header rather than buried in Settings.
        composeRule.awaitTextContaining("access").assertIsDisplayed()
    }

    @Test
    fun everySection32QuickActionIsOnTheDashboard() {
        // Located by spoken description, not label: "CPU" alone is too terse to be a
        // usable accessibility name, so each tile carries a fuller one.
        val actions = listOf(
            "Process list",
            "CPU detail",
            "Memory detail",
            "Battery detail",
            "Network detail",
            "Installed applications",
            "Permission inspector",
            "Favourites",
        )

        for (action in actions) {
            // Scrolling to it first makes this an assertion about being reachable,
            // not merely about existing somewhere in the tree.
            composeRule.awaitDescription(action).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun theInvestigationActionIsPromotedAndDescribesItsOwnState() {
        // The card's wording follows the recorder: offering "Start investigation" while
        // one is already running would describe something the tap does not do.
        composeRule.awaitNode(
            hasContentDescription("Start investigation", substring = true) or
                hasContentDescription("Investigation in progress", substring = true),
        ).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theProcessesQuickActionOpensTheRealProcessList() {
        composeRule.awaitDescription("Process list").performScrollTo().performClick()

        composeRule.awaitText("Processes").assertIsDisplayed()
        // Controls that only exist on the list screen, so this cannot be satisfied by
        // the navigation label of the same name.
        composeRule.awaitDescription("Refresh now").assertIsDisplayed()
        composeRule.awaitDescription("Process tree").assertIsDisplayed()
    }

    @Test
    fun theCpuQuickActionOpensTheProcessorScreen() {
        composeRule.awaitDescription("CPU detail").performScrollTo().performClick()

        // The screen is titled "Processor", so this fails if the tap went nowhere.
        composeRule.awaitText("Processor").assertIsDisplayed()
    }

    @Test
    fun theMemoryQuickActionOpensTheMemoryScreen() {
        composeRule.awaitDescription("Memory detail").performScrollTo().performClick()

        // "Memory" alone is ambiguous — it is also a tile label and a sort chip — so this
        // waits for the headline card that only the memory screen has.
        composeRule.awaitText("In use").assertIsDisplayed()
    }

    @Test
    fun thePermissionsQuickActionOpensThePermissionInspector() {
        composeRule.awaitDescription("Permission inspector").performScrollTo().performClick()

        composeRule.awaitText("Permissions").assertIsDisplayed()
        // A control unique to the inspector, so the assertion cannot be met by the
        // dashboard tile of the same name.
        composeRule.awaitDescription("Re-read every package's permissions").assertIsDisplayed()
    }

    @Test
    fun theDashboardStatesWhatThisDeviceAllowsRatherThanAssumingIt() {
        composeRule.awaitText("What this device allows").performScrollTo().assertIsDisplayed()
        // Section 47's rule, in the subtitle a user actually reads.
        assertThat(
            composeRule.present(
                hasText("Probed on this device, not assumed from the Android version"),
            ),
        ).isTrue()
    }

    @Test
    fun theDashboardShowsWhatProcessLensItselfCosts() {
        // Section 43: a monitor that hides its own cost cannot be held to it.
        composeRule.awaitText("ProcessLens itself").performScrollTo().assertIsDisplayed()
    }
}
