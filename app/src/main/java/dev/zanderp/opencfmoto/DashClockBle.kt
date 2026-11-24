
package dev.zanderp.opencfmoto

import android.content.Context

/** Process-wide holder for the opt-in BLE dash-clock link. */
object DashClockBle {
    @Volatile private var link: EcBtpTimeLink? = null

    fun start(context: Context, log: (String) -> Unit = LogBus::log) {
        stop()
        if (!AppSettings.bluetoothClockSync(context)) return
        val next = EcBtpTimeLink(context, log)
        link = next
        runCatching { next.start() }
            .onFailure {
                log("[EC-BTP] start failed: $it")
                stop()
            }
    }

    fun stop() {
        link?.let { runCatching { it.close() } }
        link = null
    }
}
