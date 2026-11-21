package com.thisisvista.dnssniperx.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "resolver_sources",
    indices = [Index(value = ["providerId"])]
)
data class ResolverSourceEntity(
    @PrimaryKey val sourceId: String,
    val displayName: String,
    val sourceType: String,
    val sourceUrl: String? = null,
    val providerId: String? = null,
    val countryCode: String = "ZZ",
    val trustLevel: String = "COMMUNITY",
    val enabled: Boolean = true,
    val lastFetchedAt: Long? = null,
    val lastError: String? = null
)
