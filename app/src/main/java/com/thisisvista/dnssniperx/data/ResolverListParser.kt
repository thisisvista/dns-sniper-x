package com.thisisvista.dnssniperx.data

import java.net.Inet6Address
import java.net.InetAddress

/**
 * Parses one resolver endpoint per line. Syntax validation only; no network test is performed.
 */
object ResolverListParser {
    private val hostnamePattern = Regex("^(?=.{1,254}$)(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)*[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.?$")
    private val ipv4Pattern = Regex("^(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)(?:\\.(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}$")
    private val csvHeaderValues = setOf("ip", "ip_address", "address", "resolver", "dns", "nameserver", "name_server", "server", "host")
    // WhiteDNS detailed TXT reports use lines such as:
    // "1.2.3.4  status=valid  score=6/6 ...". Import the endpoint as an
    // untested candidate; the report's verdict is historical and must be rechecked.
    private val whiteDnsDetailLine = Regex("^([^\\s,]+)\\s+status=", RegexOption.IGNORE_CASE)

    fun parse(text: String, source: String = "manual"): List<ResolverEntity> {
        val now = System.currentTimeMillis()
        return text.lineSequence()
            .map { it.removePrefix("\uFEFF").substringBefore("#").trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { rawLine ->
                val line = whiteDnsDetailLine.find(rawLine)?.groupValues?.get(1) ?: rawLine
                val columns = line.split(',', limit = 3)
                val address = columns.first().trim().removeSurrounding("\"")
                if (address.lowercase() in csvHeaderValues) return@mapNotNull null

                // Public resolver CSV files commonly provide the port in column two.
                // Preserve explicit endpoint ports, and bracket bare IPv6 before adding
                // a CSV port so the endpoint remains unambiguous.
                val csvPort = columns.getOrNull(1)?.trim()?.removeSurrounding("\"")?.toIntOrNull()
                val endpoint = when {
                    csvPort == null || address.startsWith("[") || address.count { it == ':' } == 1 -> address
                    address.count { it == ':' } > 1 -> "[$address]:$csvPort"
                    else -> "$address:$csvPort"
                }
                parseEndpoint(endpoint, source.trim().ifEmpty { "manual" }, now)
            }
            .distinctBy { it.address.lowercase() to it.port }
            .toList()
    }

    private fun parseEndpoint(endpoint: String, source: String, now: Long): ResolverEntity? {
        if (endpoint.isEmpty()) return null
        val address: String
        val port: Int

        if (endpoint.startsWith("[")) {
            val closing = endpoint.indexOf(']')
            if (closing <= 1) return null
            address = endpoint.substring(1, closing)
            val suffix = endpoint.substring(closing + 1)
            port = if (suffix.isEmpty()) 53 else {
                if (!suffix.startsWith(":")) return null
                suffix.drop(1).toIntOrNull() ?: return null
            }
            if (!isIpv6(address)) return null
        } else if (endpoint.count { it == ':' } == 1) {
            address = endpoint.substringBefore(':')
            port = endpoint.substringAfter(':').toIntOrNull() ?: return null
            val validIpv4 = isIpv4(address)
            val validHostname = hostnamePattern.matches(address) && !address.matches(Regex("^[0-9.]+$"))
            if (!validIpv4 && !validHostname) return null
        } else {
            address = endpoint
            port = 53
            if (!isIpv4(address) && !isIpv6(address) &&
                (!hostnamePattern.matches(address) || address.matches(Regex("^[0-9.]+$")))) return null
        }

        if (port !in 1..65535) return null
        return ResolverEntity(address = address.lowercase(), port = port, source = source, collectedAt = now)
    }

    private fun isIpv4(value: String) = ipv4Pattern.matches(value)

    private fun isIpv6(value: String): Boolean {
        if (!value.contains(':') || value.contains('%')) return false
        return try {
            InetAddress.getByName(value) is Inet6Address
        } catch (_: Exception) {
            false
        }
    }
}
