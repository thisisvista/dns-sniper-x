package com.thisisvista.dnssniperx.data

/**
 * Pure DNS wire-format helpers. Kept separate from sockets so packet construction
 * and response validation can be covered by local unit tests.
 */
internal object DnsPacket {
    fun buildQuery(
        name: String,
        transactionId: Int,
        includeEdns: Boolean = true,
        requestDnssec: Boolean = false,
        queryType: Int = 1,
        udpPayloadSize: Int = 1232
    ): ByteArray {
        require(transactionId in 0..65_535) { "DNS transaction ID must be 16-bit" }
        require(queryType in 1..65_535) { "DNS query type must be a valid 16-bit value" }
        require(udpPayloadSize in 512..4096) { "EDNS UDP payload size must be between 512 and 4096 bytes" }
        require(!requestDnssec || includeEdns) { "DNSSEC requests require EDNS" }
        val normalizedName = name.trimEnd('.')
        require(normalizedName.isNotEmpty() && normalizedName.all { it.code in 0x21..0x7e }) {
            "DNS name must contain printable ASCII characters only"
        }
        val labels = normalizedName.split('.')
        require(labels.isNotEmpty() && labels.all { it.isNotEmpty() }) { "Invalid DNS name" }
        val encodedLabels = labels.map { label ->
            val bytes = label.toByteArray(Charsets.US_ASCII)
            require(bytes.size in 1..63) { "Invalid DNS label" }
            bytes
        }
        val wireNameLength = encodedLabels.sumOf { it.size + 1 } + 1
        require(wireNameLength <= 255) { "DNS name is too long" }
        val questionSize = wireNameLength + 4
        // EDNS(0) advertises a 1232-byte UDP payload to reduce IP fragmentation.
        val buffer = java.nio.ByteBuffer.allocate(12 + questionSize + if (includeEdns) 11 else 0)
            .order(java.nio.ByteOrder.BIG_ENDIAN)
        buffer.putShort(transactionId.toShort())
        buffer.putShort(0x0100.toShort())
        buffer.putShort(1)
        buffer.putShort(0)
        buffer.putShort(0)
        buffer.putShort(if (includeEdns) 1 else 0)
        encodedLabels.forEach { label ->
            buffer.put(label.size.toByte())
            buffer.put(label)
        }
        buffer.put(0)
        buffer.putShort(queryType.toShort())
        buffer.putShort(1)
        if (includeEdns) {
            buffer.put(0)
            buffer.putShort(41)
            buffer.putShort(udpPayloadSize.toShort())
            // EDNS flags: DO (DNSSEC OK) requests DNSSEC records; it does not
            // mean the resolver validates DNSSEC signatures.
            buffer.putInt(if (requestDnssec) 0x00008000 else 0)
            buffer.putShort(0)
        }
        return buffer.array()
    }

    data class ResponseInfo(val status: String, val detail: String, val responseCode: Int)
    private data class PacketMetadata(val extendedRcode: Int, val description: String, val hasRelevantAnswer: Boolean)

