package com.thisisvista.dnssniperx.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolverListParserTest {
    @Test fun parsesIpv4AndCustomPort() {
        val result = ResolverListParser.parse("1.1.1.1\n8.8.8.8:5353")
        assertEquals(2, result.size)
        assertEquals(53, result[0].port)
        assertEquals(5353, result[1].port)
    }

    @Test fun parsesBracketedIpv6AndHostname() {
        val result = ResolverListParser.parse("[2001:db8::1]:5353\nresolver.example")
        assertEquals(2, result.size)
        assertEquals(5353, result[0].port)
        assertEquals("resolver.example", result[1].address)
    }

    @Test fun ignoresCommentsInvalidEntriesAndDuplicates() {
        val result = ResolverListParser.parse("# public list\n1.1.1.1\n1.1.1.1\n999.1.1.1\nnot a server")
        assertEquals(1, result.size)
        assertTrue(result.all { it.status == "NOT_TESTED" })
    }
}
