package com.thisisvista.dnssniperx.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "provider_catalog")
data class ProviderEntity(
    @PrimaryKey val providerId: String,
    val displayName: String,
    val providerType: String,
    val countryCode: String = "IR",
    /** Semicolon-separated aliases used for matching operator/network names. */
    val aliases: String = "",
    val enabled: Boolean = true
)