    fun validateResponse(query: ByteArray, response: ByteArray): ResponseInfo {
        require(query.size >= 12 && response.size >= 12) { "DNS packet is too short" }
        require(unsignedShort(query, 0) == unsignedShort(response, 0)) { "DNS transaction ID mismatch" }
        val flags = unsignedShort(response, 2)
        require((flags and 0x8000) != 0) { "Packet is not a DNS response" }
        require((flags and 0x7800) == 0) { "Unexpected DNS opcode" }

        // A response must echo the question that was actually sent.
        require(unsignedShort(query, 4) == 1 && unsignedShort(response, 4) == 1) {
            "Expected exactly one DNS question"
        }
        val queryName = readName(query, 12)
        val responseName = readName(response, 12)
        require(queryName.value.equals(responseName.value, ignoreCase = true)) {
            "DNS question name mismatch"
        }
        require(queryName.nextOffset + 4 <= query.size &&
            responseName.nextOffset + 4 <= response.size) { "Truncated DNS question" }
        val queryType = unsignedShort(query, queryName.nextOffset)
        val queryClass = unsignedShort(query, queryName.nextOffset + 2)
        val responseType = unsignedShort(response, responseName.nextOffset)
        val responseClass = unsignedShort(response, responseName.nextOffset + 2)
        require(queryType == responseType && queryClass == responseClass) {
            "DNS question type or class mismatch"
        }

        val answerCount = unsignedShort(response, 6)
        // A TC response is allowed to omit the remainder of an RR. Validate
        // the echoed question above, then classify truncation before parsing
        // answer/authority/additional records that may be incomplete.
        if ((flags and 0x0200) != 0) {
            val responseCode = flags and 0x000f
            return ResponseInfo(
                "TRUNCATED",
                "DNS response is truncated (TC flag set); answers: ${answerCount}; remaining records may be incomplete",
                responseCode
            )
        }

        val metadata = inspectAdditionalRecords(response, queryType)
        val responseCode = (metadata.extendedRcode shl 4) or (flags and 0x000f)
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
        val detail = "DNS response: ${responseCodeName} (RCODE ${responseCode}), answers: ${answerCount}; ${metadata.description}"
        return when {
            responseCode != 0 -> ResponseInfo("DNS_ERROR", detail, responseCode)
            answerCount == 0 || !metadata.hasRelevantAnswer -> ResponseInfo("NO_ANSWER", "${detail}; no answer matching the requested type or CNAME", responseCode)
            else -> ResponseInfo("PASS", detail, responseCode)
        }
    }

    /** Inspects OPT records in the Additional section; absence is informational, not a DNS failure. */
    private fun inspectAdditionalRecords(packet: ByteArray, queryType: Int): PacketMetadata {
        var offset = 12
        val questions = unsignedShort(packet, 4)
        val answers = unsignedShort(packet, 6)
        val authorities = unsignedShort(packet, 8)
        val additionals = unsignedShort(packet, 10)

        val knownLabelOffsets = mutableSetOf<Int>()
        repeat(questions) {
            offset = skipName(packet, offset, knownLabelOffsets)
            require(offset + 4 <= packet.size) { "Truncated DNS question" }
            offset += 4
        }

        var opt: PacketMetadata? = null
        var hasRelevantAnswer = false
        repeat(answers + authorities + additionals) { index ->
            val owner = readName(packet, offset, knownLabelOffsets)
            offset = owner.nextOffset
            require(offset + 10 <= packet.size) { "Truncated DNS resource record" }
            val type = unsignedShort(packet, offset)
            val recordClass = unsignedShort(packet, offset + 2)
            val ttl = unsignedInt(packet, offset + 4)
            val dataLength = unsignedShort(packet, offset + 8)
            val dataStart = offset + 10
            require(dataStart + dataLength <= packet.size) { "Truncated DNS record data" }
            if (index < answers && recordClass == 1) {
                when (type) {
                    queryType -> {
                        if (type == 1) require(dataLength == 4) { "Invalid A record length" }
                        if (type == 28) require(dataLength == 16) { "Invalid AAAA record length" }
                        hasRelevantAnswer = true
                    }
                    5 -> {
                        val cname = readName(packet, dataStart, knownLabelOffsets)
                        require(cname.nextOffset == dataStart + dataLength) {
                            "Invalid CNAME record data length"
                        }
                        hasRelevantAnswer = true
                    }
                }
            }

            if (type == 41) {
                require(index >= answers + authorities) {
                    "EDNS OPT record must appear in the Additional section"
                }
                require(owner.value.isEmpty()) { "EDNS OPT record owner name must be root" }
                require(opt == null) { "DNS response contains multiple EDNS OPT records" }

                // Each EDNS option is a 4-byte code/length header followed by
                // exactly the number of data bytes declared by that option.
                val recordEnd = dataStart + dataLength
                var optionOffset = dataStart
                while (optionOffset < recordEnd) {
                    require(optionOffset + 4 <= recordEnd) {
                        "Truncated EDNS option header"
                    }
                    val optionLength = unsignedShort(packet, optionOffset + 2)
                    optionOffset += 4
                    require(optionOffset + optionLength <= recordEnd) {
                        "Truncated EDNS option data"
                    }
                    optionOffset += optionLength
                }

                val version = ((ttl ushr 16) and 0xff).toInt()
                val extendedRcode = ((ttl ushr 24) and 0xff).toInt()
                val ednsFlags = (ttl and 0xffff).toInt()
                val description = if (version == 0) {
                    "EDNS supported (UDP payload: ${recordClass} bytes, flags: 0x${ednsFlags.toString(16).padStart(4, '0')})"
                } else {
                    "EDNS OPT present with unsupported version ${version}"
                }
                opt = PacketMetadata(extendedRcode, description, hasRelevantAnswer)
            }
            offset = dataStart + dataLength
        }
        require(offset == packet.size) { "Unexpected trailing bytes in DNS response" }
        return opt?.copy(hasRelevantAnswer = hasRelevantAnswer)
            ?: PacketMetadata(0, "EDNS not advertised by server", hasRelevantAnswer)
    }

