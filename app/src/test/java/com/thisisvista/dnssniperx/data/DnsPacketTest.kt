package com.thisisvista.dnssniperx.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DnsPacketTest {
    @Test
    fun buildsARecordQueryWithExpectedHeaderAndQuestion() {
        val query = DnsPacket.buildQuery("example.com", 0x1234)

        assertEquals(0x12, query[0].toInt() and 0xff)
        assertEquals(0x34, query[1].toInt() and 0xff)
        assertEquals(0x01, query[2].toInt() and 0xff) // recursion desired
        assertEquals(1, query[5].toInt() and 0xff) // one question
        assertEquals("example.com", decodeQuestionName(query))
        val questionEnd = questionEndOffset(query)
        assertEquals(1, query[questionEnd].toInt() and 0xff) // A
        assertEquals(1, query[questionEnd + 2].toInt() and 0xff) // IN
        assertEquals(1, query[11].toInt() and 0xff) // one additional OPT record
        assertEquals(41, ((query[questionEnd + 4].toInt() and 0xff) shl 8) or
            (query[questionEnd + 5].toInt() and 0xff)) // EDNS OPT type
        assertEquals(1232, ((query[questionEnd + 6].toInt() and 0xff) shl 8) or
            (query[questionEnd + 7].toInt() and 0xff)) // advertised UDP payload
    }

    @Test
    fun acceptsMatchingDnsResponse() {
        val query = DnsPacket.buildQuery("example.com", 42)
        val response = query.copyOf().apply {
            this[2] = 0x81.toByte()
            this[3] = 0x80.toByte()
        }

        DnsPacket.validateResponse(query, response)
    }

    @Test
    fun rejectsMismatchedTransactionId() {
        val query = DnsPacket.buildQuery("example.com", 42)
        val response = query.copyOf().apply {
            this[0] = 0
            this[1] = 7
            this[2] = 0x80.toByte()
        }

        assertThrows(IllegalArgumentException::class.java) {
            DnsPacket.validateResponse(query, response)
        }
    }

    @Test
    fun rejectsPacketsThatAreNotResponses() {
        val query = DnsPacket.buildQuery("example.com", 42)
        assertThrows(IllegalArgumentException::class.java) {
            DnsPacket.validateResponse(query, query.copyOf())
        }
    }

    @Test
    fun rejectsInvalidNames() {
        assertThrows(IllegalArgumentException::class.java) {
            DnsPacket.buildQuery("example..com", 1)
        }
    }


    @Test
    fun identifiesNxDomainAsDnsError() {
        val query = DnsPacket.buildQuery("example.com", 42)
        val response = query.copyOf().apply {
            this[2] = 0x81.toByte()
            this[3] = 0x83.toByte()
        }

        val result = DnsPacket.validateResponse(query, response)
        assertEquals("DNS_ERROR", result.status)
        org.junit.Assert.assertTrue(result.detail.contains("NXDOMAIN"))
    }

    @Test
    fun identifiesNoAnswerResponse() {
        val query = DnsPacket.buildQuery("example.com", 42)
        val response = query.copyOf().apply {
            this[2] = 0x81.toByte()
            this[3] = 0x80.toByte()
        }

        val result = DnsPacket.validateResponse(query, response)
        assertEquals("NO_ANSWER", result.status)
    }

    @Test
    fun identifiesTruncatedUdpResponse() {
        val query = DnsPacket.buildQuery("example.com", 42)
        val response = query.copyOf().apply {
            this[2] = 0x83.toByte()
            this[3] = 0x80.toByte()
            this[7] = 1
        }

        val result = DnsPacket.validateResponse(query, response)
        assertEquals("TRUNCATED", result.status)
    }


    @Test
    fun reportsEdnsWhenServerReturnsOptRecord() {
        val query = DnsPacket.buildQuery("example.com", 42)
        val response = query.copyOf().apply {
            this[2] = 0x81.toByte()
            this[3] = 0x80.toByte()
        }

        val result = DnsPacket.validateResponse(query, response)
        org.junit.Assert.assertTrue(result.detail.contains("EDNS supported"))
        org.junit.Assert.assertTrue(result.detail.contains("1232 bytes"))
    }

    @Test
    fun reportsWhenServerDoesNotAdvertiseEdns() {
        val query = DnsPacket.buildQuery("example.com", 42)
        val response = query.copyOfRange(0, query.size - 11).apply {
            this[2] = 0x81.toByte()
            this[3] = 0x80.toByte()
            this[11] = 0
        }

        val result = DnsPacket.validateResponse(query, response)
        org.junit.Assert.assertTrue(result.detail.contains("EDNS not advertised"))
    }


    @Test
    fun rejectsForwardDnsCompressionPointers() {
        val query = DnsPacket.buildQuery("example.com", 42)
        val response = query.copyOfRange(0, query.size - 11).toMutableList().apply {
            this[2] = 0x81.toByte()
            this[3] = 0x80.toByte()
            this[7] = 1 // one answer
            add(0xc0.toByte()) // answer owner name: compression pointer
            add(0xff.toByte()) // points beyond this record
            addAll(listOf(0, 1, 0, 1, 0, 0, 0, 0, 0, 4, 1, 2, 3, 4).map { it.toByte() })
        }.toByteArray()

        assertThrows(IllegalArgumentException::class.java) {
            DnsPacket.validateResponse(query, response)
        }
    }

    @Test
    fun buildsLegacyQueryWithoutEdnsOptRecord() {
        val query = DnsPacket.buildQuery("example.com", 42, includeEdns = false)
        assertEquals(0, query[10].toInt() and 0xff)
        assertEquals(0, query[11].toInt() and 0xff)
        assertEquals(questionEndOffset(query) + 4, query.size)
    }

    @Test
    fun exposesFormerrResponseCodeForLegacyFallbackDecision() {
        val query = DnsPacket.buildQuery("example.com", 42)
        val response = query.copyOf().apply {
            this[2] = 0x81.toByte()
            this[3] = 0x81.toByte()
        }

        val result = DnsPacket.validateResponse(query, response)
        assertEquals("DNS_ERROR", result.status)
        assertEquals(1, result.responseCode)
    }

    @Test
    fun exposesBadversExtendedResponseCode() {
        val query = DnsPacket.buildQuery("example.com", 42)
        val response = query.copyOf().apply {
            this[2] = 0x81.toByte()
            this[3] = 0x80.toByte()
            val ttlOffset = questionEndOffset(this) + 9
            this[ttlOffset] = 1 // EDNS extended RCODE high byte: BADVERS (16)
        }

        val result = DnsPacket.validateResponse(query, response)
        assertEquals("DNS_ERROR", result.status)
        assertEquals(16, result.responseCode)
        org.junit.Assert.assertTrue(result.detail.contains("BADVERS"))
    }

    @Test
    fun acceptsValidBackwardCompressionPointer() {
        val query = DnsPacket.buildQuery("example.com", 42)
        val response = query.copyOfRange(0, query.size - 11).toMutableList().apply {
            this[2] = 0x81.toByte()
            this[3] = 0x80.toByte()
            this[7] = 1 // one answer
            addAll(listOf(0xc0, 0x0c, 0, 1, 0, 1, 0, 0, 0, 0, 0, 4, 1, 2, 3, 4).map { it.toByte() })
        }.toByteArray()

        val result = DnsPacket.validateResponse(query, response)
        assertEquals("PASS", result.status)
    }

    @Test
    fun rejectsCompressionPointerIntoLabelPayload() {
        val query = DnsPacket.buildQuery("example.com", 42)
        val response = query.copyOfRange(0, query.size - 11).toMutableList().apply {
            this[2] = 0x81.toByte()
            this[3] = 0x80.toByte()
            this[7] = 1 // one answer
            addAll(listOf(0xc0, 0x0d, 0, 1, 0, 1, 0, 0, 0, 0, 0, 4, 1, 2, 3, 4).map { it.toByte() })
        }.toByteArray()

        assertThrows(IllegalArgumentException::class.java) {
            DnsPacket.validateResponse(query, response)
        }
    }

    private fun decodeQuestionName(packet: ByteArray): String {
        val labels = mutableListOf<String>()
        var offset = 12
        while (packet[offset].toInt() != 0) {
            val length = packet[offset].toInt() and 0xff
            offset++
            labels += packet.copyOfRange(offset, offset + length).toString(Charsets.US_ASCII)
            offset += length
        }
        return labels.joinToString(".")
    }

    private fun questionEndOffset(packet: ByteArray): Int {
        var offset = 12
        while (packet[offset].toInt() != 0) {
            offset += (packet[offset].toInt() and 0xff) + 1
        }
        return offset + 1
    }
}
