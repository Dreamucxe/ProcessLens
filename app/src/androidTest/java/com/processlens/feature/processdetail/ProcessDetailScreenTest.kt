package com.processlens.feature.processdetail

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.processlens.MainActivity
import com.processlens.testing.awaitDescription
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
 * Process details (Sections 0.1, 13, 31, 57).
 *
 * The process opened is ProcessLens' own, because that is the one row guaranteed to be
 * present on every device — and it also exercises the most interesting branch of the
 * actions card, where the app has to explain that ending this process would just close
 * itself.
 *
 * The test that matters most here is [thereIsNoBareKillButton]. Section 0.1 forbids a
 * one-tap kill as a bare primary button, and the deeper reason is that a normal Android
 * app cannot do it at all: a button labelled "Kill" would be a lie about a capability.
 * That is a property worth pinning down in a test, because it is exactly the kind of
 * affordance a later change would add back for the sake of looking powerful.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ProcessDetailScreenTest {

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
        composeRule.filterProcesses("processlens")
        composeRule.awaitNode(ownProcessRow).performClick()
        // The details screen's first card, so this is the point at which navigation is
        // known to have happened.
        composeRule.awaitText("Live metrics")
    }

    @Test
    fun theDetailScreenShowsMeasurementsAndIdentitySeparately() {
        composeRule.awaitText("Live metrics").assertIsDisplayed()
        composeRule.awaitText("Identity").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theDetailScreenSaysHowOftenItIsSampling() {
        // Section 42: a figure with no stated cadence cannot be judged.
        composeRule.awaitTextContaining("Sampled every").assertIsDisplayed()
    }

    @Test
    fun thereIsNoBareKillButton() {
        composeRule.awaitText("Actions").performScrollTo().assertIsDisplayed()

        // No control anywhere on this screen claims to end a process. Exact matching, so
        // the actions card's own explanation of why it cannot is not caught by it.
        for (forbidden in listOf(
            "Kill",
            "Kill process",
            "Force stop",
            "Force-stop",
            "End process",
            "Terminate",
            "Stop process",
        )) {
            composeRule.onAllNodesWithText(forbidden, ignoreCase = true).assertCountEquals(0)
        }
    }

    @Test
    fun theScreenOffersThePlatformsOwnAppInfoInstead() {
        composeRule.awaitText("Open system app info").performScrollTo().assertIsDisplayed()
        // The row says where it sends you before you tap it.
        composeRule.awaitTextContaining("where force stop lives").assertIsDisplayed()
    }

    @Test
    fun theScreenDeclaresItselfReadOnly() {
        composeRule.awaitText("Read-only observation").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theActionsAreRoutesToRealScreens() {
        // Section 2: no button may open a placeholder. Both of these are navigation.
        composeRule.awaitText("Record an investigation").performScrollTo().assertIsDisplayed()
        composeRule.awaitText("Application details").performScrollTo().performClick()

        // The app screen's package card, which no other screen has.
        composeRule.awaitText("From the package manager").assertIsDisplayed()
        composeRule.awaitDescription("Back").performClick()
        composeRule.awaitText("Live metrics").assertIsDisplayed()
    }

    @Test
    fun favouritingIsReversible() {
        // Favourites persist (Section 31), so the test restores whatever it found rather
        // than assuming a starting state.
        val wasFavourite =
            composeRule.present(hasContentDescription("Remove from favourites", substring = true))

        if (wasFavourite) {
            composeRule.awaitDescription("Remove from favourites").performClick()
            composeRule.awaitDescription("Add to favourites").assertIsDisplayed()
            composeRule.awaitDescription("Add to favourites").performClick()
            composeRule.awaitDescription("Remove from favourites").assertIsDisplayed()
        } else {
            composeRule.awaitDescription("Add to favourites").performClick()
            composeRule.awaitDescription("Remove from favourites").assertIsDisplayed()
            composeRule.awaitDescription("Remove from favourites").performClick()
            composeRule.awaitDescription("Add to favourites").assertIsDisplayed()
        }
    }

    @Test
    fun backReturnsToTheList() {
        composeRule.awaitDescription("Back").performClick()

        composeRule.awaitText("Processes").assertIsDisplayed()
        composeRule.awaitDescription("Refresh now").assertIsDisplayed()
    }
}