    /**
     * Returns the offset immediately after a possibly compressed DNS name.
     * Compression targets are traversed and validated, not merely skipped.
     */
    private data class NameInfo(val value: String, val nextOffset: Int)

    private fun skipName(
        packet: ByteArray,
        start: Int,
        knownLabelOffsets: MutableSet<Int>
    ): Int = readName(packet, start, knownLabelOffsets).nextOffset

    /** Decodes a DNS name, following only backward pointers to previously parsed label boundaries. */
    private fun readName(
        packet: ByteArray,
        start: Int,
        knownLabelOffsets: MutableSet<Int> = mutableSetOf()
    ): NameInfo {
        var offset = start
        var nextOffset: Int? = null
        var expandedLength = 0
        var steps = 0
        val visited = mutableSetOf<Int>()
        val labels = mutableListOf<String>()

        while (true) {
            require(offset < packet.size) { "Truncated DNS name" }
            require(visited.add(offset)) { "DNS compression pointer loop" }
            val length = packet[offset].toInt() and 0xff
            when {
                length == 0 -> {
                    knownLabelOffsets += offset
                    expandedLength += 1
                    require(expandedLength <= 255) { "DNS name is too long" }
                    return NameInfo(labels.joinToString("."), nextOffset ?: (offset + 1))
                }
                (length and 0xc0) == 0xc0 -> {
                    // A compressed name may itself be the target of a later
                    // pointer, so record the pointer's starting boundary too.
                    knownLabelOffsets += offset
                    require(offset + 1 < packet.size) { "Truncated DNS compression pointer" }
                    val pointer = ((length and 0x3f) shl 8) or (packet[offset + 1].toInt() and 0xff)
                    require(pointer >= 12 && pointer < offset) {
                        "DNS compression pointer must reference an earlier name"
                    }
                    require(pointer in knownLabelOffsets) {
                        "DNS compression pointer must reference a known label boundary"
                    }
                    if (nextOffset == null) nextOffset = offset + 2
                    offset = pointer
                }
                (length and 0xc0) != 0 -> throw IllegalArgumentException("Invalid DNS label length")
                else -> {
                    knownLabelOffsets += offset
                    require(offset + 1 + length <= packet.size) { "Truncated DNS label" }
                    expandedLength += length + 1
                    require(expandedLength <= 255) { "DNS name is too long" }
                    labels += packet.copyOfRange(offset + 1, offset + 1 + length)
                        .toString(Charsets.US_ASCII)
                    offset += length + 1
                }
            }
            steps++
            require(steps <= 256) { "DNS name is too long or malformed" }
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
