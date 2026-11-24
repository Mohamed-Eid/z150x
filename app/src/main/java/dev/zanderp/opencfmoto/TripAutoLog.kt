
package dev.zanderp.opencfmoto

import android.content.Context
import android.os.SystemClock

/**
 * Keeps the shared [TripRecorder] aligned with the Android Auto projection ([Phase.STREAMING]).
 * Honours Setup ▸ *Log trips automatically*.
 *
 * A short link drop mid-ride (Wi-Fi hiccup, the watchdog forcing a reconnect) does not end the
 * recording: GPS stays on for up to [DROP_GRACE_MS], the same tolerance as [RideSession], so one
 * ride stays one trip and the distance ridden during the drop is kept. See [shouldRecord].
 */
object TripAutoLog {
    /** How long the projection may be down before the ride is banked (same as [RideSession]). */
    internal const val DROP_GRACE_MS = 45_000L

    /** elapsedRealtime of the first sync that found a recording ride without projection; 0 = none. */
    private var droppedSince = 0L

    /**
     * Whether the recorder should be running. Pure, so it can be unit-tested.
     *
     *  - Projecting → record (the only state that starts a recording).
     *  - Not recording → stay stopped.
     *  - Recording, but the AA service is gone, or the phase says the ride is over on purpose
     *    ([Phase.STOPPED] / [Phase.IDLE]: the rider tapped Stop or the app gave up;
     *    [Phase.WAITING_FOR_BIKE]: the bike's Wi-Fi has already been away for minutes;
     *    [Phase.MIRRORING]: not an Android Auto ride) → stop now.
     *  - Recording while the link is being re-established ([Phase.RECONNECTING], the connect steps
     *    a reconnect goes through, or [Phase.ERROR], which the watchdog re-arms) → keep recording
     *    as long as the drop has lasted no more than [DROP_GRACE_MS], then stop.
     */
    internal fun shouldRecord(phase: Phase, recording: Boolean, serviceRunning: Boolean, droppedForMs: Long): Boolean {
        if (phase == Phase.STREAMING) return true
        if (!recording || !serviceRunning) return false
        return when (phase) {
            Phase.STOPPED, Phase.IDLE, Phase.WAITING_FOR_BIKE, Phase.MIRRORING -> false
            else -> droppedForMs <= DROP_GRACE_MS
        }
    }

    /**
     * Start recording while projecting; stop+save when not (after the grace above). Safe to call
     * often (AA watchdog tick, session teardown).
     */
    @Synchronized
    fun sync(context: Context) {
        val app = context.applicationContext
        if (!AppSettings.logTrips(app)) {
            droppedSince = 0L
            val rec = TripLogger.current
            if (rec != null && rec.recording) rec.stopAndSave()
            return
        }
        val phase = ConnectionState.phase
        val rec = TripLogger.get(app)
        val now = SystemClock.elapsedRealtime()
        val before = droppedSince
        if (phase == Phase.STREAMING || !rec.recording) droppedSince = 0L
        else if (before == 0L) droppedSince = now
        val droppedFor = if (droppedSince == 0L) 0L else now - droppedSince

        if (shouldRecord(phase, rec.recording, AndroidAutoService.isRunning, droppedFor)) {
            when {
                !rec.recording -> if (rec.start()) LogBus.log("[trip] auto-logging this ride (AA)")
                before != 0L && droppedSince == 0L ->
                    LogBus.log("[trip] projection back after ${(now - before) / 1000} s — same ride, still recording")
                before == 0L && droppedSince != 0L ->
                    LogBus.log("[trip] projection dropped (${phase.logLabel}) — still recording for up to ${DROP_GRACE_MS / 1000} s")
            }
        } else if (rec.recording) {
            if (droppedFor > DROP_GRACE_MS) {
                LogBus.log("[trip] projection down for more than ${DROP_GRACE_MS / 1000} s (${phase.logLabel}) — ending this ride")
            }
            droppedSince = 0L
            rec.stopAndSave()
        }
    }
}
