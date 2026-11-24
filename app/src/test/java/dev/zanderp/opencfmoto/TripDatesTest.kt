package dev.zanderp.opencfmoto

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** Day boundaries across Egypt's daylight-saving changes, and dates in the app's current language. */
class TripDatesTest {
    private val savedZone = TimeZone.getDefault()
    private val savedLocale = Locale.getDefault()
    private val hour = 3_600_000L

    @Before fun cairo() {
        TimeZone.setDefault(TimeZone.getTimeZone("Africa/Cairo"))
    }

    @After fun restore() {
        TimeZone.setDefault(savedZone)
        Locale.setDefault(savedLocale)
    }

    private fun at(y: Int, m: Int, d: Int, h: Int = 12, min: Int = 0): Long =
        Calendar.getInstance().apply { clear(); set(y, m - 1, d, h, min) }.timeInMillis

    private fun dayOf(ms: Long) = Calendar.getInstance().apply { timeInMillis = ms }.get(Calendar.DAY_OF_MONTH)

    @Test fun ordinaryDayIs24Hours() {
        val start = TripsListActivity.startOfDay(at(2026, 2, 10))
        assertEquals(24 * hour, TripsListActivity.endOfDay(start) - start)
    }

    @Test fun dayTheClocksGoBackIs25Hours() {
        // Egypt, last Thursday of October: at midnight the clock goes back to 23:00.
        val start = TripsListActivity.startOfDay(at(2026, 10, 29))
        val end = TripsListActivity.endOfDay(start)
        assertEquals(25 * hour, end - start)
        // A ride in the repeated hour (the 25th of that day) belongs to the 29th, not to no day.
        val lateRide = start + 24 * hour + 20 * 60_000L
        assertEquals(29, dayOf(lateRide))
        assertTrue(lateRide in start until end)
        assertEquals(start, TripsListActivity.startOfDay(lateRide))
        // …and the next day starts exactly where this one ends.
        assertEquals(end, TripsListActivity.startOfDay(at(2026, 10, 30)))
    }

    @Test fun dayTheClocksGoForwardIs23Hours() {
        // Egypt, last Friday of April: midnight jumps straight to 01:00.
        val start = TripsListActivity.startOfDay(at(2026, 4, 24))
        val end = TripsListActivity.endOfDay(start)
        assertEquals(23 * hour, end - start)
        assertEquals(end, TripsListActivity.startOfDay(at(2026, 4, 25)))
        // 00:30 on the 25th is the 25th's ride; with "+24 h" it also showed up under the 24th.
        val earlyRide = at(2026, 4, 25, 0, 30)
        assertFalse(earlyRide in start until end)
        assertTrue(earlyRide in start until start + 24 * hour)
    }

    // ── Dates follow the language in force, with Western digits in Arabic ──

    // Lazy: the times must be worked out after cairo() has set the zone, not in the machine's own
    // (CI runs in UTC, where an eager 18:05 came out as 20:05 Cairo time).
    private val ride by lazy {
        Trip("1", at(2026, 11, 24, 18, 5), at(2026, 11, 24, 19, 40), 12_500.0, 3_000_000L, 20f, emptyList())
    }

    private fun hasArabicIndicDigits(s: String) = s.any { it in '٠'..'٩' || it in '۰'..'۹' }

    @Test fun formattersFollowALanguageSwitch() {
        Locale.setDefault(Locale.forLanguageTag("en"))
        val english = ride.dateText()
        assertEquals("Tue 24 Nov 2026", english)
        // The in-app language switch: the same code must now answer in Arabic (it used to keep
        // the language the formatter was first created with).
        Locale.setDefault(Locale.forLanguageTag("ar-u-nu-latn"))
        val arabic = ride.dateText()
        assertTrue(arabic, arabic.any { it in '؀'..'ۿ' })
        assertTrue(arabic, arabic.contains("24") && arabic.contains("2026"))
        assertEquals(arabic, Trip.formatForRider(Trip.DAY_PATTERN, ride.start))
        Locale.setDefault(Locale.forLanguageTag("en"))
        assertEquals(english, ride.dateText())
    }

    @Test fun arabicWithoutTheDigitsExtensionStillGetsWesternDigits() {
        // Plain "ar" / "ar-EG" would print ٢٤ and ١٨:٠٥.
        for (tag in listOf("ar", "ar-EG")) {
            val plain = Locale.forLanguageTag(tag)
            assertTrue(
                "the platform default for $tag should be Arabic-Indic digits, or this test proves nothing",
                hasArabicIndicDigits(java.text.SimpleDateFormat(Trip.DAY_PATTERN, plain).format(java.util.Date(ride.start))),
            )
            Locale.setDefault(plain)
            assertEquals("latn", Trip.riderLocale().getUnicodeLocaleType("nu"))
            assertEquals("ar", Trip.riderLocale().language)
            assertFalse(ride.dateText(), hasArabicIndicDigits(ride.dateText()))
            assertEquals("18:05 – 19:40", ride.timeRangeText())
        }
    }

    @Test fun otherLocalesAreLeftAlone() {
        val en = Locale.forLanguageTag("en-GB")
        assertEquals(en, Trip.riderLocale(en))
        val ar = Locale.forLanguageTag("ar-EG-u-nu-latn")
        assertEquals(ar, Trip.riderLocale(ar))
    }
}
