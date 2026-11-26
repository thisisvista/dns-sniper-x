package com.thisisvista.dnssniperx

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
        // Manual import now lives on the dedicated Collect tab.
        composeRule.onNodeWithText("Collect").performClick()
        val address = "198.51.100.77"

        composeRule.onNodeWithText("DNS addresses")
            .performScrollTo()
            .performTextInput(address)
        composeRule.onNodeWithText("Import to local library")
            .performScrollTo()
            .performClick()

        // Confirm the real UI callback completed successfully.
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithText("Saved 1 DNS entries successfully.")
                .fetchSemanticsNodes().isNotEmpty()
        }
        runBlocking {
            withTimeout(30_000) {
                dao.observeCount().first { it == 1 }
            }
        }

        // Importing the same address again must not create a duplicate.
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

            // Verify the saved record through the same paged query used by Results.
            val saved = dao.getResolverPage(
                statusFilter = "ALL",
                searchTerm = address,
                networkType = "ALL",
                providerLabel = "ALL",
                fastestFirst = false,
                limit = 10,
                offset = 0
            )
            assertEquals(1, saved.size)
            assertEquals(address, saved.single().address)
        }
    }
}
