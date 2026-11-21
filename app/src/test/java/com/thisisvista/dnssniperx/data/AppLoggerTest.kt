package com.thisisvista.dnssniperx.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLoggerTest {
    @Test fun redactsSecretAssignments() {
        val safe = AppLogger.sanitize("password=hunter2 token:abc123 private_key=verysecret")
        assertFalse(safe.contains("hunter2"))
        assertFalse(safe.contains("abc123"))
        assertFalse(safe.contains("verysecret"))
        assertTrue(safe.contains("[REDACTED]"))
    }

    @Test fun redactsUrlCredentialsAndQueryParameters() {
        val safe = AppLogger.sanitize("https://alice:password@example.test/path?token=secret")
        assertFalse(safe.contains("alice"))
        assertFalse(safe.contains("password"))
        assertFalse(safe.contains("token=secret"))
        assertTrue(safe.contains("[REDACTED]"))
    }

    @Test fun preservesOrdinaryDiagnosticText() {
        val safe = AppLogger.sanitize("Socket timeout while connecting to resolver 1.1.1.1")
        assertTrue(safe.contains("Socket timeout"))
        assertTrue(safe.contains("1.1.1.1"))
    }
}
