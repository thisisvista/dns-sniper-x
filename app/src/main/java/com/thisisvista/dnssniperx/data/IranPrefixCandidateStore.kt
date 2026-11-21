package com.thisisvista.dnssniperx.data

/** Persists bounded prefix samples without confusing registry evidence with DNS health. */
class IranPrefixCandidateStore(private val dao: ResolverDao) {
    suspend fun persist(batch: IranPrefixDiscovery.Batch): Int {
        if (batch.candidates.isEmpty()) return 0
        dao.upsertResolverSource(
            ResolverSourceEntity(
                sourceId = IranPrefixRegistryClient.SOURCE_ID,
                displayName = "RIPE NCC Iran IPv4 delegated prefixes",
                sourceType = "REGISTRY_DISCOVERY",
                sourceUrl = IranPrefixRegistryClient.SOURCE_URL,
                countryCode = "IR",
                trustLevel = "REGISTRY"
            )
        )
        val collectedAt = System.currentTimeMillis()
        val resolvers = batch.candidates.map { candidate ->
            ResolverEntity(
                address = candidate.address,
                port = 53,
                source = SOURCE_NAME,
                collectedAt = collectedAt,
                status = "NOT_TESTED",
                note = "Candidate from ${candidate.cidr}; not yet confirmed as a DNS resolver"
            )
        }
        val inserted = dao.insertOrMergeSources(resolvers)
        batch.candidates.forEach { candidate ->
            dao.insertResolverEvidence(
                ResolverEvidenceEntity(
                    address = candidate.address,
                    port = 53,
                    sourceId = IranPrefixRegistryClient.SOURCE_ID,
                    providerId = candidate.providerId,
                    collectedAt = collectedAt,
                    evidenceType = EvidenceType.REGISTRY_PREFIX,
                    ownershipStatus = OwnershipStatus.CANDIDATE,
                    evidenceDetails = "country=IR;cidr=${candidate.cidr};source=RIPE delegated stats"
                )
            )
        }
        return inserted
    }

    companion object {
        const val SOURCE_NAME = "iran-ripe-prefix-sample"
    }
}
