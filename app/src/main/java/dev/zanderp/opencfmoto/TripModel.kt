package dev.zanderp.opencfmoto

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One GPS sample on a ride. */
data class TrackPoint(val lat: Double, val lon: Double, val time: Long, val speedMs: Float)

/**
 * A recorded ride: summary stats plus the full GPS track (for the map).
 *
 * Speeds are stored in m/s (as the GPS reports them); distance in metres; times in epoch millis.
 * The [id] is the start-time millis, which keeps files sortable and unique enough for one device.
 */
data class Trip(
    val id: String,
    val start: Long,
    val end: Long,
    val distanceMeters: Double,
    val movingTimeMs: Long,
    val maxSpeedMs: Float,
    val points: List<TrackPoint>,
) {
    val distanceKm: Double get() = distanceMeters / 1000.0
    val maxKmh: Int get() = (maxSpeedMs * 3.6f).toInt()
    val avgKmh: Int get() =
        if (movingTimeMs > 0) ((distanceMeters / (movingTimeMs / 1000.0)) * 3.6).toInt() else 0

    fun durationText(): String {
        val sec = (movingTimeMs / 1000).coerceAtLeast(0)
        return String.format(Locale.US, "%02d:%02d:%02d", sec / 3600, (sec % 3600) / 60, sec % 60)
    }

    fun distanceText(): String = OpenCfMotoApp.str(R.string.code_unit_km, String.format(Locale.US, "%.2f", distanceKm))

    fun dateText(): String = formatForRider(DAY_PATTERN, start)

    fun timeRangeText(): String {
        val s = formatForRider(TIME_PATTERN, start)
        val e = formatForRider(TIME_PATTERN, end)
        return "$s – $e"
    }

    companion object {
        /** A day as the Rides screens show it, e.g. "Tue 24 Nov 2026". */
        const val DAY_PATTERN = "EEE d MMM yyyy"
        private const val TIME_PATTERN = "HH:mm"

        /**
         * The locale for dates and times shown to the rider. The app's Arabic is `ar-u-nu-latn`
         * (Western digits); if the locale in force is Arabic without that extension, dates would
         * come out in ٠-٩, so Latin digits are forced here.
         */
        fun riderLocale(base: Locale = Locale.getDefault()): Locale =
            if (base.language == "ar" && base.getUnicodeLocaleType("nu") != "latn") {
                try {
                    Locale.Builder().setLocale(base).setUnicodeLocaleKeyword("nu", "latn").build()
                } catch (_: RuntimeException) {
                    Locale.forLanguageTag("ar-u-nu-latn")
                }
            } else {
                base
            }

        /**
         * Formats [epochMs] with [pattern] in the language the app is in right now. The formatter is
         * built on every call: a shared static one keeps the language it was created with after the
         * in-app language switch, and [SimpleDateFormat] is not safe to share between threads.
         */
        fun formatForRider(pattern: String, epochMs: Long, locale: Locale = riderLocale()): String =
            SimpleDateFormat(pattern, locale).format(Date(epochMs))
    }
}
