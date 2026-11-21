package com.thisisvista.dnssniperx.data

data class ActiveNetworkState(
    val isConnected: Boolean = false,
    val transport: ActiveNetworkTransport = ActiveNetworkTransport.NONE,
    val isInternetValidated: Boolean = false,
    val operatorName: String? = null,
    val providerId: String? = null,
    val providerName: String? = null,
    val dnsServers: List<String> = emptyList()
)

enum class ActiveNetworkTransport {
    NONE, WIFI, CELLULAR, VPN, ETHERNET, OTHER
}

object ProviderNameMatcher {
    fun match(operatorName: String?, providers: List<ProviderEntity>): ProviderEntity? {
        val normalizedOperator = normalize(operatorName.orEmpty())
        if (normalizedOperator.isBlank()) return null

        return providers.firstOrNull { provider ->
            val aliases = (provider.displayName + ";" + provider.aliases)
                .split(';')
                .map(::normalize)
                .filter { it.length >= 3 }
            aliases.any { alias ->
                normalizedOperator == alias ||
                    (alias.length >= 5 && normalizedOperator.contains(alias)) ||
                    (normalizedOperator.length >= 5 && alias.contains(normalizedOperator))
            }
        }
    }

    private fun normalize(value: String): String =
        value.trim().lowercase().filter { it.isLetterOrDigit() }
}
