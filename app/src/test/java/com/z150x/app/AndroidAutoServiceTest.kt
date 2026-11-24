package com.z150x.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAutoServiceTest {
    @Test
    fun bikeTransportIsLiveForApOrP2p() {
        assertFalse(AndroidAutoService.bikeTransportConnected(false, false))
        assertTrue(AndroidAutoService.bikeTransportConnected(true, false))
        assertTrue(AndroidAutoService.bikeTransportConnected(false, true))
        assertTrue(AndroidAutoService.bikeTransportConnected(true, true))
        // 125M: the dash is on the phone's hotspot (no AP, no P2P) but frames are flowing.
        assertTrue(AndroidAutoService.bikeTransportConnected(false, false, liveLink = true))
    }

    @Test
    fun aaRetriggerOnlyWhileDashPullsFrames() {
        // Static screen: the idle redraw sends a frame every ~2 s.
        assertTrue(AndroidAutoService.dashStillPulling(true, 2_000L))
        // Bike switched off: no frame since the drop plus the settle delay.
        assertFalse(AndroidAutoService.dashStillPulling(true, 6_000L))
        assertFalse(AndroidAutoService.dashStillPulling(true, Long.MAX_VALUE))
        assertFalse(AndroidAutoService.dashStillPulling(false, 100L))
    }

    @Test
    fun resumeAfterProcessDeathNeedsAFreshHeartbeat() {
        val now = 1_000_000_000L
        assertTrue(AndroidAutoService.heartbeatIsFresh(now - 30_000L, now))
        assertTrue(AndroidAutoService.heartbeatIsFresh(now - AndroidAutoService.HEARTBEAT_FRESH_MS, now))
        // No ride was on, or it ended long ago.
        assertFalse(AndroidAutoService.heartbeatIsFresh(0L, now))
        assertFalse(AndroidAutoService.heartbeatIsFresh(now - AndroidAutoService.HEARTBEAT_FRESH_MS - 1, now))
        // The clock was set back since: not evidence of a live ride.
        assertFalse(AndroidAutoService.heartbeatIsFresh(now + 5_000L, now))
    }
}
