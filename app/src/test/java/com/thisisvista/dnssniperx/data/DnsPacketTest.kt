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
        assertEquals(1, query[query.size - 4].toInt() and 0xff) // A
        assertEquals(1, query[query.size - 2].toInt() and 0xff) // IN
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
}
