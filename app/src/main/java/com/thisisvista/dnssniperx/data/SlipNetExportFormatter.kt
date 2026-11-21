package com.thisisvista.dnssniperx.data

/**
 * Produces the plain IPv4 list accepted by SlipNet's DNS scanner import.
 * Only working IPv4 resolvers on the standard DNS port are included.
 */
data class SlipNetExportResult(
    val text: String,
    val exportedCount: Int,
    val skippedCount: Int
)

object SlipNetExportFormatter {
    fun format(resolvers: List<ResolverEntity>): SlipNetExportResult {
        val working = resolvers
            .filter { it.udpStatus == "PASS" || it.tcpStatus == "PASS" }
            .sortedBy { resolver ->
                listOfNotNull(
                    resolver.udpLatencyMs.takeIf { resolver.udpStatus == "PASS" },
                    resolver.tcpLatencyMs.takeIf { resolver.tcpStatus == "PASS" }
                ).minOrNull() ?: Long.MAX_VALUE
            }

        val compatible = working
            .filter { it.port == 53 && isIpv4Literal(it.address) }
            .distinctBy { it.address }

        val text = compatible.joinToString(separator = "\n") { it.address }
            .let { if (it.isEmpty()) "" else "$it\n" }

        return SlipNetExportResult(
            text = text,
            exportedCount = compatible.size,
            skippedCount = working.size - compatible.size
        )
    }

    fun isIpv4Literal(address: String): Boolean {
        val parts = address.split(".")
        if (parts.size != 4) return false
        return parts.all { part ->
            part.isNotEmpty() &&
                part.all(Char::isDigit) &&
                (part.length == 1 || !part.startsWith("0")) &&
                (part.toIntOrNull() in 0..255)
        }
    }
}
