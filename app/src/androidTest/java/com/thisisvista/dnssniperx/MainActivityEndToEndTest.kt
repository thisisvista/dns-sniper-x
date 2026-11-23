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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

    private val dao by lazy {
        ResolverDatabase.getInstance(
            InstrumentationRegistry.getInstrumentation().targetContext
        ).resolverDao()
    }

    @Before
    fun clearLocalDatabase() {
        ResolverDatabase.getInstance(
            InstrumentationRegistry.getInstrumentation().targetContext
        ).clearAllTables()
    }

    @Test
    fun manualImportDeduplicatesAndSurvivesActivityRecreation() {
        val address = "198.51.100.77"
        val endpoint = "$address:53"

        composeRule.onNodeWithText("DNS addresses")
            .performScrollTo()
            .performTextInput(address)
        composeRule.onNodeWithText("Import to local library")
            .performScrollTo()
            .performClick()

        // First prove that the UI action reached the import callback. This gives
        // a useful failure if parsing/insertion fails instead of timing out on a
        // downstream dashboard counter.
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithText("Saved 1 DNS entries successfully.")
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Independently verify the durable Room state before checking its UI projection.
        runBlocking {
            withTimeout(30_000) {
                dao.observeCount().first { it == 1 }
            }
        }
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithText("1 DNS entries saved on this device.")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithText(endpoint).fetchSemanticsNodes().isNotEmpty()
        }
        // The saved-resolver list is below the import controls; scroll it into view.
        composeRule.onNodeWithText(endpoint).performScrollTo().assertIsDisplayed()

        // Importing the same endpoint again must not create a second library row.
        composeRule.onNodeWithText("DNS addresses")
            .performScrollTo()
            .performTextInput(address)
        composeRule.onNodeWithText("Import to local library")
            .performScrollTo()
            .performClick()

        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithText(
                "No new entries. These DNS addresses are already saved."
            ).fetchSemanticsNodes().isNotEmpty()
        }
        runBlocking {
            withTimeout(30_000) {
                dao.observeCount().first { it == 1 }
            }
        }
        composeRule.onNodeWithText("1 DNS entries saved on this device.")
            .performScrollTo()
            .assertIsDisplayed()

        // Recreate the real Activity: first prove Room still owns the row, then
        // wait for the recreated Activity's Flow-backed dashboard to render it.
        composeRule.activityRule.scenario.recreate()
        runBlocking {
            withTimeout(30_000) {
                dao.observeCount().first { it == 1 }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Local DNS library").performScrollTo().assertIsDisplayed()
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithText(
                "1 DNS entries saved on this device.", useUnmergedTree = true
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithText(endpoint, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(endpoint, useUnmergedTree = true)
            .performScrollTo().assertIsDisplayed()
    }
}
