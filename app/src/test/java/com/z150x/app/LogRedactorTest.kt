package com.z150x.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogRedactorTest {
    @Test
    fun redactsUrlPassword() {
        val out = LogRedactor.redact("qr ssid=CFMOTO-x&pwd=supersecret&modelid=1")
        assertTrue(out.contains("pwd=«redacted»"))
        assertFalse(out.contains("supersecret"))
        assertTrue(out.contains("ssid=CFMOTO-x"))
    }

    @Test
    fun masksSerialTail() {
        val out = LogRedactor.redact("""{"HUID":"CRCP230501740"}""")
        assertTrue(out.contains("CRCP"))
        assertFalse(out.contains("CRCP230501740"))
    }

    @Test
    fun masksHardwareAddresses() {
        val out = LogRedactor.redact("[BT-TRIG] a paired device (DC:0D:30:85:10:66) connected")
        assertTrue(out.contains("DC:0D:30:••:••:66"))
        assertFalse(out.contains("85:10"))
        // A clock time is not an address.
        assertTrue(LogRedactor.redact("18:17:31.403 hb#6").contains("18:17:31.403"))
    }
}
