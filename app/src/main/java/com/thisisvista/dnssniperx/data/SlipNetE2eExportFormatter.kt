package com.thisisvista.dnssniperx.data

/**
 * Two exports serve different SlipNet workflows:
 * - resolverListText is a plain IP-per-line file for SlipNet's DNS scanner import.
 * - reportJson preserves the exact engine/profile and FULL E2E proof for audit/reuse.
 *
 * Only verified FULL_PASS rows with a successful HTTP response are eligible.
 */
data class SlipNetE2eExportResult(
    val resolverListText: String,
    val reportJson: String,
    val exportedCount: Int,
    val skippedCount: Int
)

object SlipNetE2eExportFormatter {
    fun format(rows: List<SlipNetE2eExportRow>): SlipNetE2eExportResult {
        val eligible = rows.filter { row ->
            row.resolverPort == 53 &&
                row.verificationMode == "FULL" &&
                row.status == "FULL_PASS" &&
                row.httpStatusCode?.let { it in 200..399 } == true &&
                row.tunnelProtocol in setOf("DNSTT", "VAYDNS") &&
                SlipNetExportFormatter.isIpv4Literal(row.resolverAddress)
        }.groupBy { "${it.resolverAddress}:${it.resolverPort}:${it.tunnelProtocol}:${it.profileId}" }
            .values
            .mapNotNull { attempts -> attempts.maxWithOrNull(compareBy<SlipNetE2eExportRow> { it.testedAt }.thenBy { it.profileId }) }
            .sortedWith(compareBy<SlipNetE2eExportRow> { it.resolverAddress }.thenBy { it.tunnelProtocol })

        val resolverList = eligible.map { it.resolverAddress }.distinct()
            .joinToString("\n")
            .let { if (it.isEmpty()) "" else "$it\n" }

        val report = buildString {
            append("{\n")
            append("  \"schema\": \"dns-sniperx-slipnet-e2e-v1\",\n")
            append("  \"generatedAt\": ").append(System.currentTimeMillis()).append(",\n")
            append("  \"records\": [")
            if (eligible.isNotEmpty()) append("\n")
            eligible.forEachIndexed { index, row ->
                append("    {\n")
                append("      \"resolver\": {\"address\": ").append(json(row.resolverAddress))
                    .append(", \"port\": ").append(row.resolverPort).append("},\n")
                append("      \"engine\": ").append(json(row.tunnelProtocol)).append(",\n")
                append("      \"profile\": {\n")
                append("        \"id\": ").append(json(row.profileId)).append(",\n")
                append("        \"name\": ").append(json(row.profileName)).append(",\n")
                append("        \"serverHost\": ").append(json(row.serverHost)).append(",\n")
                append("        \"serverPort\": ").append(if (row.tunnelProtocol == "DNSTT") "null" else row.serverPort.toString()).append(",\n")
                append("        \"dnsttServerPublicKey\": ").append(nullableJson(row.dnsttServerPublicKey)).append(",\n")
                append("        \"slipstreamTlsServerName\": ").append(nullableJson(row.slipstreamTlsServerName)).append("\n")
                append("      },\n")
                append("      \"verification\": {\"status\": \"FULL_PASS\", \"strength\": \"FULL_HTTP\", \"mode\": \"FULL\", \"httpStatusCode\": ")
                    .append(row.httpStatusCode).append(", \"elapsedMs\": ").append(row.elapsedMs).append("},\n")
                append("      \"lastSuccessAt\": ").append(row.testedAt).append(",\n")
                append("      \"network\": {")
                append("\"key\": ").append(json(row.networkKey))
                append(", \"transport\": ").append(json(row.networkTransport))
                append(", \"label\": ").append(nullableJson(row.networkLabel))
                append(", \"operator\": ").append(nullableJson(row.operatorName))
                append(", \"providerId\": ").append(nullableJson(row.providerId))
                append("}\n")
                append("    }")
                if (index != eligible.lastIndex) append(",")
                append("\n")
            }
            append("  ]\n")
            append("}\n")
        }

        return SlipNetE2eExportResult(
            resolverListText = resolverList,
            reportJson = report,
            exportedCount = eligible.size,
            skippedCount = rows.size - eligible.size
        )
    }

    private fun nullableJson(value: String?): String = value?.let(::json) ?: "null"

    private fun json(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) {
                    append("\\u").append(char.code.toString(16).padStart(4, '0'))
                } else append(char)
            }
        }
        append('"')
    }
}
