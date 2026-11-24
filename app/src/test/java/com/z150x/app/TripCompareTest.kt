package com.z150x.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripCompareTest {

    /** A straight 5 km ride north at [kmh], with [stopS] seconds stood still halfway. */
    private fun ride(id: String, start: Long, kmh: Double = 30.0, stopS: Int = 60, km: Double = 5.0): Trip {
        val pts = ArrayList<TrackPoint>()
        val ms = kmh / 3.6
        val movingS = (km * 1000 / ms).toInt()
        var t = start; var lat = 41.38
        val dLat = ms / 111_000.0
        for (s in 0 until movingS) {
            if (s == movingS / 2) repeat(stopS) { pts += TrackPoint(lat, 2.15, t, 0f); t += 1000 }
            pts += TrackPoint(lat, 2.15, t, ms.toFloat()); lat += dLat; t += 1000
        }
        return Trip(id, start, t, km * 1000, movingS * 1000L, ms.toFloat(), pts)
    }

    private fun usual() = (1..4).map { ride("u$it", it * 86_400_000L) }

    @Test fun needsThreeSimilarRides() {
        val today = ride("t", 10 * 86_400_000L)
        assertNull(TripCompare.compare(today, usual().take(2)))
        assertNotNull(TripCompare.compare(today, usual()))
    }

    @Test fun blamesTheStops() {
        val r = TripCompare.compare(ride("t", 10 * 86_400_000L, stopS = 420), usual())!!
        assertTrue("diff ${r.diffS}", r.diffS in 330..390)
        assertEquals(TripCompare.Factor.STILL, r.main)
    }

    @Test fun blamesThePace() {
        val r = TripCompare.compare(ride("t", 10 * 86_400_000L, kmh = 20.0), usual())!!
        assertTrue("diff ${r.diffS}", r.diffS > 250)
        assertEquals(TripCompare.Factor.PACE, r.main)
    }

    /** Opening one ride's map parses only the rides that can be its twins, picked from the index. */
    @Test fun candidatesFromSummariesGiveTheSameComparison() {
        val today = ride("t", 10 * 86_400_000L, stopS = 420)
        val all = usual() + today +
            ride("short", 20 * 86_400_000L, km = 1.0) + ride("long", 21 * 86_400_000L, km = 40.0)
        // What the index holds: the same trips without their GPS points.
        val summaries = all.map { it.copy(points = emptyList()) }
        val ids = TripCompare.candidates(today, summaries).map { it.id }.toSet()
        assertEquals(setOf("u1", "u2", "u3", "u4"), ids)
        val loaded = all.filter { it.id in ids }
        assertEquals(TripCompare.compare(today, all), TripCompare.compare(today, loaded))
    }
}
