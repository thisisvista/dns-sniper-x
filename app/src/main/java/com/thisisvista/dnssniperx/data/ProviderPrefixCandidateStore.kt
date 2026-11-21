package com.thisisvista.dnssniperx.data

/** Stores provider-prefix candidates with explicit ASN evidence and no DNS-health claim. */
class ProviderPrefixCandidateStore(private val dao: ResolverDao) {
    suspend fun persist(batch: ProviderPrefixDiscovery.Batch): Int {
        if (batch.candidates.isEmpty()) return 0
        val collectedAt = System.currentTimeMillis()
        val resolvers = batch.candidates.map { candidate ->
            ResolverEntity(
                address = candidate.address,
                port = 53,
                source = SOURCE_NAME,
                collectedAt = collectedAt,
                status = "NOT_TESTED",
                note = "Provider candidate AS${candidate.asn}, ${candidate.cidr}; DNS not tested"
            )
        }
        val inserted = dao.insertOrMergeSources(resolvers)

        val evidence = batch.candidates.map { candidate ->
            val sourceId = "${ProviderPrefixDiscovery.RIPESTAT_SOURCE_ID_PREFIX}${candidate.providerId}"
            ResolverEvidenceEntity(
                address = candidate.address,
                port = 53,
                sourceId = sourceId,
                providerId = candidate.providerId,
                collectedAt = collectedAt,
                evidenceType = EvidenceType.REGISTRY_PREFIX,
                ownershipStatus = OwnershipStatus.CANDIDATE,
                evidenceDetails = "country=IR;provider=${candidate.providerId};asn=AS${candidate.asn};cidr=${candidate.cidr};source=RIPEstat"
            )
        }
        batch.candidates.distinctBy { it.providerId to it.asn }.forEach { candidate ->
            dao.upsertResolverSource(
                ResolverSourceEntity(
                    sourceId = "${ProviderPrefixDiscovery.RIPESTAT_SOURCE_ID_PREFIX}${candidate.providerId}",
                    displayName = "RIPEstat announced prefixes (AS${candidate.asn})",
                    sourceType = "ASN_ROUTING_DISCOVERY",
                    sourceUrl = "https://stat.ripe.net/data/announced-prefixes/data.json?resource=AS${candidate.asn}",
                    providerId = candidate.providerId,
                    countryCode = "IR",
                    trustLevel = "ROUTING_DATA"
                )
            )
        }
        dao.insertResolverEvidenceRows(evidence)
        return inserted
    }

    companion object {
        const val SOURCE_NAME = "provider-asn-prefix-sample"
    }
}
