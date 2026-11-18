package com.thisisvista.dnssniperx.data

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pure DNS wire-format helpers. Kept separate from sockets so packet construction
 * and response validation can be covered by local unit tests.
 */
internal object DnsPacket {
    fun buildQuery(name: String, transactionId: Int): ByteArray {
        require(transactionId in 0..65_535) { "DNS transaction ID must be 16-bit" }
        val labels = name.trimEnd('.').split('.')
        require(labels.isNotEmpty() && labels.all { it.isNotEmpty() }) { "Invalid DNS name" }
        val encodedLabels = labels.map { label ->
            val bytes = label.toByteArray(Charsets.US_ASCII)
            require(bytes.size in 1..63 && bytes.all { (it.toInt() and 0x80) == 0 }) {
                "Invalid DNS label"
            }
            bytes
        }
        val questionSize = encodedLabels.sumOf { it.size + 1 } + 1 + 4
        val buffer = ByteBuffer.allocate(12 + questionSize).order(ByteOrder.BIG_ENDIAN)
        buffer.putShort(transactionId.toShort())
        buffer.putShort(0x0100.toShort())
        buffer.putShort(1)
        buffer.putShort(0)
        buffer.putShort(0)
        buffer.putShort(0)
        encodedLabels.forEach { label ->
            buffer.put(label.size.toByte())
            buffer.put(label)
        }
        buffer.put(0)
        buffer.putShort(1) // A
        buffer.putShort(1) // IN
        return buffer.array()
    }

    data class ResponseInfo(val status: String, val detail: String)

    fun validateResponse(query: ByteArray, response: ByteArray): ResponseInfo {
        require(query.size >= 12 && response.size >= 12) { "DNS packet is too short" }
        val queryId = unsignedShort(query, 0)
        val responseId = unsignedShort(response, 0)
        require(queryId == responseId) { "DNS transaction ID mismatch" }
        val flags = unsignedShort(response, 2)
        require((flags and 0x8000) != 0) { "Packet is not a DNS response" }
        require((flags and 0x7800) == 0) { "Unexpected DNS opcode" }

        val responseCode = flags and 0x000f
        val answerCount = unsignedShort(response, 6)
        val responseCodeName = when (responseCode) {
            0 -> "NOERROR"
            1 -> "FORMERR"
            2 -> "SERVFAIL"
            3 -> "NXDOMAIN"
            4 -> "NOTIMP"
            5 -> "REFUSED"
            else -> "RCODE_$responseCode"
        }
        val detail = "DNS response: $responseCodeName (RCODE $responseCode), answers: $answerCount"
        return when {
            (flags and 0x0200) != 0 -> ResponseInfo("TRUNCATED", "$detail; UDP response was truncated")
            responseCode != 0 -> ResponseInfo("DNS_ERROR", detail)
            answerCount == 0 -> ResponseInfo("NO_ANSWER", "$detail; valid response without an answer")
            else -> ResponseInfo("PASS", detail)
        }
    }

    private fun unsignedShort(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)
}
