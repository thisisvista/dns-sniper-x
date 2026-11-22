package com.thisisvista.dnssniperx

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.thisisvista.dnssniperx.data.ResolverDatabase
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the real Activity and Compose UI against the on-device Room database.
 * This is an emulator UI E2E smoke test, not a substitute for real ISP/network tests.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityEndToEndTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun clearLocalDatabase() {
        ResolverDatabase.getInstance(
            InstrumentationRegistry.getInstrumentation().targetContext
        ).clearAllTables()
    }

    @Test
    fun manualImportDeduplicatesAndSurvivesActivityRecreation() {
        val address = "198.51.100.77"

        composeRule.onNodeWithText("DNS addresses")
            .performScrollTo()
            .performTextInput(address)
        composeRule.onNodeWithText("Import to local library")
            .performScrollTo()
            .performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("1 DNS entries saved on this device.")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(address).fetchSemanticsNodes().isNotEmpty()
        }
        // The saved-resolver list is below the import controls; scroll it into view.
        composeRule.onNodeWithText(address).performScrollTo().assertIsDisplayed()

        // Importing the same endpoint again must not create a second library row.
        composeRule.onNodeWithText("DNS addresses")
            .performScrollTo()
            .performTextInput(address)
        composeRule.onNodeWithText("Import to local library")
            .performScrollTo()
            .performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(
                "No new entries. These DNS addresses are already saved."
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("1 DNS entries saved on this device.").assertIsDisplayed()

        // Recreate the real Activity: the saved record must still be loaded from Room.
        composeRule.activityRule.scenario.recreate()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("1 DNS entries saved on this device.")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(address).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(address).performScrollTo().assertIsDisplayed()
    }
}
