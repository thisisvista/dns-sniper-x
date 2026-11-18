package com.thisisvista.dnssniperx.data

/**
 * Parses one resolver endpoint per line. Syntax validation only; no network test is performed.
 */
object ResolverListParser {
    private val hostnamePattern = Regex("^(?=.{1,253}$)(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)*[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$")
    private val ipv4Pattern = Regex("^(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)(?:\\.(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}$")
    private val ipv6Pattern = Regex("^[0-9A-Fa-f:]+$")

    fun parse(text: String, source: String = "manual"): List<ResolverEntity> {
        val now = System.currentTimeMillis()
        return text.lineSequence()
            .map { it.substringBefore("#").trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { parseEndpoint(it.substringBefore(",").trim(), source.trim().ifEmpty { "manual" }, now) }
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
        } else if (endpoint.count { it == ':' } == 1 && endpoint.substringBefore(':').contains('.')) {
            address = endpoint.substringBefore(':')
            port = endpoint.substringAfter(':').toIntOrNull() ?: return null
            if (!isIpv4(address)) return null
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
    private fun isIpv6(value: String) =
        value.contains(':') && ipv6Pattern.matches(value) && value.count { it == ':' } >= 2
}
