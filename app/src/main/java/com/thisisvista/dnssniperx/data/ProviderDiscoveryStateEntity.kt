package com.thisisvista.dnssniperx.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Durable cursor and cached routing snapshot for bounded provider-prefix discovery. */
@Entity(tableName = "provider_discovery_state")
data class ProviderDiscoveryStateEntity(
    @PrimaryKey val selectionKey: String,
    val providerIds: String,
    val nextOffset: Long,
    val totalCandidates: Long,
    val complete: Boolean,
    val updatedAt: Long,
    val prefixSnapshotJson: String? = null,
    val snapshotFetchedAt: Long? = null,
    val iranAllocationRanges: Int = 0
)
