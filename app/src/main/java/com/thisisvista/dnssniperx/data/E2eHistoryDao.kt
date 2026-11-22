package com.thisisvista.dnssniperx.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Read/write access to immutable, per-attempt end-to-end tunnel evidence. */
@Dao
interface E2eHistoryDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAttempt(attempt: E2eHistoryEntity): Long

    @Query("""
        SELECT * FROM e2e_history
        WHERE resolverAddress = :address AND resolverPort = :port
        ORDER BY testedAt DESC, id DESC
        LIMIT :limit
    """)
    fun observeResolverAttempts(address: String, port: Int, limit: Int = 100): Flow<List<E2eHistoryEntity>>

    @Query("""
        SELECT * FROM e2e_history
        WHERE resolverAddress = :address AND resolverPort = :port
        ORDER BY testedAt DESC, id DESC
        LIMIT :limit
    """)
    suspend fun getResolverAttempts(address: String, port: Int, limit: Int = 100): List<E2eHistoryEntity>

    @Query("""
        SELECT * FROM e2e_history
        WHERE networkKey = :networkKey
        ORDER BY testedAt DESC, id DESC
        LIMIT :limit
    """)
    fun observeNetworkAttempts(networkKey: String, limit: Int = 100): Flow<List<E2eHistoryEntity>>

    @Query("SELECT COUNT(*) FROM e2e_history")
    fun observeAttemptCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM e2e_history WHERE status = :status")
    fun observeStatusCount(status: String): Flow<Int>
}
