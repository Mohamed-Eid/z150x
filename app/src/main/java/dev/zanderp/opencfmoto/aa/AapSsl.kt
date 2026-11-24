// Ported from headunit-revived (AGPLv3): aap/AapSsl.kt
package dev.zanderp.opencfmoto.aa

interface AapSsl {
    fun decrypt(start: Int, length: Int, buffer: ByteArray): ByteArrayWithLimit?
    fun encrypt(offset: Int, length: Int, buffer: ByteArray): ByteArrayWithLimit?
    fun postHandshakeReset()
    fun performHandshake(connection: AccessoryConnection): Boolean
    fun release()

    /** SHA-256 of the public key at the top of the peer's certificate chain; null before the handshake. */
    val peerKeyHash: String? get() = null
}
