package com.z150x.app

import com.z150x.app.aa.AaPeerTrust
import com.z150x.app.aa.AaPeerTrust.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class AaPeerTrustTest {
    @Test fun firstSessionPinsTheKey() {
        assertEquals(Decision.PIN_FIRST, AaPeerTrust.decide(null, 0L, "aa", 100L))
    }

    @Test fun sameKeyMatches() {
        assertEquals(Decision.MATCH, AaPeerTrust.decide("aa", 100L, "aa", 100L))
        // An Android Auto update that keeps the certificate is still a match.
        assertEquals(Decision.MATCH, AaPeerTrust.decide("aa", 100L, "aa", 200L))
    }

    @Test fun newKeyAfterAnAndroidAutoUpdateIsAccepted() {
        assertEquals(Decision.REPIN_AFTER_UPDATE, AaPeerTrust.decide("aa", 100L, "bb", 200L))
    }

    @Test fun newKeyWithoutAnUpdateIsRefused() {
        assertEquals(Decision.MISMATCH, AaPeerTrust.decide("aa", 100L, "bb", 100L))
        // Android Auto's install time can't be read: don't treat that as "updated".
        assertEquals(Decision.MISMATCH, AaPeerTrust.decide("aa", 100L, "bb", 0L))
    }

    @Test fun noCertificate() {
        assertEquals(Decision.NO_CERTIFICATE, AaPeerTrust.decide("aa", 100L, null, 100L))
        assertEquals(Decision.NO_CERTIFICATE, AaPeerTrust.decide(null, 0L, null, 100L))
    }
}
