package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The odometer estimate only grows by km ridden after the rider's reading. */
class MaintenanceOdometerTest {
    private val day = 86_400_000L
    private val readingAt = 400 * day

    /** A [km] ride north at a steady 36 km/h (10 m/s), one point per 10 s. */
    private fun ride(start: Long, km: Double, id: String = start.toString()): Trip {
        val pts = ArrayList<TrackPoint>()
        val steps = (km * 1000 / 100).toInt()
        for (i in 0..steps) pts += TrackPoint(30.0 + i * 100 / 111_195.0, 31.2, start + i * 10_000L, 10f)
        return Trip(id, start, start + steps * 10_000L, km * 1000, steps * 10_000L, 10f, pts)
    }

    @Test fun rideAfterTheReadingCountsInFull() {
        assertEquals(12.0, Maintenance.kmAfter(ride(readingAt + day, 12.0), readingAt), 1e-9)
        assertEquals(12.0, Maintenance.kmAfter(ride(readingAt, 12.0), readingAt), 1e-9)
    }

    @Test fun importedOlderRideAddsNothing() {
        // Odometer set to 5,000 km today, then last month's 200 km ride is imported.
        assertEquals(0.0, Maintenance.kmAfter(ride(readingAt - 30 * day, 200.0), readingAt), 0.0)
    }

    @Test fun readingTypedInMidRideCountsOnlyWhatFollows() {
        // 20 km ride; the rider reads the dash 5 km in (500 s after the start at 10 m/s).
        val t = ride(readingAt - 500_000L, 20.0)
        assertEquals(15.0, Maintenance.kmAfter(t, readingAt), 0.11)
        // Reading taken with the bike just switched on, before moving off: the whole ride counts.
        assertEquals(20.0, Maintenance.kmAfter(ride(readingAt - 1L, 20.0), readingAt), 0.11)
    }

    @Test fun midRideWithoutATrackFallsBackToTime() {
        val t = ride(readingAt - 500_000L, 20.0).copy(points = emptyList())
        assertEquals(15.0, Maintenance.kmAfter(t, readingAt), 1e-6)
    }

    @Test fun unknownReadingTimeCountsEverythingAsBefore() {
        assertEquals(200.0, Maintenance.kmAfter(ride(readingAt - 30 * day, 200.0), null), 1e-9)
    }

    // ── Books saved before the reading's time was stored ──

    @Test fun legacyBookCountsNewRidesAndSkipsOlderImports() {
        val stored = listOf(ride(100 * day, 10.0), ride(200 * day, 10.0), ride(300 * day, 10.0))
        // A ride just recorded (already in the store when the km are added): newer than the rest.
        val fresh = ride(310 * day, 25.0)
        val since = Maintenance.legacyBaseTime(fresh, stored + fresh)
        assertEquals(300 * day, since)
        assertEquals(25.0, Maintenance.kmAfter(fresh, since), 1e-9)
        // A ride recovered after a crash started before "now" but after every stored ride: counts.
        val recovered = ride(305 * day, 18.0)
        assertEquals(18.0, Maintenance.kmAfter(recovered, Maintenance.legacyBaseTime(recovered, stored + recovered)), 1e-9)
        // Last year's ride imported from a GPX: older than what is stored, adds nothing.
        val old = ride(50 * day, 200.0)
        assertEquals(0.0, Maintenance.kmAfter(old, Maintenance.legacyBaseTime(old, stored + old)), 0.0)
    }

    @Test fun legacyBookWithNoOtherRideStaysUnstamped() {
        val only = ride(310 * day, 25.0)
        assertNull(Maintenance.legacyBaseTime(only, listOf(only)))
        assertNull(Maintenance.legacyBaseTime(only, emptyList()))
        assertEquals(25.0, Maintenance.kmAfter(only, null), 1e-9)
    }
}
