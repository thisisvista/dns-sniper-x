package com.thisisvista.dnssniperx.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "resolver_evidence",
    indices = [
        Index(value = ["address", "port", "sourceId"], unique = true),
        Index(value = ["providerId"]),
        Index(value = ["ownershipStatus"])
    ]
)
data class ResolverEvidenceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val address: String,
    val port: Int = 53,
    val sourceId: String,
    val providerId: String? = null,
    val collectedAt: Long = System.currentTimeMillis(),
    val evidenceType: String = EvidenceType.SOURCE_LIST,
    val ownershipStatus: String = OwnershipStatus.UNVERIFIED,
    val evidenceDetails: String? = null
)

object EvidenceType {
    const val SOURCE_LIST = "SOURCE_LIST"
    const val ACTIVE_NETWORK = "ACTIVE_NETWORK"
    const val SIM_OPERATOR_MATCH = "SIM_OPERATOR_MATCH"
    const val PROVIDER_DOCUMENTATION = "PROVIDER_DOCUMENTATION"
    const val REGISTRY_PREFIX = "REGISTRY_PREFIX"
    const val USER_LABEL = "USER_LABEL"
}

object OwnershipStatus {
    const val UNVERIFIED = "UNVERIFIED"
    const val CANDIDATE = "CANDIDATE"
    const val VERIFIED = "VERIFIED"
    const val REJECTED = "REJECTED"
}
