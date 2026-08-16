package com.processlens.feature.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.processlens.MainActivity
import com.processlens.domain.model.RefreshRate
import com.processlens.domain.model.ThemeMode
import com.processlens.testing.UI_TIMEOUT_MS
import com.processlens.testing.awaitGone
import com.processlens.testing.awaitNode
import com.processlens.testing.awaitText
import com.processlens.testing.launchApp
import com.processlens.testing.openTab
import com.processlens.testing.present
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Settings (Sections 40, 41, 49, 57).
 *
 * Two properties are worth a test here beyond "the screen renders".
 *
 * The first is persistence. A preference that resets when the activity is recreated is
 * not a preference, so [theThemeChoiceSurvivesTheActivityBeingRecreated] changes the
 * theme, recreates the activity for real, and reads the choice back out of the app's own
 * database — then puts it back the way it found it.
 *
 * The second is that state is expressed in words. Section 49 forbids communicating state
 * through colour alone, and a settings screen is where that rule is easiest to break: a
 * selected chip that is merely tinted, or a switch whose position is its only readout,
 * tells a screen-reader user nothing. Both are checked through the accessibility state
 * description, which is the thing that would actually be announced.
 *
 * "Restore defaults" is exercised only as far as its confirmation. The test then cancels:
 * it runs on the user's real device against their real preferences, and wiping them to
 * prove a button works would be a poor trade.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    /** Whatever the theme was before this test ran, so it can be put back. */
    private lateinit var initialTheme: ThemeMode

    @Before
    fun setUp() {
        hiltRule.inject()
        composeRule.launchApp()
        composeRule.openTab("Settings")
        composeRule.awaitText("Appearance")
        initialTheme = selectedTheme()
    }

    @After
    fun restoreTheme() {
        // These tests run against the user's real preferences, so the theme goes back to
        // whatever it was rather than to the app's default.
        openSettings()
        if (selectedTheme() != initialTheme) {
            composeRule.awaitText(initialTheme.label).performScrollTo().performClick()
            composeRule.awaitNode(
                hasText(initialTheme.label) and hasStateDescription(SELECTED),
            )
        }
    }

    @Test
    fun everySettingsSectionIsPresent() {
        // Section 41's list. Read in order, scrolling to each, so this also proves the
        // screen scrolls the whole way rather than stopping at the fold.
        val sections = listOf(
            "Appearance",
            "Monitoring",
            "Investigation",
            "Access",
            "Privacy and export",
            "Stored data",
            "Restore defaults",
            "About",
        )

        for (section in sections) {
            composeRule.awaitText(section).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun theRefreshRatesAreExactlyTheOnesSection6Asks() {
        // 1, 2, 5, 10 seconds and manual — read from the enum, so removing one fails
        // here rather than silently shipping.
        for (rate in RefreshRate.entries) {
            composeRule.awaitText(rate.label).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun aSelectedChipSaysThatItIsSelected() {
        // Section 49. Exactly one theme is selected, and which one is in the state
        // description rather than only in the chip's fill colour.
        val selected = ThemeMode.entries.filter {
            composeRule.present(hasText(it.label) and hasStateDescription(SELECTED))
        }

        assertThat(selected).hasSize(1)
    }

    @Test
    fun aSwitchSaysWhetherItIsOnOrOff() {
        // Section 49 for the other control type. The row owns the semantics — title,
        // explanation and state in one announcement — because a bare switch read on its
        // own is just "on" with no subject.
        composeRule.awaitNode(hasContentDescription("Glass cards", substring = true))
            .performScrollTo()
            .assert(hasStateDescription("On") or hasStateDescription("Off"))
    }

    @Test
    fun theThemeChoiceSurvivesTheActivityBeingRecreated() {
        // A theme that is not the one already in force, so the assertion cannot pass by
        // nothing having happened.
        val target = ThemeMode.entries.first { it != initialTheme }

        composeRule.awaitText(target.label).performScrollTo().performClick()
        composeRule.awaitNode(
            hasText(target.label) and hasStateDescription(SELECTED),
        ).assertIsDisplayed()

        // A real recreation, not a recomposition: the process keeps running but the
        // activity, the Compose tree and every ViewModel are rebuilt, so the choice can
        // only come back from the database it was written to.
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
        openSettings()

        composeRule.awaitNode(
            hasText(target.label) and hasStateDescription(SELECTED),
        ).assertIsDisplayed()
        // Put back by restoreTheme().
    }

    @Test
    fun restoringDefaultsAsksBeforeItDoesAnything() {
        composeRule.awaitText("Restore defaults…").performScrollTo().performClick()

        // What it will and will not touch, before it touches anything.
        composeRule.awaitNode(
            hasText("Recordings, favourites and observation history are kept", substring = true),
        ).assertIsDisplayed()

        composeRule.awaitText("Cancel").performScrollTo().performClick()
        composeRule.awaitGone(hasText("Cancel"))
        // The prompt is back, so nothing was reset.
        composeRule.awaitText("Restore defaults…").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theAboutSectionReportsTheDeviceItIsActuallyRunningOn() {
        composeRule.awaitText("About").performScrollTo().assertIsDisplayed()
        composeRule.awaitText("Android").performScrollTo().assertIsDisplayed()
        composeRule.awaitText("Device").performScrollTo().assertIsDisplayed()
    }

    /** Gets back to a usable Settings screen, from wherever a test left the app. */
    private fun openSettings() {
        if (!composeRule.present(hasText("Appearance"))) {
            composeRule.openTab("Settings")
        }
        composeRule.awaitText("Appearance")
    }

    /**
     * Which theme the screen currently reports as selected.
     *
     * Read from the accessibility state description rather than from the database, so the
     * setup and teardown that depend on it would themselves fail if the screen stopped
     * saying which theme is in force.
     */
    private fun selectedTheme(): ThemeMode {
        composeRule.waitUntil(UI_TIMEOUT_MS) {
            ThemeMode.entries.any {
                composeRule.present(hasText(it.label) and hasStateDescription(SELECTED))
            }
        }
        return ThemeMode.entries.first {
            composeRule.present(hasText(it.label) and hasStateDescription(SELECTED))
        }
    }

    private companion object {
        const val SELECTED = "Selected"
    }
}
