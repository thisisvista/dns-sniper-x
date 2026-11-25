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
    fun manualImportDeduplicatesAndPersistsToRoom() {
        // Manual import now lives on the dedicated Collect & Scan tab.
        composeRule.onNodeWithText("Collect & Scan").performClick()
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

        // Exercise duplicate import while the import controls are still in view.
        // Avoid scrolling down to the resolver list and then back up while the
        // soft keyboard is opening on slower CI emulators.
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

        // Close the soft keyboard before changing tabs. On slower hosted emulators,
        // the IME can cover the tab row and leave the Activity backgrounded during
        // the subsequent scroll/semantics wait.
        composeRule.pressBack()

        // Saved resolver browsing now lives on the Results tab, not Collect & Scan.
        // Room above verifies persistence; now verify the user-facing list projection.
        composeRule.onNodeWithText("Results").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithText(endpoint).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(endpoint).performScrollTo().assertIsDisplayed()

        // Room is the durable source of truth. Activity recreation is covered
        // separately by database/repository instrumentation; recreating a live
        // Compose owner inside this UI smoke test triggers a platform renderer
        // race on the hosted API 34 emulator.
    }
}
