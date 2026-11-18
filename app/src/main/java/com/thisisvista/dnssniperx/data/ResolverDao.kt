package com.thisisvista.dnssniperx.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ResolverDao {
    @Query("SELECT * FROM resolvers ORDER BY collectedAt DESC, address ASC")
    fun observeAll(): Flow<List<ResolverEntity>>

    @Query("SELECT COUNT(*) FROM resolvers")
    fun observeCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(resolvers: List<ResolverEntity>): List<Long>

    @Query("DELETE FROM resolvers WHERE id = :id")
    suspend fun deleteById(id: Long)
}
