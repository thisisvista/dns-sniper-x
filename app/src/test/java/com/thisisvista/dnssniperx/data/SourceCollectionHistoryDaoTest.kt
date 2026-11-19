package com.thisisvista.dnssniperx.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SourceCollectionHistoryDaoTest {
    private lateinit var database: ResolverDatabase
    private lateinit var dao: ResolverDao

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ResolverDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.resolverDao()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun saveHistoryRetainsOnlyNewestOneHundredRows() = runBlocking {
        val entries = (1L..125L).map { timestamp ->
            SourceCollectionHistoryEntity(
                sourceId = "source-$timestamp",
                sourceName = "Source $timestamp",
                sourceUrl = "https://example.com/$timestamp.txt",
                retrievedAt = timestamp,
                foundCount = timestamp.toInt(),
                error = null
            )
        }

        dao.saveSourceCollectionHistory(entries)

        val retained = dao.observeRecentSourceCollectionHistory().first()
        assertEquals(100, retained.size)
        assertEquals(125L, retained.first().retrievedAt)
        assertEquals(26L, retained.last().retrievedAt)
        assertTrue(retained.all { it.retrievedAt in 26L..125L })
    }

    @Test
    fun repeatedSavesContinuePruningOldRows() = runBlocking {
        dao.saveSourceCollectionHistory((1L..100L).map { timestamp ->
            SourceCollectionHistoryEntity(
                sourceId = "source-$timestamp",
                sourceName = "Source $timestamp",
                sourceUrl = "https://example.com/$timestamp.txt",
                retrievedAt = timestamp,
                foundCount = 1,
                error = null
            )
        })
        dao.saveSourceCollectionHistory((101L..110L).map { timestamp ->
            SourceCollectionHistoryEntity(
                sourceId = "source-$timestamp",
                sourceName = "Source $timestamp",
                sourceUrl = "https://example.com/$timestamp.txt",
                retrievedAt = timestamp,
                foundCount = 1,
                error = null
            )
        })

        val retained = dao.observeRecentSourceCollectionHistory().first()
        assertEquals(100, retained.size)
        assertEquals(110L, retained.first().retrievedAt)
        assertEquals(11L, retained.last().retrievedAt)
    }
}
