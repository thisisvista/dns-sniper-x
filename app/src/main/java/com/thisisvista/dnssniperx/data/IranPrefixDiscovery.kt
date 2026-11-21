package com.thisisvista.dnssniperx.data

/** Bounded, resumable IPv4 candidate discovery. Prefix evidence never proves DNS service or retail ISP ownership. */
object IranPrefixDiscovery {
    const val DEFAULT_SAMPLES_PER_PREFIX = 10
    const val MAX_SAMPLES_PER_PREFIX = 50
    const val DEFAULT_BATCH_SIZE = 500
    const val MAX_BATCH_SIZE = 2_000

    data class Ipv4Range(val start: Long, val endInclusive: Long) {
        init { require(start in 0..MAX_IPV4 && endInclusive in start..MAX_IPV4) }
        val size: Long get() = endInclusive - start + 1
    }

    data class CidrBlock(val network: Long, val prefixLength: Int) {
        init { require(network in 0..MAX_IPV4 && prefixLength in 0..32) }
        val size: Long get() = 1L shl (32 - prefixLength)
        val endInclusive: Long get() = network + size - 1
        fun asText(): String = "${toIpv4(network)}/$prefixLength"
    }

    data class Candidate(
        val address: String,
        val cidr: String,
        val countryCode: String = "IR",
        val providerId: String? = null,
        val ownershipStatus: String = OwnershipStatus.CANDIDATE,
        val evidenceType: String = EvidenceType.REGISTRY_PREFIX
    )

    data class Batch(
        val candidates: List<Candidate>,
        val nextOffset: Long,
        val totalCandidates: Long,
        val complete: Boolean
    )

    /** RIPE delegated stats: registry|cc|type|start|value|date|status... */
    fun parseDelegatedIpv4(text: String, countryCode: String = "IR"): List<Ipv4Range> =
        text.lineSequence().mapNotNull { parseDelegatedIpv4Line(it, countryCode) }
            .distinct().sortedBy { it.start }.toList()

    fun parseDelegatedIpv4Line(line: String, countryCode: String = "IR"): Ipv4Range? {
        val normalized = line.trim()
        if (normalized.isEmpty() || normalized.startsWith("#") ||
            normalized.startsWith("2|") || normalized.startsWith("registry|")) return null
        val fields = normalized.split('|')
        if (fields.size < 7) return null
        if (!fields[1].equals(countryCode, ignoreCase = true) ||
            !fields[2].equals("ipv4", ignoreCase = true) ||
            fields[6].lowercase() !in setOf("allocated", "assigned")) return null
        val start = parseIpv4(fields[3]) ?: return null
        val count = fields[4].toLongOrNull()?.takeIf { it > 0 } ?: return null
        if (count - 1 > MAX_IPV4 - start) return null
        return Ipv4Range(start, start + count - 1)
    }

    /** Convert arbitrary inclusive ranges into their exact minimal CIDR cover. */
    fun toCidrs(range: Ipv4Range): List<CidrBlock> {
        val result = mutableListOf<CidrBlock>()
        var current = range.start
        while (current <= range.endInclusive) {
            val alignmentPrefix = if (current == 0L) 0 else
                32 - java.lang.Long.numberOfTrailingZeros(current).coerceAtMost(32)
            val remaining = range.endInclusive - current + 1
            val floorLog2 = 63 - java.lang.Long.numberOfLeadingZeros(remaining)
            val sizePrefix = 32 - floorLog2
            val block = CidrBlock(current, maxOf(alignmentPrefix, sizePrefix))
            result += block
            current += block.size
        }
        return result
    }

    /** Deterministic sampling supports offset-based resume without enumerating address space. */
    fun sampleBatch(
        prefixes: List<CidrBlock>,
        samplesPerPrefix: Int = DEFAULT_SAMPLES_PER_PREFIX,
        offset: Long = 0,
        batchSize: Int = DEFAULT_BATCH_SIZE,
        providerId: String? = null
    ): Batch {
        require(samplesPerPrefix in 1..MAX_SAMPLES_PER_PREFIX)
        require(offset >= 0)
        require(batchSize in 1..MAX_BATCH_SIZE)
        val eligible = prefixes.filter { it.prefixLength <= 30 && isPublicRange(it.network, it.endInclusive) }
        val total = eligible.sumOf { samplesPerPrefix.toLong() }
        if (offset >= total) return Batch(emptyList(), total, total, true)

        val end = minOf(total, offset + batchSize)
        val rows = ArrayList<Candidate>((end - offset).toInt())
        var globalIndex = 0L
        for (prefix in eligible) {
            for (sampleIndex in 0 until samplesPerPrefix) {
                if (globalIndex >= offset && globalIndex < end) {
                    val addressValue = sampledAddress(prefix, sampleIndex, samplesPerPrefix)
                    if (isPublicRange(addressValue, addressValue)) {
                        rows += Candidate(toIpv4(addressValue), prefix.asText(), providerId = providerId)
                    }
                }
                globalIndex++
                if (globalIndex >= end) break
            }
            if (globalIndex >= end) break
        }
        return Batch(rows.distinctBy { it.address }, end, total, end >= total)
    }

    fun parseCidr(value: String): CidrBlock? {
        val parts = value.trim().split('/')
        if (parts.size != 2) return null
        val address = parseIpv4(parts[0]) ?: return null
        val prefix = parts[1].toIntOrNull()?.takeIf { it in 0..32 } ?: return null
        val mask = if (prefix == 0) 0L else (MAX_IPV4 shl (32 - prefix)) and MAX_IPV4
        return CidrBlock(address and mask, prefix)
    }

    fun parseIpv4(value: String): Long? {
        val parts = value.trim().split('.')
        if (parts.size != 4) return null
        var result = 0L
        for (part in parts) {
            if (part.isEmpty() || (part.length > 1 && part.startsWith('0'))) return null
            val octet = part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
            result = (result shl 8) or octet.toLong()
        }
        return result
    }

    fun toIpv4(value: Long): String {
        require(value in 0..MAX_IPV4)
        return listOf(24, 16, 8, 0).joinToString(".") { shift ->
            ((value ushr shift) and 255).toString()
        }
    }

    private fun sampledAddress(prefix: CidrBlock, index: Int, count: Int): Long {
        val usable = prefix.size - 2
        if (usable <= 1) return prefix.network + 1
        val rotation = (prefix.network xor (prefix.network ushr 13) xor prefix.prefixLength.toLong()) % usable
        val position = ((index.toLong() * usable) / count + rotation) % usable
        return prefix.network + 1 + position
    }

    private fun isPublicRange(start: Long, end: Long): Boolean {
        val first = (start ushr 24).toInt()
        val second = ((start ushr 16) and 255).toInt()
        val third = ((start ushr 8) and 255).toInt()
        if (first == 0 || first == 10 || first == 127 || first >= 224) return false
        if (first == 100 && second in 64..127) return false
        if (first == 169 && second == 254) return false
        if (first == 172 && second in 16..31) return false
        if (first == 192 && (second == 0 || second == 168)) return false
        if (first == 192 && second == 88 && third == 99) return false
        if (first == 198 && second in 18..19) return false
        if (first == 198 && second == 51) return false
        if (first == 203 && second == 0 && third == 113) return false
        if ((end ushr 24).toInt() != first) return false
        return true
    }

    private const val MAX_IPV4 = 0xFFFF_FFFFL
}
