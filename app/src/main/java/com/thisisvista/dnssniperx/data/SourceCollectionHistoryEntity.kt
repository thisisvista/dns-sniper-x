package com.thisisvista.dnssniperx.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "source_collection_history",
    indices = [Index(value = ["retrievedAt"])]
)
data class SourceCollectionHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: String,
    val sourceName: String,
    val sourceUrl: String,
    val retrievedAt: Long,
    val foundCount: Int,
    val error: String?,
    val termsNote: String? = null
)
