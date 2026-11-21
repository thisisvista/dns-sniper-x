package com.thisisvista.dnssniperx.data

/** Non-secret configuration metadata for a future tunnel adapter. */
enum class TunnelProtocol { DNSTT, SLIPSTREAM }

data class TunnelProfile(
    val id: String,
    val name: String,
    val protocol: TunnelProtocol,
    val serverHost: String,
    val serverPort: Int,
    /** Public Noise key metadata; never a private key. */
    val dnsttServerPublicKey: String? = null,
    /** TLS name used for Slipstream certificate hostname verification. */
    val slipstreamTlsServerName: String? = null
) {
    fun validate(): List<String> = buildList {
        if (id.isBlank()) add("Profile ID is required")
        if (name.isBlank()) add("Profile name is required")
        if (serverHost.isBlank() || serverHost.any(Char::isWhitespace)) {
            add("Server host must be non-empty and contain no whitespace")
        }
        if (serverPort !in 1..65535) add("Server port must be between 1 and 65535")
        when (protocol) {
            TunnelProtocol.DNSTT -> {
                if (dnsttServerPublicKey.isNullOrBlank()) add("DNSTT server public key is required")
                if (slipstreamTlsServerName != null) add("Slipstream TLS server name is not valid for DNSTT")
            }
            TunnelProtocol.SLIPSTREAM -> {
                if (slipstreamTlsServerName.isNullOrBlank() ||
                    slipstreamTlsServerName.any(Char::isWhitespace)
                ) add("Slipstream TLS server name is required")
                if (dnsttServerPublicKey != null) add("DNSTT public key is not valid for Slipstream")
            }
        }
    }

    fun isValid(): Boolean = validate().isEmpty()
}
