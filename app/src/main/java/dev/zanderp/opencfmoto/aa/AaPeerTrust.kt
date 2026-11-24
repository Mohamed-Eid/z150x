package dev.zanderp.opencfmoto.aa

import android.content.Context
import java.security.MessageDigest
import java.security.cert.Certificate

/**
 * Who is on the other end of the Android Auto link?
 *
 * The link is a loopback TCP socket and its TLS accepts any certificate (the head unit side has no
 * Google root to check against), so "it came from 127.0.0.1" was the only test. Any app on the phone
 * with the INTERNET permission could therefore play Android Auto, ask for the microphone and get the
 * rider's voice through this app's RECORD_AUDIO grant.
 *
 * This pins the certificate Android Auto presents, trust-on-first-use: the public key at the top of
 * its chain is remembered on the first session and compared on every later one. Android Auto's
 * certificate ships inside its APK, so it may change when Android Auto is updated; a change that
 * comes together with a new Android Auto install time is accepted and re-pinned. A change without
 * one is not Android Auto: the session still runs (so a wrong guess here can never break the dash),
 * but the microphone stays closed until the rider allows it from the notification.
 */
object AaPeerTrust {
    private const val PREFS = "aa_peer"
    private const val KEY_HASH = "key_sha256"
    private const val KEY_UPDATED = "gearhead_updated"
    private const val GEARHEAD_PKG = "com.google.android.projection.gearhead"

    enum class Decision { PIN_FIRST, MATCH, REPIN_AFTER_UPDATE, MISMATCH, NO_CERTIFICATE }

    /** False while the live session's peer is not the pinned Android Auto. Read by [AaMicrophone]. */
    @Volatile var micAllowed = true
        private set

    /** The key a refused session presented, kept so the rider can accept it. */
    @Volatile private var pendingHash: String? = null

    /** SHA-256 (hex) of the public key of the last certificate in [chain], the one closest to the root. */
    fun keyHash(chain: Array<out Certificate>?): String? = try {
        chain?.lastOrNull()?.publicKey?.encoded?.let { key ->
            MessageDigest.getInstance("SHA-256").digest(key).joinToString("") { "%02x".format(it) }
        }
    } catch (_: Exception) { null }

    /** Pure decision, unit-tested. [gearheadUpdated] is Android Auto's last install/update time. */
    internal fun decide(pinnedHash: String?, pinnedUpdated: Long, hash: String?, gearheadUpdated: Long): Decision = when {
        hash == null -> Decision.NO_CERTIFICATE
        pinnedHash == null -> Decision.PIN_FIRST
        pinnedHash == hash -> Decision.MATCH
        gearheadUpdated != 0L && gearheadUpdated != pinnedUpdated -> Decision.REPIN_AFTER_UPDATE
        else -> Decision.MISMATCH
    }

    /**
     * Call once per session, right after the TLS handshake.
     * @return true if the microphone may be opened for this session.
     */
    fun onSession(ctx: Context, hash: String?, log: (String) -> Unit): Boolean {
        val allowed = try {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val pinned = prefs.getString(KEY_HASH, null)
            val updated = gearheadUpdated(ctx)
            val short = hash?.take(12) ?: "none"
            when (decide(pinned, prefs.getLong(KEY_UPDATED, 0L), hash, updated)) {
                Decision.PIN_FIRST -> {
                    prefs.edit().putString(KEY_HASH, hash).putLong(KEY_UPDATED, updated).apply()
                    log("[AA] peer certificate $short… remembered as Android Auto (first session)")
                    true
                }
                Decision.MATCH -> {
                    if (prefs.getLong(KEY_UPDATED, 0L) != updated) prefs.edit().putLong(KEY_UPDATED, updated).apply()
                    true
                }
                Decision.REPIN_AFTER_UPDATE -> {
                    prefs.edit().putString(KEY_HASH, hash).putLong(KEY_UPDATED, updated).apply()
                    log("[AA] peer certificate changed to $short… after an Android Auto update — accepted")
                    true
                }
                Decision.MISMATCH -> {
                    pendingHash = hash
                    log("[AA] !! peer certificate $short… is not the one Android Auto used before (${pinned?.take(12)}…) and Android Auto was not updated — microphone blocked for this session")
                    false
                }
                Decision.NO_CERTIFICATE -> {
                    // Nothing to compare. With no pin yet there is nothing to protect either; with
                    // one, a peer that shows no certificate is not the Android Auto we know.
                    log("[AA] peer showed no certificate${if (pinned != null) " — microphone blocked for this session" else ""}")
                    pinned == null
                }
            }
        } catch (e: Exception) {
            log("[AA] peer check failed ($e) — microphone left on")
            true
        }
        micAllowed = allowed
        return allowed
    }

    /** The rider tapped "Allow" on the notification: accept the key of the refused session. */
    fun trustPending(ctx: Context, log: (String) -> Unit) {
        val hash = pendingHash ?: return
        pendingHash = null
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_HASH, hash).putLong(KEY_UPDATED, gearheadUpdated(ctx)).apply()
        } catch (_: Exception) {}
        micAllowed = true
        log("[AA] rider allowed the new Android Auto certificate ${hash.take(12)}… — microphone on")
    }

    /** A session ended: the next one is judged afresh. */
    fun onSessionEnded() {
        micAllowed = true
    }

    private fun gearheadUpdated(ctx: Context): Long = try {
        ctx.packageManager.getPackageInfo(GEARHEAD_PKG, 0).lastUpdateTime
    } catch (_: Exception) { 0L }
}
