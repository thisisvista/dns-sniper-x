package com.thisisvista.dnssniperx.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProviderDiscoveryPipelineTest {
    private val pipeline = ProviderDiscoveryPipeline()

    @Test fun cachedSnapshotRoundTripsProviderAsnAndPrefixOrder() {
        val snapshot = ProviderDiscoveryPipeline.Snapshot(
            iranAllocationRanges = 27,
            prefixes = listOf(
                ProviderPrefixDiscovery.ProviderPrefix(
                    "irancell", 44244, IranPrefixDiscovery.parseCidr("5.160.0.0/24")!!
                ),
                ProviderPrefixDiscovery.ProviderPrefix(
                    "mci", 197207, IranPrefixDiscovery.parseCidr("5.161.0.0/24")!!
                )
            )
        )

        val decoded = pipeline.decodeSnapshot(pipeline.encodeSnapshot(snapshot), 27)

        assertEquals(27, decoded.iranAllocationRanges)
        assertEquals(snapshot.prefixes, decoded.prefixes)
    }

    @Test fun cachedSnapshotRejectsUnknownProviderAndInvalidCidr() {
        assertThrows(IllegalArgumentException::class.java) {
            pipeline.decodeSnapshot("""[{"providerId":"unknown","asn":1,"cidr":"5.160.0.0/24"}]""")
        }
        assertThrows(IllegalStateException::class.java) {
            pipeline.decodeSnapshot("""[{"providerId":"irancell","asn":44244,"cidr":"not-a-cidr"}]""")
        }
    }

    @Test fun cachedSnapshotResumesSameDeterministicCandidatePage() {
        val snapshot = ProviderDiscoveryPipeline.Snapshot(
            iranAllocationRanges = 1,
            prefixes = listOf(
                ProviderPrefixDiscovery.ProviderPrefix(
                    "irancell", 44244, IranPrefixDiscovery.parseCidr("5.160.0.0/24")!!
                )
            )
        )
        val restored = pipeline.decodeSnapshot(pipeline.encodeSnapshot(snapshot), 1)

        val first = pipeline.discoverBatch(restored, offset = 0, batchSize = 3)
        val resumed = pipeline.discoverBatch(restored, offset = first.batch.nextOffset, batchSize = 3)
        val direct = pipeline.discoverBatch(snapshot, offset = 3, batchSize = 3)

        assertEquals(direct.batch.candidates, resumed.batch.candidates)
        assertEquals(first.batch.totalCandidates, resumed.batch.totalCandidates)
    }
}
