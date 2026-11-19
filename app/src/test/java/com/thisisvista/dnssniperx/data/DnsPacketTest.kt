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
        assertEquals(0, query[questionEnd].toInt() and 0xff) // A, high byte
        assertEquals(1, query[questionEnd + 1].toInt() and 0xff) // A, low byte
        assertEquals(0, query[questionEnd + 2].toInt() and 0xff) // IN, high byte
        assertEquals(1, query[questionEnd + 3].toInt() and 0xff) // IN, low byte
        assertEquals(1, query[11].toInt() and 0xff) // one additional OPT record
        assertEquals(41, ((query[questionEnd + 5].toInt() and 0xff) shl 8) or
            (query[questionEnd + 6].toInt() and 0xff)) // EDNS OPT type
        assertEquals(1232, ((query[questionEnd + 7].toInt() and 0xff) shl 8) or
            (query[questionEnd + 8].toInt() and 0xff)) // advertised UDP payload
    }

    @Test
    fun buildsDnskeyQueryWithCustomEdnsPayload() {
        val query = DnsPacket.buildQuery(
            "org",
            7,
            requestDnssec = true,
            queryType = 48,
            udpPayloadSize = 4096
        )
        val questionEnd = questionEndOffset(query)
        assertEquals(48, ((query[questionEnd].toInt() and 0xff) shl 8) or
            (query[questionEnd + 1].toInt() and 0xff))
        assertEquals(4096, ((query[questionEnd + 7].toInt() and 0xff) shl 8) or
            (query[questionEnd + 8].toInt() and 0xff))
    }

    @Test
    fun rejectsEdnsPayloadOutsideSupportedDiagnosticRange() {
        assertThrows(IllegalArgumentException::class.java) {
            DnsPacket.buildQuery("example.com", 1, udpPayloadSize = 500)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DnsPacket.buildQuery("example.com", 1, udpPayloadSize = 4097)
        }
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
    fun rejectsNonAsciiDnsNamesInsteadOfSilentlyReplacingCharacters() {
        assertThrows(IllegalArgumentException::class.java) {
            DnsPacket.buildQuery("café.example", 1)
        }
    }

    @Test
    fun rejectsDnsNamesLongerThan255WireBytes() {
        val label = "a".repeat(63)
        val overlongName = listOf(label, label, label, label).joinToString(".")

        assertThrows(IllegalArgumentException::class.java) {
            DnsPacket.buildQuery(overlongName, 1)
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
    fun rejectsUnrelatedAnswerTypeForAddressQuery() {
        val query = DnsPacket.buildQuery("example.com", 42)
        val response = query.copyOfRange(0, query.size - 11).toMutableList().apply {
            this[2] = 0x81.toByte()
            this[3] = 0x80.toByte()
            this[7] = 1 // one answer
            this[11] = 0 // no additional records
            // A TXT answer is not a meaningful answer to an A query.
            addAll(listOf(0xc0, 0x0c, 0, 16, 0, 1, 0, 0, 0, 0, 0, 1, 'x'.code).map { it.toByte() })
        }.toByteArray()

        val result = DnsPacket.validateResponse(query, response)
        assertEquals("NO_ANSWER", result.status)
    }

    @Test
    fun acceptsCnameAsAnswerToAddressQuery() {
        val query = DnsPacket.buildQuery("example.com", 42)
        val response = query.copyOfRange(0, query.size - 11).toMutableList().apply {
            this[2] = 0x81.toByte()
            this[3] = 0x80.toByte()
            this[7] = 1 // one answer
            this[11] = 0 // no additional records
            // CNAME answer for example.com pointing to alias.example.net.
            addAll(listOf(0xc0, 0x0c, 0, 5, 0, 1, 0, 0, 0, 0, 0, 19).map { it.toByte() })
            addAll(listOf(5, 'a'.code, 'l'.code, 'i'.code, 'a'.code, 's'.code,
                7, 'e'.code, 'x'.code, 'a'.code, 'm'.code, 'p'.code, 'l'.code, 'e'.code,
                3, 'n'.code, 'e'.code, 't'.code, 0).map { it.toByte() })
        }.toByteArray()

        val result = DnsPacket.validateResponse(query, response)
        assertEquals("PASS", result.status)
    }

    @Test
    fun identifiesTruncatedUdpResponse() {
        val query = DnsPacket.buildQuery("example.com", 42)
        val response = query.copyOf().apply {
            this[2] = 0x83.toByte()
            this[3] = 0x80.toByte()
            this[7] = 0
        }

        val result = DnsPacket.validateResponse(query, response)
        assertEquals("TRUNCATED", result.status)
    }

    @Test
    fun identifiesTruncatedResponseBeforeParsingIncompleteAnswerRecords() {
        val query = DnsPacket.buildQuery("org", 42, queryType = 48, requestDnssec = true)
        // TC is set and ANCOUNT says one answer, but the packet ends after
        // the complete question. This is a valid truncated DNS response.
        val response = query.copyOfRange(0, questionEndOffset(query) + 4).apply {
            this[2] = 0x83.toByte()
            this[3] = 0x80.toByte()
            this[6] = 0
            this[7] = 1
            this[10] = 0
            this[11] = 0
        }

        val result = DnsPacket.validateResponse(query, response)
        assertEquals("TRUNCATED", result.status)
        org.junit.Assert.assertTrue(result.detail.contains("TC flag set"))
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
    fun setsDnssecOkBitWhenRequested() {
        val query = DnsPacket.buildQuery("example.com", 42, requestDnssec = true)
        val ttlOffset = questionEndOffset(query) + 9

        assertEquals(0x80, query[ttlOffset + 2].toInt() and 0xff)
        assertEquals(0x00, query[ttlOffset + 3].toInt() and 0xff)
    }

    @Test
    fun rejectsDnssecRequestWithoutEdns() {
        assertThrows(IllegalArgumentException::class.java) {
            DnsPacket.buildQuery("example.com", 42, includeEdns = false, requestDnssec = true)
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
            this[11] = 0 // no additional records in this synthetic response
            addAll(listOf(0xc0, 0x0c, 0, 1, 0, 1, 0, 0, 0, 0, 0, 4, 1, 2, 3, 4).map { it.toByte() })
        }.toByteArray()

        val result = DnsPacket.validateResponse(query, response)
        assertEquals("PASS", result.status)
    }

    @Test
    fun acceptsCompressionPointerToEarlierPointerName() {
        val query = DnsPacket.buildQuery("example.com", 42)
        val firstAnswerOffset = questionEndOffset(query) + 4
        val response = query.copyOfRange(0, query.size - 11).toMutableList().apply {
            this[2] = 0x81.toByte()
            this[3] = 0x80.toByte()
            this[7] = 2 // two answers
            this[11] = 0 // no additional records
            // First owner points to the question name at offset 12.
            add(0xc0.toByte())
            add(0x0c)
            addAll(listOf(0, 1, 0, 1, 0, 0, 0, 0, 0, 4, 1, 2, 3, 4).map { it.toByte() })
            // Second owner points to the first answer's compression pointer.
            add(0xc0.toByte())
            add(firstAnswerOffset.toByte())
            addAll(listOf(0, 1, 0, 1, 0, 0, 0, 0, 0, 4, 5, 6, 7, 8).map { it.toByte() })
        }.toByteArray()

        val result = DnsPacket.validateResponse(query, response)
        assertEquals("PASS", result.status)
    }

    @Test
    fun rejectsCompressionPointerIntoLabelPayload() {
        // The byte at offset 13 is deliberately 3, which looks like a valid
        // label length but is actually the first byte inside the "name" label.
        // Mutate a valid ASCII-built packet so the test reaches pointer validation.
        val query = DnsPacket.buildQuery("abc.com", 42).apply {
            this[13] = 3
        }
        val response = query.copyOfRange(0, query.size - 11).toMutableList().apply {
            this[2] = 0x81.toByte()
            this[3] = 0x80.toByte()
            this[7] = 1 // one answer
            this[11] = 0 // no additional records in this synthetic response
            addAll(listOf(0xc0, 0x0d, 0, 1, 0, 1, 0, 0, 0, 0, 0, 4, 1, 2, 3, 4).map { it.toByte() })
        }.toByteArray()

        assertEquals(3, response[13].toInt() and 0xff)
        assertThrows(IllegalArgumentException::class.java) {
            DnsPacket.validateResponse(query, response)
        }
    }

    @Test
    fun rejectsResponseForDifferentQuestionName() {
        val query = DnsPacket.buildQuery("example.com", 42)
        val response = query.copyOf().apply {
            this[2] = 0x81.toByte()
            this[3] = 0x80.toByte()
            this[13] = 'x'.code.toByte() // "xample.com" instead of "example.com"
        }

        assertThrows(IllegalArgumentException::class.java) {
            DnsPacket.validateResponse(query, response)
        }
    }

    @Test
    fun rejectsResponseForDifferentQuestionType() {
        val query = DnsPacket.buildQuery("example.com", 42)
        val response = query.copyOf().apply {
            this[2] = 0x81.toByte()
            this[3] = 0x80.toByte()
            val typeOffset = questionEndOffset(this)
            this[typeOffset + 1] = 28 // AAAA instead of A
        }

        assertThrows(IllegalArgumentException::class.java) {
            DnsPacket.validateResponse(query, response)
        }
    }

    @Test
    fun rejectsEdnsOptRecordOutsideAdditionalSection() {
        val query = DnsPacket.buildQuery("example.com", 42)
        val response = query.copyOfRange(0, query.size - 11).toMutableList().apply {
            this[2] = 0x81.toByte()
            this[3] = 0x80.toByte()
            this[7] = 1 // one answer
            // An OPT RR is not valid in the Answer section.
            addAll(listOf(0, 0, 41, 4, 208, 0, 0, 0, 0, 0, 0).map { it.toByte() })
        }.toByteArray()

        assertThrows(IllegalArgumentException::class.java) {
            DnsPacket.validateResponse(query, response)
        }
    }

    @Test
    fun rejectsMultipleEdnsOptRecords() {
        val query = DnsPacket.buildQuery("example.com", 42)
        val response = query.copyOf().toMutableList().apply {
            this[2] = 0x81.toByte()
            this[3] = 0x80.toByte()
            this[10] = 0
            this[11] = 2 // two Additional records
            addAll(listOf(0, 41, 4, 208, 0, 0, 0, 0, 0, 0).map { it.toByte() })
        }.toByteArray()

        assertThrows(IllegalArgumentException::class.java) {
            DnsPacket.validateResponse(query, response)
        }
    }

    @Test
    fun rejectsEdnsOptRecordWithNonRootOwnerName() {
        val query = DnsPacket.buildQuery("example.com", 42)
        val optOffset = questionEndOffset(query) + 4
        val response = (query.copyOfRange(0, optOffset).toList() +
            listOf(1, 'x'.code, 0, 0, 41, 4, 208, 0, 0, 0, 0, 0, 0).map { it.toByte() })
            .toMutableList().apply {
                this[2] = 0x81.toByte()
                this[3] = 0x80.toByte()
            }.toByteArray()

        assertThrows(IllegalArgumentException::class.java) {
            DnsPacket.validateResponse(query, response)
        }
    }

    @Test
    fun acceptsWellFormedEdnsOption() {
        val query = DnsPacket.buildQuery("example.com", 42)
        val response = query.copyOf().toMutableList().apply {
            this[2] = 0x81.toByte()
            this[3] = 0x80.toByte()
            val optOffset = questionEndOffset(query) + 4
            this[optOffset + 9] = 0
            this[optOffset + 10] = 6 // one option: code 65001, length 2
            addAll(listOf(0xfd, 0xe9, 0, 2, 0x12, 0x34).map { it.toByte() })
        }.toByteArray()

        val result = DnsPacket.validateResponse(query, response)
        assertEquals("NO_ANSWER", result.status)
    }

    @Test
    fun rejectsTruncatedEdnsOptionHeader() {
        val query = DnsPacket.buildQuery("example.com", 42)
        val response = query.copyOf().toMutableList().apply {
            this[2] = 0x81.toByte()
            this[3] = 0x80.toByte()
            val optOffset = questionEndOffset(query) + 4
            this[optOffset + 9] = 0
            this[optOffset + 10] = 3
            addAll(listOf(0, 1, 0).map { it.toByte() })
        }.toByteArray()

        assertThrows(IllegalArgumentException::class.java) {
            DnsPacket.validateResponse(query, response)
        }
    }

    @Test
    fun rejectsEdnsOptionDataLongerThanRemainingRecordData() {
        val query = DnsPacket.buildQuery("example.com", 42)
        val response = query.copyOf().toMutableList().apply {
            this[2] = 0x81.toByte()
            this[3] = 0x80.toByte()
            val optOffset = questionEndOffset(query) + 4
            this[optOffset + 9] = 0
            this[optOffset + 10] = 6
            addAll(listOf(0, 1, 0, 4, 0x12, 0x34).map { it.toByte() })
        }.toByteArray()

        assertThrows(IllegalArgumentException::class.java) {
            DnsPacket.validateResponse(query, response)
        }
    }

    @Test
    fun ignoresAddressAnswerForUnrelatedOwnerName() {
        val query = DnsPacket.buildQuery("example.com", 42)
        val response = query.copyOfRange(0, query.size - 11).toMutableList().apply {
            this[2] = 0x81.toByte()
            this[3] = 0x80.toByte()
            this[7] = 1
            this[11] = 0
            // A valid A record, but for other.com rather than example.com.
            addAll(listOf(5, 'o'.code, 't'.code, 'h'.code, 'e'.code, 'r'.code,
                3, 'c'.code, 'o'.code, 'm'.code, 0,
                0, 1, 0, 1, 0, 0, 0, 0, 0, 4, 1, 2, 3, 4).map { it.toByte() })
        }.toByteArray()

        val result = DnsPacket.validateResponse(query, response)
        assertEquals("NO_ANSWER", result.status)
    }

    @Test
    fun acceptsAddressAnswerAtCnameTarget() {
        val query = DnsPacket.buildQuery("example.com", 42)
        val response = query.copyOfRange(0, query.size - 11).toMutableList().apply {
            this[2] = 0x81.toByte()
            this[3] = 0x80.toByte()
            this[7] = 2
            this[11] = 0
            // example.com CNAME alias.example.net.
            addAll(listOf(0xc0, 0x0c, 0, 5, 0, 1, 0, 0, 0, 0, 0, 19).map { it.toByte() })
            addAll(listOf(5, 'a'.code, 'l'.code, 'i'.code, 'a'.code, 's'.code,
                7, 'e'.code, 'x'.code, 'a'.code, 'm'.code, 'p'.code, 'l'.code, 'e'.code,
                3, 'n'.code, 'e'.code, 't'.code, 0).map { it.toByte() })
            // The A record belongs to the CNAME target.
            addAll(listOf(0xc0, 0x29, 0, 1, 0, 1, 0, 0, 0, 0, 0, 4, 8, 8, 8, 8).map { it.toByte() })
        }.toByteArray()

        val result = DnsPacket.validateResponse(query, response)
        assertEquals("PASS", result.status)
    }

    @Test
    fun doesNotTreatCnameWithoutRequestedAddressAsSuccessfulResolution() {
        val query = DnsPacket.buildQuery("example.com", 42)
        val response = query.copyOfRange(0, query.size - 11).toMutableList().apply {
            this[2] = 0x81.toByte()
            this[3] = 0x80.toByte()
            this[7] = 1
            this[11] = 0
            // example.com -> alias.example.net, but no A record follows.
            addAll(listOf(0xc0, 0x0c, 0, 5, 0, 1, 0, 0, 0, 0, 0, 19).map { it.toByte() })
            addAll(listOf(5, 'a'.code, 'l'.code, 'i'.code, 'a'.code, 's'.code,
                7, 'e'.code, 'x'.code, 'a'.code, 'm'.code, 'p'.code, 'l'.code, 'e'.code,
                3, 'n'.code, 'e'.code, 't'.code, 0).map { it.toByte() })
        }.toByteArray()

        val result = DnsPacket.validateResponse(query, response)
        assertEquals("NO_ANSWER", result.status)
    }

    @Test
    fun acceptsAddressAnswerAfterMultiHopCnameChain() {
        val query = DnsPacket.buildQuery("example.com", 42)
        val response = query.copyOfRange(0, query.size - 11).toMutableList().apply {
            this[2] = 0x81.toByte()
            this[3] = 0x80.toByte()
            this[7] = 3 // two CNAME records followed by one A record
            this[11] = 0
            // example.com -> alias.example.net
            addAll(listOf(0xc0, 0x0c, 0, 5, 0, 1, 0, 0, 0, 0, 0, 19).map { it.toByte() })
            addAll(listOf(5, 'a'.code, 'l'.code, 'i'.code, 'a'.code, 's'.code,
                7, 'e'.code, 'x'.code, 'a'.code, 'm'.code, 'p'.code, 'l'.code, 'e'.code,
                3, 'n'.code, 'e'.code, 't'.code, 0).map { it.toByte() })
            // alias.example.net -> edge.example.org; 0x29 is the first target name.
            addAll(listOf(0xc0, 0x29, 0, 5, 0, 1, 0, 0, 0, 0, 0, 18,
                4, 'e'.code, 'd'.code, 'g'.code, 'e'.code, 7, 'e'.code, 'x'.code,
                'a'.code, 'm'.code, 'p'.code, 'l'.code, 'e'.code,
                3, 'o'.code, 'r'.code, 'g'.code, 0).map { it.toByte() })
            // The A record belongs to edge.example.org; 0x48 is the second target name.
            addAll(listOf(0xc0, 0x48, 0, 1, 0, 1, 0, 0, 0, 0, 0, 4, 8, 8, 4, 4).map { it.toByte() })
        }.toByteArray()

        val result = DnsPacket.validateResponse(query, response)
        assertEquals("PASS", result.status)
    }

    @Test
    fun rejectsCnameLoop() {
        val query = DnsPacket.buildQuery("example.com", 42)
        val response = query.copyOfRange(0, query.size - 11).toMutableList().apply {
            this[2] = 0x81.toByte()
            this[3] = 0x80.toByte()
            this[7] = 1
            this[11] = 0
            // example.com points back to itself.
            addAll(listOf(0xc0, 0x0c, 0, 5, 0, 1, 0, 0, 0, 0, 0, 2, 0xc0, 0x0c).map { it.toByte() })
        }.toByteArray()

        assertThrows(IllegalArgumentException::class.java) {
            DnsPacket.validateResponse(query, response)
        }
    }

    @Test
    fun rejectsConflictingCnameTargetsForSameOwner() {
        val query = DnsPacket.buildQuery("example.com", 42)
        val response = query.copyOfRange(0, query.size - 11).toMutableList().apply {
            this[2] = 0x81.toByte()
            this[3] = 0x80.toByte()
            this[7] = 2
            this[11] = 0
            addAll(listOf(0xc0, 0x0c, 0, 5, 0, 1, 0, 0, 0, 0, 0, 2, 0xc0, 0x0c).map { it.toByte() })
            addAll(listOf(0xc0, 0x0c, 0, 5, 0, 1, 0, 0, 0, 0, 0, 11,
                5, 'o'.code, 't'.code, 'h'.code, 'e'.code, 'r'.code, 3, 'c'.code, 'o'.code, 'm'.code, 0).map { it.toByte() })
        }.toByteArray()

        assertThrows(IllegalArgumentException::class.java) {
            DnsPacket.validateResponse(query, response)
        }
    }

    @Test
    fun ignoresCnameAnswerForUnrelatedOwnerName() {
        val query = DnsPacket.buildQuery("example.com", 42)
        val response = query.copyOfRange(0, query.size - 11).toMutableList().apply {
            this[2] = 0x81.toByte()
            this[3] = 0x80.toByte()
            this[7] = 1
            this[11] = 0
            // other.com is an unrelated owner, even though its CNAME data is valid.
            addAll(listOf(5, 'o'.code, 't'.code, 'h'.code, 'e'.code, 'r'.code,
                3, 'c'.code, 'o'.code, 'm'.code, 0,
                0, 5, 0, 1, 0, 0, 0, 0, 0, 19,
                5, 'a'.code, 'l'.code, 'i'.code, 'a'.code, 's'.code,
                7, 'e'.code, 'x'.code, 'a'.code, 'm'.code, 'p'.code, 'l'.code, 'e'.code,
                3, 'n'.code, 'e'.code, 't'.code, 0).map { it.toByte() })
        }.toByteArray()

        val result = DnsPacket.validateResponse(query, response)
        assertEquals("NO_ANSWER", result.status)
    }

    @Test
    fun rejectsMalformedARecordEvenWhenQueryAskedForAaaa() {
        val query = DnsPacket.buildQuery("example.com", 42, queryType = 28)
        val response = responseWithAddressRecord(query, type = 1, data = listOf(1, 2, 3))

        assertThrows(IllegalArgumentException::class.java) {
            DnsPacket.validateResponse(query, response)
        }
    }

    @Test
    fun rejectsMalformedAaaaRecordEvenWhenQueryAskedForA() {
        val query = DnsPacket.buildQuery("example.com", 42, queryType = 1)
        val response = responseWithAddressRecord(query, type = 28, data = List(15) { 0 })

        assertThrows(IllegalArgumentException::class.java) {
            DnsPacket.validateResponse(query, response)
        }
    }

    private fun responseWithAddressRecord(query: ByteArray, type: Int, data: List<Int>): ByteArray {
        return query.copyOfRange(0, query.size - 11).toMutableList().apply {
            this[2] = 0x81.toByte()
            this[3] = 0x80.toByte()
            this[7] = 1
            this[11] = 0
            addAll(listOf(0xc0, 0x0c, 0, type, 0, 1, 0, 0, 0, 0, 0, data.size).map { it.toByte() })
            addAll(data.map { it.toByte() })
        }.toByteArray()
    }

    @Test
    fun rejectsARecordWithInvalidAddressLength() {
        val query = DnsPacket.buildQuery("example.com", 42)
        val response = query.copyOfRange(0, query.size - 11).toMutableList().apply {
            this[2] = 0x81.toByte()
            this[3] = 0x80.toByte()
            this[7] = 1
            this[11] = 0
            addAll(listOf(0xc0, 0x0c, 0, 1, 0, 1, 0, 0, 0, 0, 0, 3, 1, 2, 3).map { it.toByte() })
        }.toByteArray()

        assertThrows(IllegalArgumentException::class.java) {
            DnsPacket.validateResponse(query, response)
        }
    }

    @Test
    fun rejectsCnameWithMalformedTargetName() {
        val query = DnsPacket.buildQuery("example.com", 42)
        val response = query.copyOfRange(0, query.size - 11).toMutableList().apply {
            this[2] = 0x81.toByte()
            this[3] = 0x80.toByte()
            this[7] = 1
            this[11] = 0
            addAll(listOf(0xc0, 0x0c, 0, 5, 0, 1, 0, 0, 0, 0, 0, 2, 0xc0, 0xff).map { it.toByte() })
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
