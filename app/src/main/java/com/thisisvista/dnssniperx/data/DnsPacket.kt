package com.thisisvista.dnssniperx.data

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
        // EDNS(0) advertises a 1232-byte UDP payload to reduce IP fragmentation.
        val buffer = java.nio.ByteBuffer.allocate(12 + questionSize + 11)
            .order(java.nio.ByteOrder.BIG_ENDIAN)
        buffer.putShort(transactionId.toShort())
        buffer.putShort(0x0100.toShort())
        buffer.putShort(1)
        buffer.putShort(0)
        buffer.putShort(0)
        buffer.putShort(1)
        encodedLabels.forEach { label ->
            buffer.put(label.size.toByte())
            buffer.put(label)
        }
        buffer.put(0)
        buffer.putShort(1)
        buffer.putShort(1)
        buffer.put(0)
        buffer.putShort(41)
        buffer.putShort(1232)
        buffer.putInt(0)
        buffer.putShort(0)
        return buffer.array()
    }

    data class ResponseInfo(val status: String, val detail: String)
    private data class EdnsInfo(val extendedRcode: Int, val description: String)

    fun validateResponse(query: ByteArray, response: ByteArray): ResponseInfo {
        require(query.size >= 12 && response.size >= 12) { "DNS packet is too short" }
        require(unsignedShort(query, 0) == unsignedShort(response, 0)) { "DNS transaction ID mismatch" }
        val flags = unsignedShort(response, 2)
        require((flags and 0x8000) != 0) { "Packet is not a DNS response" }
        require((flags and 0x7800) == 0) { "Unexpected DNS opcode" }

        val edns = inspectAdditionalRecords(response)
        val responseCode = (edns.extendedRcode shl 4) or (flags and 0x000f)
        val answerCount = unsignedShort(response, 6)
        val responseCodeName = when (responseCode) {
            0 -> "NOERROR"
            1 -> "FORMERR"
            2 -> "SERVFAIL"
            3 -> "NXDOMAIN"
            4 -> "NOTIMP"
            5 -> "REFUSED"
            16 -> "BADVERS"
            else -> "RCODE_${responseCode}"
        }
        val detail = "DNS response: ${responseCodeName} (RCODE ${responseCode}), answers: ${answerCount}; ${edns.description}"
        return when {
            (flags and 0x0200) != 0 -> ResponseInfo("TRUNCATED", "${detail}; UDP response was truncated")
            responseCode != 0 -> ResponseInfo("DNS_ERROR", detail)
            answerCount == 0 -> ResponseInfo("NO_ANSWER", "${detail}; valid response without an answer")
            else -> ResponseInfo("PASS", detail)
        }
    }

    /** Inspects OPT records in the Additional section; absence is informational, not a DNS failure. */
    private fun inspectAdditionalRecords(packet: ByteArray): EdnsInfo {
        var offset = 12
        val questions = unsignedShort(packet, 4)
        val answers = unsignedShort(packet, 6)
        val authorities = unsignedShort(packet, 8)
        val additionals = unsignedShort(packet, 10)

        repeat(questions) {
            offset = skipName(packet, offset)
            require(offset + 4 <= packet.size) { "Truncated DNS question" }
            offset += 4
        }

        var opt: EdnsInfo? = null
        repeat(answers + authorities + additionals) { index ->
            offset = skipName(packet, offset)
            require(offset + 10 <= packet.size) { "Truncated DNS resource record" }
            val type = unsignedShort(packet, offset)
            val recordClass = unsignedShort(packet, offset + 2)
            val ttl = unsignedInt(packet, offset + 4)
            val dataLength = unsignedShort(packet, offset + 8)
            val dataStart = offset + 10
            require(dataStart + dataLength <= packet.size) { "Truncated DNS record data" }

            if (index >= answers + authorities && type == 41) {
                val version = ((ttl ushr 16) and 0xff).toInt()
                val extendedRcode = ((ttl ushr 24) and 0xff).toInt()
                val ednsFlags = (ttl and 0xffff).toInt()
                val description = if (version == 0) {
                    "EDNS supported (UDP payload: ${recordClass} bytes, flags: 0x${ednsFlags.toString(16).padStart(4, '0')})"
                } else {
                    "EDNS OPT present with unsupported version ${version}"
                }
                opt = EdnsInfo(extendedRcode, description)
            }
            offset = dataStart + dataLength
        }
        require(offset == packet.size) { "Unexpected trailing bytes in DNS response" }
        return opt ?: EdnsInfo(0, "EDNS not advertised by server")
    }

    /** Returns the offset immediately after a possibly compressed DNS name. */
    private fun skipName(packet: ByteArray, start: Int): Int {
        var offset = start
        var steps = 0
        while (true) {
            require(offset < packet.size) { "Truncated DNS name" }
            val length = packet[offset].toInt() and 0xff
            when {
                length == 0 -> return offset + 1
                (length and 0xc0) == 0xc0 -> {
                    require(offset + 1 < packet.size) { "Truncated DNS compression pointer" }
                    val pointer = ((length and 0x3f) shl 8) or (packet[offset + 1].toInt() and 0xff)
                    require(pointer < packet.size) { "Invalid DNS compression pointer" }
                    return offset + 2
                }
                (length and 0xc0) != 0 -> error("Invalid DNS label length")
                else -> {
                    require(offset + 1 + length <= packet.size) { "Truncated DNS label" }
                    offset += length + 1
                }
            }
            steps++
            require(steps <= 127) { "DNS name is too long" }
        }
    }

    private fun unsignedShort(bytes: ByteArray, offset: Int): Int {
        require(offset >= 0 && offset + 2 <= bytes.size) { "Truncated DNS packet" }
        return ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)
    }

    private fun unsignedInt(bytes: ByteArray, offset: Int): Long {
        require(offset >= 0 && offset + 4 <= bytes.size) { "Truncated DNS packet" }
        return ((bytes[offset].toLong() and 0xff) shl 24) or
            ((bytes[offset + 1].toLong() and 0xff) shl 16) or
            ((bytes[offset + 2].toLong() and 0xff) shl 8) or
            (bytes[offset + 3].toLong() and 0xff)
    }
}
