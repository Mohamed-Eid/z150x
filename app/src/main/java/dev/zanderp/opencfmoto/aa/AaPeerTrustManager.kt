// Replaces headunit-revived's ssl/NoCheckTrustManager.kt (AGPLv3), which accepted anything.
package dev.zanderp.opencfmoto.aa

import java.security.PublicKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * Judges the certificate Android Auto presents in the AAP TLS handshake.
 *
 * Android Auto's certificate comes from Google's private "Automotive Link" authority, whose root is
 * not available to a head unit app, so the chain cannot be validated against a trust store. What is
 * known at handshake time is checked here, and a peer that fails is refused before any data flows:
 *
 *  - it must present a certificate at all;
 *  - that certificate must not be the head unit's own ([ownKey]). The head-unit key ships in every
 *    open-source head unit, so anyone can present it; Android Auto never does.
 *
 * Whether the peer is the *same* Android Auto as before is judged after the handshake by
 * [AaPeerTrust], which pins its key on first use and keeps the microphone closed on a mismatch.
 *
 * This app is always the TLS client on this link, so it never accepts a client certificate.
 */
class AaPeerTrustManager(private val ownKey: PublicKey?) : X509TrustManager {

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("the head unit side does not accept TLS clients")
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        if (chain.isNullOrEmpty()) throw CertificateException("Android Auto presented no certificate")
        val own = ownKey?.encoded
        if (own != null && chain.any { it.publicKey.encoded.contentEquals(own) }) {
            throw CertificateException("peer presented the head unit's own certificate — not Android Auto")
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
