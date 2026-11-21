package com.thisisvista.dnssniperx.data

import org.json.JSONArray
import org.json.JSONObject

/** Orchestrates bounded discovery pages and serializes a reusable routing snapshot. */
class ProviderDiscoveryPipeline {
    data class Snapshot(
        val iranAllocationRanges: Int,
        val prefixes: List<ProviderPrefixDiscovery.ProviderPrefix>
    )

    data class Result(
        val iranAllocationRanges: Int,
        val providerPrefixes: Int,
        val batch: ProviderPrefixDiscovery.Batch
    )

    suspend fun fetchSnapshot(providerIds: Set<String>): Snapshot {
        val iranRanges = IranPrefixRegistryClient.fetchIranRanges()
        val prefixesByProvider = ProviderPrefixDiscovery.fetchProviderPrefixes(iranRanges, providerIds)
        val prefixes = prefixesByProvider.values.flatten()
        require(prefixes.size <= MAX_CACHED_PREFIXES) { "Provider prefix snapshot exceeds safety limit" }
        return Snapshot(iranAllocationRanges = iranRanges.size, prefixes = prefixes)
    }

    fun discoverBatch(
        snapshot: Snapshot,
        offset: Long = 0,
        batchSize: Int = IranPrefixDiscovery.DEFAULT_BATCH_SIZE
    ): Result {
        val batch = ProviderPrefixDiscovery.sampleBatch(snapshot.prefixes, offset = offset, batchSize = batchSize)
        return Result(snapshot.iranAllocationRanges, snapshot.prefixes.size, batch)
    }

    fun encodeSnapshot(snapshot: Snapshot): String = JSONArray().apply {
        snapshot.prefixes.forEach { prefix ->
            put(JSONObject()
                .put("providerId", prefix.providerId)
                .put("asn", prefix.asn)
                .put("cidr", prefix.cidr.asText()))
        }
    }.toString()

    fun decodeSnapshot(json: String, iranAllocationRanges: Int = 0): Snapshot {
        val array = JSONArray(json)
        require(array.length() <= MAX_CACHED_PREFIXES) { "Cached provider prefix snapshot exceeds limit" }
        val knownProviders = ProviderPrefixDiscovery.asnSeeds.mapTo(hashSetOf()) { it.providerId }
        val prefixes = ArrayList<ProviderPrefixDiscovery.ProviderPrefix>(array.length())
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            val providerId = item.getString("providerId")
            require(providerId in knownProviders)
            val asn = item.getInt("asn")
            val cidr = IranPrefixDiscovery.parseCidr(item.getString("cidr"))
                ?: error("Invalid CIDR in cached provider snapshot")
            prefixes += ProviderPrefixDiscovery.ProviderPrefix(providerId, asn, cidr)
        }
        return Snapshot(iranAllocationRanges, prefixes)
    }

    /** Compatibility helper for one-shot callers. */
    suspend fun discoverBatch(
        providerIds: Set<String> = ProviderPrefixDiscovery.asnSeeds.mapTo(linkedSetOf()) { it.providerId },
        samplesPerPrefix: Int = IranPrefixDiscovery.DEFAULT_SAMPLES_PER_PREFIX,
        offset: Long = 0,
        batchSize: Int = IranPrefixDiscovery.DEFAULT_BATCH_SIZE
    ): Result {
        require(samplesPerPrefix == IranPrefixDiscovery.DEFAULT_SAMPLES_PER_PREFIX) {
            "Custom samples-per-prefix is not supported by the persisted discovery cursor"
        }
        return discoverBatch(fetchSnapshot(providerIds), offset, batchSize)
    }

    companion object { const val MAX_CACHED_PREFIXES = 100_000 }
}
