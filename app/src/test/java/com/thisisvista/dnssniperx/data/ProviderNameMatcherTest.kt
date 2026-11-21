package com.thisisvista.dnssniperx.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderNameMatcherTest {
    private val providers = listOf(
        ProviderEntity("mci", "همراه اول", "MOBILE", aliases = "MCI;Hamrah-e Aval;Mobile Communications Company of Iran"),
        ProviderEntity("irancell", "ایرانسل", "MOBILE", aliases = "MTN Irancell;Irancell"),
        ProviderEntity("rightel", "رایتل", "MOBILE", aliases = "RighTel")
    )

    @Test fun matchesIrancellOperatorName() {
        assertEquals("irancell", ProviderNameMatcher.match("MTN Irancell", providers)?.providerId)
    }

    @Test fun matchesMciOperatorName() {
        assertEquals("mci", ProviderNameMatcher.match("MCI", providers)?.providerId)
    }

    @Test fun matchesPersianDisplayName() {
        assertEquals("mci", ProviderNameMatcher.match("همراه اول", providers)?.providerId)
    }

    @Test fun unknownOrBlankOperatorDoesNotGuess() {
        assertNull(ProviderNameMatcher.match("Unknown Network", providers))
        assertNull(ProviderNameMatcher.match(null, providers))
    }
}
