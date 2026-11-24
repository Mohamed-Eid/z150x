
package com.z150x.app

import android.app.Application
import android.app.NotificationManager
import androidx.annotation.StringRes
import java.io.File

/** Process entry — installs crash capture before any Activity runs. */
class Z150XApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        try {
            LogBus.includeSecrets = AppSettings.includeSecretsInLogs(this)
        } catch (_: Exception) {
        }
        CrashGuard.install(this)
        SearchKeyGuard.install(this)
        CrashGuard.hydrateLogBus(this)
        try {
            AppSettings.applyToHolder(this)
            RideStop.install()
        } catch (_: Exception) {
        }
        // After hydrate so Share Logs still show prior crash, then stamp this process build.
        LogBus.logSessionBanner()
        try { LastExit.check(this) } catch (_: Exception) {}
        try { TripRecorder.recoverAtStart(this) } catch (_: Exception) {}
        AppHttp.init(this)
        ConnectSound.init(this)
        ConnectWidget.init(this)
        try { cleanUpRemovedCrashAlert() } catch (_: Exception) {}
    }

    /**
     * One-time cleanup for installs upgraded from a version that still had the crash alert (impact
     * detector, SMS to emergency contacts, "black box"). That feature is gone, so what it stored goes
     * too: the contacts' names and phone numbers, the impact recordings (they hold GPS positions) and
     * the alarm's notification channel. Runs off the main thread, never throws, and is tried again on
     * the next start until every step has succeeded. Safe on an install that never had any of it.
     */
    private fun cleanUpRemovedCrashAlert() {
        Thread({
            try {
                val state = getSharedPreferences(CLEANUP_PREFS, MODE_PRIVATE)
                if (state.getBoolean(KEY_CRASH_ALERT_CLEANED, false)) return@Thread
                var ok = true
                // Detector on/off, rider name and the emergency contacts.
                try { if (!deleteSharedPreferences("crash_detector")) ok = false } catch (_: Exception) { ok = false }
                // Black-box recordings, and the GPX copies its "Share report" left in the cache.
                for (dir in listOf(File(filesDir, "blackbox"), File(cacheDir, "share"))) {
                    try { if (!dir.deleteRecursively()) ok = false } catch (_: Exception) { ok = false }
                }
                // The alarm's notification channel (this also clears a notification still posted on it).
                try {
                    getSystemService(NotificationManager::class.java)?.deleteNotificationChannel("crash_alert")
                } catch (_: Exception) { ok = false }
                if (ok) {
                    state.edit().putBoolean(KEY_CRASH_ALERT_CLEANED, true).apply()
                    LogBus.log("[APP] data of the removed crash alert cleaned up")
                }
            } catch (_: Exception) {
            }
        }, "crash-alert-cleanup").apply { isDaemon = true }.start()
    }

    companion object {
        private const val CLEANUP_PREFS = "upgrade_cleanup"
        private const val KEY_CRASH_ALERT_CLEANED = "crash_alert_removed"

        @Volatile private var instance: Application? = null

        /**
         * Localized string for code with no Context at hand (process-global state, background
         * helpers). The Application is created before anything else runs.
         */
        fun str(@StringRes id: Int, vararg args: Any): String {
            val app = instance ?: return ""
            return if (args.isEmpty()) app.getString(id) else app.getString(id, *args)
        }
    }
}
