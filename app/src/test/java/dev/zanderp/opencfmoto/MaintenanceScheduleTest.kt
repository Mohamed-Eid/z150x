
package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Manufacturer-style schedules: first service at X km, then every Y km on the odometer (Zontes 125X manual). */
class MaintenanceScheduleTest {
    private fun task(first: Int?, every: Int?, last: Double?) =
        Maintenance.Task("t", "t", every, null, last, null, firstKm = first)

    private fun next(first: Int?, every: Int?, last: Double?, odo: Double) =
        Maintenance.kmWindow(task(first, every, last), odo)?.second

    @Test fun service_1000_then_every_6000() {
        assertEquals(listOf(1000.0, 6000.0, 12000.0, 18000.0, 24000.0), Maintenance.milestones(1000, 6000, 20000.0).take(5))
        assertEquals(1000.0, next(1000, 6000, null, 800.0))
        assertEquals(6000.0, next(1000, 6000, 1000.0, 1200.0))
        assertEquals(6000.0, next(1000, 6000, 1150.0, 1200.0))   // done a bit late: still due at 6,000
        assertEquals(12000.0, next(1000, 6000, 5800.0, 5900.0))  // done a bit early: covers 6,000
        assertEquals(12000.0, next(1000, 6000, 6400.0, 6500.0))  // done a bit late: covers 6,000
    }

    @Test fun gearbox_oil_6000_then_18000() {
        assertEquals(listOf(6000.0, 18000.0, 30000.0), Maintenance.milestones(6000, 12000, 25000.0).take(3))
        assertEquals(18000.0, next(6000, 12000, 6100.0, 7000.0))
    }

    @Test fun oil_filter_1000_then_12000() {
        assertEquals(12000.0, next(1000, 12000, 1000.0, 2000.0))
        assertEquals(24000.0, next(1000, 12000, 12100.0, 13000.0))
    }

    @Test fun never_logged() {
        assertEquals(1000.0, next(1000, 6000, null, 1400.0))  // overdue by 400: real
        assertNull(next(1000, 6000, null, 9000.0))             // far past: ask instead of "overdue by 8,000"
    }

    @Test fun plain_interval_unchanged() {
        assertEquals(9000.0, next(null, 3000, 6000.0, 7000.0))
        assertNull(next(null, 3000, null, 7000.0))
        // No first-service km: always "every N since last", wherever it was done.
        assertEquals(9600.0, next(null, 6000, 3600.0, 3700.0))
        assertEquals(11800.0, next(null, 6000, 5800.0, 5900.0))
    }

    // ── Which mark a logged service counts for (oil: first at 1,000, then every 6,000) ──

    @Test fun service_past_half_way_does_not_skip_the_next_mark() {
        // 3,600 is 2,400 km before the 6,000 mark: not "a bit early", so 6,000 is still due.
        // (It used to be taken for the 6,000 service, pushing the reminder to 12,000.)
        assertEquals(6000.0, next(1000, 6000, 3600.0, 3700.0))
        assertEquals(6000.0, next(1000, 6000, 3000.0, 3100.0))
        assertEquals(6000.0, next(1000, 6000, 5000.0, 5050.0))   // 1,000 early: over the 15 % (900 km)
    }

    @Test fun a_bit_early_counts_for_that_mark() {
        assertEquals(900.0, Maintenance.EARLY_SERVICE_SHARE * 6000, 1e-9)
        assertEquals(12000.0, next(1000, 6000, 5800.0, 5900.0))  // 200 early
        assertEquals(12000.0, next(1000, 6000, 5200.0, 5300.0))  // 800 early: inside the 900 km
        assertEquals(6000.0, next(1000, 6000, 700.0, 800.0))     // first service 300 early: covers 1,000
    }

    @Test fun late_counts_for_the_mark_just_passed() {
        assertEquals(12000.0, next(1000, 6000, 6400.0, 6500.0))
        assertEquals(12000.0, next(1000, 6000, 6000.0, 6100.0))  // exactly on the mark
        assertEquals(6000.0, next(1000, 6000, 1000.0, 1200.0))   // first service exactly at 1,000
        assertEquals(18000.0, next(1000, 6000, 12300.0, 12400.0))
    }

    @Test fun next_due_is_never_the_mark_the_service_was_logged_for() {
        // Whatever km the service is logged at, the next due lies ahead of it and at most
        // 1.15 intervals away.
        var last = 100.0
        while (last < 40_000.0) {
            val due = next(1000, 6000, last, last)!!
            assertTrue("last=$last due=$due", due > last && due - last <= 6000 * 1.15 + 1e-6)
            last += 50.0
        }
    }

    @Test fun never_more_than_one_interval_when_not_early() {
        // A custom task "first at 10,000, then every 3,000" with a service logged at 2,000:
        // the 10,000 mark is 8,000 away, so the plain interval applies until the grid is reached.
        assertEquals(5000.0, next(10000, 3000, 2000.0, 2100.0))
        assertEquals(10000.0, next(10000, 3000, 8000.0, 8100.0))
    }

    @Test fun one_off_task_is_finished_once_logged() {
        assertNull(next(5000, null, 5100.0, 6000.0))
        assertNull(next(5000, null, 2000.0, 6000.0))
    }
}
