package com.thisisvista.dnssniperx.data

/** Runs one bounded provider discovery page and commits candidates plus resume state. */
class ProviderDiscoveryCoordinator(
    private val dao: ResolverDao,
    private val pipeline: ProviderDiscoveryPipeline = ProviderDiscoveryPipeline()
) {
    data class Summary(
        val selectionKey: String,
        val providerCount: Int,
        val iranAllocationRanges: Int,
        val providerPrefixes: Int,
        val discoveredCandidates: Int,
        val newlySaved: Int,
        val nextOffset: Long,
        val totalCandidates: Long,
        val complete: Boolean
    )

    suspend fun discoverNextBatch(
        providerIds: Set<String>,
        batchSize: Int = IranPrefixDiscovery.DEFAULT_BATCH_SIZE
    ): Summary {
        require(providerIds.isNotEmpty()) { "Select at least one provider" }
        val knownIds = ProviderPrefixDiscovery.asnSeeds.mapTo(hashSetOf()) { it.providerId }
        require(providerIds.all { it in knownIds }) { "Unknown provider selection" }

        val normalizedIds = providerIds.toSortedSet()
        val selectionKey = normalizedIds.joinToString(",")
        val previous = dao.getProviderDiscoveryState(selectionKey)
        if (previous?.complete == true) {
            return Summary(
                selectionKey = selectionKey,
                providerCount = normalizedIds.size,
                iranAllocationRanges = 0,
                providerPrefixes = 0,
                discoveredCandidates = 0,
                newlySaved = 0,
                nextOffset = previous.nextOffset,
                totalCandidates = previous.totalCandidates,
                complete = true
            )
        }

        // Cursor advances only in the same Room transaction that saves the batch.
        // If discovery or persistence fails, the previous cursor remains resumable.
        val result = pipeline.discoverBatch(
            providerIds = normalizedIds,
            offset = previous?.nextOffset ?: 0L,
            batchSize = batchSize
        )
        val state = ProviderDiscoveryStateEntity(
            selectionKey = selectionKey,
            providerIds = normalizedIds.joinToString(","),
            nextOffset = result.batch.nextOffset,
            totalCandidates = result.batch.totalCandidates,
            complete = result.batch.complete,
            updatedAt = System.currentTimeMillis()
        )
        val inserted = ProviderPrefixCandidateStore(dao).persist(result.batch, state)
        return Summary(
            selectionKey = selectionKey,
            providerCount = normalizedIds.size,
            iranAllocationRanges = result.iranAllocationRanges,
            providerPrefixes = result.providerPrefixes,
            discoveredCandidates = result.batch.candidates.size,
            newlySaved = inserted,
            nextOffset = result.batch.nextOffset,
            totalCandidates = result.batch.totalCandidates,
            complete = result.batch.complete
        )
    }

    suspend fun reset(providerIds: Set<String>) {
        require(providerIds.isNotEmpty())
        val key = providerIds.toSortedSet().joinToString(",")
        dao.deleteProviderDiscoveryState(key)
    }
}
