package dev.zanderp.opencfmoto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the ride recorder keeps running across a link drop, and when it banks the ride. */
class TripAutoLogTest {
    private val grace = TripAutoLog.DROP_GRACE_MS

    private fun keep(phase: Phase, recording: Boolean = true, service: Boolean = true, droppedMs: Long = 5_000L) =
        TripAutoLog.shouldRecord(phase, recording, service, droppedMs)

    @Test fun sameGraceAsTheRideSession() {
        assertTrue(grace == 45_000L)
    }

    @Test fun projectingRecords() {
        assertTrue(keep(Phase.STREAMING, recording = false, droppedMs = 0L))
        assertTrue(keep(Phase.STREAMING, recording = true, droppedMs = 0L))
    }

    @Test fun onlyProjectingStartsARecording() {
        for (p in Phase.entries) if (p != Phase.STREAMING) {
            assertFalse("$p must not start a recording", keep(p, recording = false, droppedMs = 0L))
        }
    }

    @Test fun shortReconnectKeepsTheSameRide() {
        // The phases a Wi-Fi hiccup or a forced reconnect passes through on its way back to STREAMING.
        val hiccup = listOf(
            Phase.RECONNECTING, Phase.PXC_CONNECTING, Phase.JOINING_WIFI,
            Phase.STARTING_AA, Phase.AA_VIDEO_LIVE, Phase.ERROR,
        )
        for (p in hiccup) {
            assertTrue("$p at 0 s", keep(p, droppedMs = 0L))
            assertTrue("$p at 20 s", keep(p, droppedMs = 20_000L))
            assertTrue("$p at the limit", keep(p, droppedMs = grace))
        }
    }

    @Test fun dropLongerThanTheGraceEndsTheRide() {
        for (p in listOf(Phase.RECONNECTING, Phase.PXC_CONNECTING, Phase.ERROR)) {
            assertFalse("$p past the limit", keep(p, droppedMs = grace + 1))
            assertFalse("$p after minutes", keep(p, droppedMs = 10 * 60_000L))
        }
    }

    @Test fun riderStopEndsTheRideAtOnce() {
        // Stop button / give-up: STOPPED, and the service goes away right after.
        assertFalse(keep(Phase.STOPPED, droppedMs = 0L))
        assertFalse(keep(Phase.IDLE, droppedMs = 0L))
        // Service destroyed while the phase still looks like a reconnect: nobody is reconnecting.
        assertFalse(keep(Phase.RECONNECTING, service = false, droppedMs = 0L))
        assertFalse(keep(Phase.ERROR, service = false, droppedMs = 1_000L))
    }

    @Test fun bikeGoneEndsTheRideAtOnce() {
        // WAITING_FOR_BIKE is only set after the bike's Wi-Fi has been away for minutes.
        assertFalse(keep(Phase.WAITING_FOR_BIKE, droppedMs = 0L))
        assertFalse(keep(Phase.MIRRORING, droppedMs = 0L))
    }
}
