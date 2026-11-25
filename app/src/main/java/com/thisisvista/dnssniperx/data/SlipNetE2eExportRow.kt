package com.thisisvista.dnssniperx.data

/** One latest successful FULL E2E proof row joined to the exact tested engine profile. */
data class SlipNetE2eExportRow(
    val resolverAddress: String,
    val resolverPort: Int,
    val testedAt: Long,
    val networkKey: String,
    val networkTransport: String,
    val networkLabel: String?,
    val operatorName: String?,
    val providerId: String?,
    val profileId: String,
    val profileName: String,
    val tunnelProtocol: String,
    val serverHost: String,
    val serverPort: Int,
    val dnsttServerPublicKey: String?,
    val slipstreamTlsServerName: String?,
    val verificationMode: String,
    val status: String,
    val elapsedMs: Long,
    val httpStatusCode: Int?
)
