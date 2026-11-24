package dev.zanderp.opencfmoto

import dev.zanderp.opencfmoto.aa.AaPeerTrustManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

class AaPeerTrustManagerTest {
    private fun cert(pem: String): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(pem.byteInputStream()) as X509Certificate

    /** The head unit certificate the app ships (unit tests run in the module directory). */
    private val headUnit = cert(File("src/main/res/raw/aa_cert").readText())

    /** Some other party's certificate: self-signed, made only for this test. */
    private val other = cert(OTHER_PEM)

    private val trust = AaPeerTrustManager(headUnit.publicKey)

    @Test fun acceptsAPeerWithItsOwnCertificate() {
        trust.checkServerTrusted(arrayOf(other), "RSA")
    }

    @Test fun refusesAPeerWithNoCertificate() {
        assertThrows(CertificateException::class.java) { trust.checkServerTrusted(null, "RSA") }
        assertThrows(CertificateException::class.java) { trust.checkServerTrusted(emptyArray(), "RSA") }
    }

    @Test fun refusesAPeerPresentingTheHeadUnitCertificate() {
        assertThrows(CertificateException::class.java) { trust.checkServerTrusted(arrayOf(headUnit), "RSA") }
        // Anywhere in the chain, not only first.
        assertThrows(CertificateException::class.java) { trust.checkServerTrusted(arrayOf(other, headUnit), "RSA") }
    }

    @Test fun neverAcceptsATlsClient() {
        assertThrows(CertificateException::class.java) { trust.checkClientTrusted(arrayOf(other), "RSA") }
    }

    @Test fun advertisesNoIssuers() {
        assertEquals(0, trust.acceptedIssuers.size)
    }

    private companion object {
        val OTHER_PEM = """
-----BEGIN CERTIFICATE-----
MIIC6jCCAdKgAwIBAgIUSQ9g6c8pM2bO2JchvLm5w4yeofAwDQYJKoZIhvcNAQEL
BQAwFDESMBAGA1UECgwJVGVzdCBwZWVyMCAXDTI2MTEyNDE5MDQxMVoYDzIxMjYx
MDMxMTkwNDExWjAUMRIwEAYDVQQKDAlUZXN0IHBlZXIwggEiMA0GCSqGSIb3DQEB
AQUAA4IBDwAwggEKAoIBAQDlI7a/pHGG8EX3sHuQxwChgZ2snff2GmP9MYbnjx7G
34dOWdivQDV/7TsYmC7xUHud2N8t0UzAipu8McsIivBrHOoRzFJyp+YYzvECE2kW
MuMbtzZ6WIUt71jwFElkwQDVPp8fHJ2eAPS2uvtREwuRgnt5d4vWjRdA2Bi5OpcI
jHoOpKMIW3PuBpsqh12IeZE3J11oSFc3Oo1BNmktjiGpGectlgYh91FH++bAOu13
5FXTzRVnen+Ur3dCNlArqKTEfWnPjnjNOz5geaVW4Wbi+TfZbq9hdbfJBrso3EWV
R/fXSKvbKskKseLWh5EjiKfldV9fDolCZzR7lc52BTf1AgMBAAGjMjAwMB0GA1Ud
DgQWBBQJlmqbdFLBhE0MJpgfxrILPUHQuTAPBgNVHRMBAf8EBTADAQH/MA0GCSqG
SIb3DQEBCwUAA4IBAQA+kNBDxfuVamUvpJcTHwdqDlGbLEOCYWqTSHaBMfA5engm
soBjAHdQWHOSZDtFbN3DzFpIjCHvOuAd3jKXCBTF/mdFTcg/SoBJsyKKL1VKOTTP
QFHoA/12m/9hLYbj2KAiw7tRrbxfHybqxABiddvt3pCdrqgWVddcRyokfVixT/m6
+/6hCRSW2fru9ySRtGkWEL5gOYdjJ6GpDLsfQKFB7m72LV9VMc50qA9z/jJkehnP
qWC4lCWYpdNS3PdrSjlBjJhAS/rDs6BX9F55c+ICYdNPwEYDOwSp0SfOckiP/Adx
SzwP2dmPbCgIR3Hb0x2cKSW13lGb5xd/ljWgOtwu
-----END CERTIFICATE-----
        """.trimIndent()
    }
}
