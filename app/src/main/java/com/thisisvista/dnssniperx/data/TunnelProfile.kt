package com.thisisvista.dnssniperx.data

/** Non-secret configuration metadata for a future tunnel adapter. */
enum class TunnelProtocol { DNSTT, SLIPSTREAM }

data class TunnelProfile(
    val id: String,
    val name: String,
    val protocol: TunnelProtocol,
    val serverHost: String,
    val serverPort: Int,
    /** 32-byte Noise server public key, encoded as 64 hexadecimal characters. */
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
                val key = dnsttServerPublicKey
                if (key.isNullOrBlank()) {
                    add("DNSTT server public key is required")
                } else if (key.length != 64 || !key.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                    add("DNSTT public key must be exactly 32 bytes encoded as 64 hexadecimal characters")
                }
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
