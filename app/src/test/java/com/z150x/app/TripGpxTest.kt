package com.z150x.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TripGpxTest {
    @Test
    fun writesGpxTrack() {
        val trip = Trip(
            id = "1",
            start = 1_700_000_000_000L,
            end = 1_700_000_360_000L,
            distanceMeters = 12_500.0,
            movingTimeMs = 360_000L,
            maxSpeedMs = 25f,
            points = listOf(
                TrackPoint(45.0, 25.0, 1_700_000_000_000L, 10f),
                TrackPoint(45.01, 25.01, 1_700_000_180_000L, 12f),
            ),
        )
        val xml = TripGpx.toXml(trip)
        assertTrue(xml.contains("<gpx"))
        assertTrue(xml.contains("trkpt lat=\"45.000000\""))
        assertTrue(xml.contains("<time>"))

        assertTrue(xml.contains("trkpt lat=\"45.010000\""))
        assertTrue(TripGpx.suggestedFileName(trip).endsWith(".gpx"))

        // Files riders share carry this app's name, not the upstream project's.
        assertTrue(xml.contains("creator=\"Z150X\""))
        assertTrue(xml.contains("<name>Z150X "))
        assertTrue(TripGpx.suggestedFileName(trip).startsWith("z150x_trip_"))
        assertFalse(xml.contains("OpenCfMoto", ignoreCase = true))
        assertFalse(TripGpx.suggestedFileName(trip).contains("opencfmoto", ignoreCase = true))
    }
}
