package com.thisisvista.dnssniperx.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Durable cursor for a bounded provider-prefix discovery selection. */
@Entity(tableName = "provider_discovery_state")
data class ProviderDiscoveryStateEntity(
    @PrimaryKey val selectionKey: String,
    val providerIds: String,
    val nextOffset: Long,
    val totalCandidates: Long,
    val complete: Boolean,
    val updatedAt: Long
)
