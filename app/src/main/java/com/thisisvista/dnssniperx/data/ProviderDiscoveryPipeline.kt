package com.thisisvista.dnssniperx.data

/** Orchestrates one bounded discovery page; it never scans the complete address space. */
class ProviderDiscoveryPipeline {
    data class Result(
        val iranAllocationRanges: Int,
        val providerPrefixes: Int,
        val batch: ProviderPrefixDiscovery.Batch
    )

    suspend fun discoverBatch(
        providerIds: Set<String> = ProviderPrefixDiscovery.asnSeeds.mapTo(linkedSetOf()) { it.providerId },
        samplesPerPrefix: Int = IranPrefixDiscovery.DEFAULT_SAMPLES_PER_PREFIX,
        offset: Long = 0,
        batchSize: Int = IranPrefixDiscovery.DEFAULT_BATCH_SIZE
    ): Result {
        val iranRanges = IranPrefixRegistryClient.fetchIranRanges()
        val prefixesByProvider = ProviderPrefixDiscovery.fetchProviderPrefixes(iranRanges, providerIds)
        val prefixes = prefixesByProvider.values.flatten()
        val batch = ProviderPrefixDiscovery.sampleBatch(
            prefixes = prefixes,
            samplesPerPrefix = samplesPerPrefix,
            offset = offset,
            batchSize = batchSize
        )
        return Result(
            iranAllocationRanges = iranRanges.size,
            providerPrefixes = prefixes.size,
            batch = batch
        )
    }
}
