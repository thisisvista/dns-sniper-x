package com.thisisvista.dnssniperx.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Provider ASN seeds are intentionally narrow primary-origin seeds, not a complete
 * ISP ownership database. RIPEstat supplies current announced prefixes; prefixes are
 * intersected with RIPE's IR allocations before they become provider candidates.
 */
object ProviderPrefixDiscovery {
    private const val RIPESTAT_HOST = "stat.ripe.net"
    private const val CONNECT_TIMEOUT_MS = 12_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val MAX_RESPONSE_CHARS = 5_000_000

    const val RIPESTAT_SOURCE_ID_PREFIX = "ripestat-asn-prefix-"

    data class AsnSeed(
        val providerId: String,
        val displayName: String,
        val asn: Int,
        val reference: String = "https://github.com/Kiya6955/IR-ISP-Blocker"
    )

    data class ProviderPrefix(val providerId: String, val asn: Int, val cidr: IranPrefixDiscovery.CidrBlock)

    data class Candidate(
        val address: String,
        val cidr: String,
        val providerId: String,
        val asn: Int,
        val ownershipStatus: String = OwnershipStatus.CANDIDATE
    )

    data class Batch(val candidates: List<Candidate>, val nextOffset: Long, val totalCandidates: Long, val complete: Boolean)

    /** Primary ASN seeds; update only after checking the current holder and routing data. */
    val asnSeeds = listOf(
        AsnSeed("mci", "همراه اول", 197207),
        AsnSeed("irancell", "ایرانسل", 44244),
        AsnSeed("rightel", "رایتل", 57218),
        AsnSeed("tci", "مخابرات ایران", 58224),
        AsnSeed("shatel", "شاتل", 31549),
        AsnSeed("asiatech", "آسیاتک", 43754),
        AsnSeed("pars-online", "پارس‌آنلاین", 16322),
        AsnSeed("mobinnet", "مبین‌نت", 50810),
        AsnSeed("hiweb", "های‌وب", 56402),
        AsnSeed("pishgaman", "پیشگامان", 49100),
        AsnSeed("respina", "رسپینا", 42337),
        AsnSeed("afranet", "افرانت", 25184)
    )

