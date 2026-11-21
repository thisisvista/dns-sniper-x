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
        require(batchSize in 1..IranPrefixDiscovery.MAX_BATCH_SIZE) { "Invalid provider discovery batch size" }
        val knownIds = ProviderPrefixDiscovery.asnSeeds.mapTo(hashSetOf()) { it.providerId }
        require(providerIds.all { it in knownIds }) { "Unknown provider selection" }

        val normalizedIds = providerIds.toSortedSet()
        val selectionKey = normalizedIds.joinToString(",")
        val previous = dao.getProviderDiscoveryState(selectionKey)
        if (previous?.complete == true) {
            return Summary(selectionKey, normalizedIds.size, previous.iranAllocationRanges,
                0, 0, 0, previous.nextOffset, previous.totalCandidates, true)
        }

        val now = System.currentTimeMillis()
        val cachedSnapshot = previous?.prefixSnapshotJson?.takeIf {
            previous.snapshotFetchedAt != null && now - previous.snapshotFetchedAt in 0..SNAPSHOT_TTL_MS
        }?.let { encoded ->
            runCatching { pipeline.decodeSnapshot(encoded, previous.iranAllocationRanges) }.getOrNull()
        }
        // Reuse the exact same ordered prefix snapshot for every resumed page.
        // RIPEstat and delegated stats are refreshed only after the 24-hour TTL.
        val snapshot = cachedSnapshot ?: pipeline.fetchSnapshot(normalizedIds)
        val result = pipeline.discoverBatch(snapshot, previous?.nextOffset ?: 0L, batchSize)
        val state = ProviderDiscoveryStateEntity(
            selectionKey = selectionKey,
            providerIds = normalizedIds.joinToString(","),
            nextOffset = result.batch.nextOffset,
            totalCandidates = result.batch.totalCandidates,
            complete = result.batch.complete,
            updatedAt = now,
            prefixSnapshotJson = pipeline.encodeSnapshot(snapshot),
            snapshotFetchedAt = if (cachedSnapshot != null) previous?.snapshotFetchedAt else now,
            iranAllocationRanges = snapshot.iranAllocationRanges
        )
        val inserted = ProviderPrefixCandidateStore(dao).persist(result.batch, state)
        return Summary(selectionKey, normalizedIds.size, result.iranAllocationRanges,
            result.providerPrefixes, result.batch.candidates.size, inserted,
            result.batch.nextOffset, result.batch.totalCandidates, result.batch.complete)
    }

    suspend fun reset(providerIds: Set<String>) {
        require(providerIds.isNotEmpty())
        dao.deleteProviderDiscoveryState(providerIds.toSortedSet().joinToString(","))
    }

    companion object { const val SNAPSHOT_TTL_MS = 24L * 60L * 60L * 1000L }
}