    suspend fun fetchProviderPrefixes(
        iranRanges: List<IranPrefixDiscovery.Ipv4Range>,
        providerIds: Set<String> = asnSeeds.mapTo(linkedSetOf()) { it.providerId }
    ): Map<String, List<ProviderPrefix>> = withContext(Dispatchers.IO) {
        require(providerIds.all { id -> asnSeeds.any { it.providerId == id } }) { "Unknown provider ID" }
        val result = linkedMapOf<String, List<ProviderPrefix>>()
        asnSeeds.filter { it.providerId in providerIds }.forEach { seed ->
            val url = URL("https://$RIPESTAT_HOST/data/announced-prefixes/data.json?resource=AS${seed.asn}&min_peers_seeing=10")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "DNS-SniperX/0.4 Android")
                setRequestProperty("Accept", "application/json")
            }
            try {
                require(url.protocol == "https" && url.host == RIPESTAT_HOST && url.port in listOf(-1, 443))
                check(connection.responseCode == HttpURLConnection.HTTP_OK) { "RIPEstat failed for AS${seed.asn}" }
                val declared = connection.contentLengthLong
                require(declared <= MAX_RESPONSE_CHARS) { "RIPEstat response exceeded size limit" }
                val body = StringBuilder()
                BufferedReader(InputStreamReader(connection.inputStream, StandardCharsets.UTF_8)).use { reader ->
                    val buffer = CharArray(8_192)
                    while (true) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        require(body.length + count <= MAX_RESPONSE_CHARS) { "RIPEstat response exceeded size limit" }
                        body.append(buffer, 0, count)
                    }
                }
                val data = JSONObject(body.toString()).optJSONObject("data")
                    ?: error("RIPEstat response did not contain data")
                val announced = data.optJSONArray("prefixes")
                    ?: error("RIPEstat response did not contain announced prefixes")
                val mapped = LinkedHashMap<String, ProviderPrefix>()
                for (index in 0 until announced.length()) {
                    val prefixText = announced.optJSONObject(index)?.optString("prefix").orEmpty()
                    if (prefixText.contains(':')) continue
                    val prefix = IranPrefixDiscovery.parseCidr(prefixText) ?: continue
                    intersectWithIran(prefix, iranRanges).forEach { block ->
                        mapped["${block.asText()}"] = ProviderPrefix(seed.providerId, seed.asn, block)
                    }
                    require(mapped.size <= 20_000) { "Provider prefix count exceeded safety limit" }
                }
                result[seed.providerId] = mapped.values.toList()
            } finally {
                connection.disconnect()
            }
        }
        result
    }

    /** Intersect BGP-announced CIDRs with current IR delegated IPv4 allocations. */
    fun intersectWithIran(
        prefix: IranPrefixDiscovery.CidrBlock,
        iranRanges: List<IranPrefixDiscovery.Ipv4Range>
    ): List<IranPrefixDiscovery.CidrBlock> {
        val intersections = mutableListOf<IranPrefixDiscovery.CidrBlock>()
        iranRanges.forEach { range ->
            val start = maxOf(prefix.network, range.start)
            val end = minOf(prefix.endInclusive, range.endInclusive)
            if (start <= end) {
                intersections += IranPrefixDiscovery.toCidrs(IranPrefixDiscovery.Ipv4Range(start, end))
            }
        }
        return intersections.distinctBy { it.asText() }.sortedBy { it.network }
    }

    /** Stable per-prefix sample order makes the offset safe to persist and resume. */
    fun sampleBatch(
        prefixes: List<ProviderPrefix>,
        samplesPerPrefix: Int = IranPrefixDiscovery.DEFAULT_SAMPLES_PER_PREFIX,
        offset: Long = 0,
        batchSize: Int = IranPrefixDiscovery.DEFAULT_BATCH_SIZE
    ): Batch {
        require(samplesPerPrefix in 1..IranPrefixDiscovery.MAX_SAMPLES_PER_PREFIX)
        require(offset >= 0)
        require(batchSize in 1..IranPrefixDiscovery.MAX_BATCH_SIZE)
        val eligible = prefixes.distinctBy { "${it.providerId}:${it.asn}:${it.cidr.asText()}" }
            .filter { it.cidr.prefixLength <= 30 && isPublic(it.cidr) }
            .sortedWith(compareBy<ProviderPrefix> { it.providerId }.thenBy { it.asn }.thenBy { it.cidr.network })
        val total = eligible.size.toLong() * samplesPerPrefix
        if (offset >= total) return Batch(emptyList(), total, total, true)
        val end = minOf(total, offset + batchSize)
        val candidates = mutableListOf<Candidate>()
        var cursor = 0L
        for (prefix in eligible) {
            for (sampleIndex in 0 until samplesPerPrefix) {
                if (cursor >= offset && cursor < end) {
                    val value = sampleAddress(prefix.cidr, sampleIndex, samplesPerPrefix)
                    candidates += Candidate(
                        IranPrefixDiscovery.toIpv4(value),
                        prefix.cidr.asText(),
                        prefix.providerId,
                        prefix.asn
                    )
                }
                cursor++
                if (cursor >= end) break
            }
            if (cursor >= end) break
        }
        return Batch(candidates.distinctBy { "${it.providerId}:${it.address}" }, end, total, end >= total)
    }

    private fun sampleAddress(prefix: IranPrefixDiscovery.CidrBlock, index: Int, count: Int): Long {
        val usable = prefix.size - 2
        val rotation = (prefix.network xor (prefix.network ushr 13) xor prefix.prefixLength.toLong()) % usable
        return prefix.network + 1 + ((index.toLong() * usable) / count + rotation) % usable
    }

    private fun isPublic(prefix: IranPrefixDiscovery.CidrBlock): Boolean {
        val start = prefix.network
        val end = prefix.endInclusive
        val first = (start ushr 24).toInt()
        val second = ((start ushr 16) and 255).toInt()
        val third = ((start ushr 8) and 255).toInt()
        if (first == 0 || first == 10 || first == 127 || first >= 224) return false
        if (first == 100 && second in 64..127) return false
        if (first == 169 && second == 254) return false
        if (first == 172 && second in 16..31) return false
        if (first == 192 && (second == 0 || second == 168)) return false
        if (first == 192 && second == 88 && third == 99) return false
        if (first == 198 && (second in 18..19 || second == 51)) return false
        if (first == 203 && second == 0 && third == 113) return false
        return (end ushr 24).toInt() == first
    }
}
