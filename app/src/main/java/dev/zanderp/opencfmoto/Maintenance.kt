
package dev.zanderp.opencfmoto

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

/**
 * Maintenance logbook per bike (Garage): an estimated odometer (the rider's reading + every ride
 * recorded since), tasks with km and/or time intervals, refuels with real consumption, and a
 * heads-up when something is due as you connect to the bike.
 *
 * Intervals are generic starting points, not the manufacturer's — the rider adjusts them.
 */
object Maintenance {
    private const val PREFS = "maintenance"
    private const val CHANNEL = "maintenance"
    private const val NOTIF_ID = 4401
    const val DUE_SOON_KM = 300
    const val DUE_SOON_DAYS = 15

    data class Task(
        val id: String,
        val name: String,
        val everyKm: Int?,
        val everyMonths: Int?,
        val lastKm: Double?,
        val lastAt: Long?,
        val enabled: Boolean = true,
        /**
         * Manufacturer-style schedule: the first service at this odometer reading (e.g. 1,000 km
         * running-in), then every [everyKm] counted on the odometer itself (6,000 · 12,000 · …),
         * not from whenever it was last done. Null = plain "every N km since last time".
         */
        val firstKm: Int? = null,
    )

    data class Entry(val at: Long, val km: Double, val kind: String, val taskId: String?, val liters: Double?, val euros: Double?)

    data class Book(
        val bikeKey: String,
        val odoBaseKm: Double?,
        val kmSinceBase: Double,
        val tasks: List<Task>,
        val log: List<Entry>,
        /** km recorded by the app for this bike, never reset (refuel maths survive odometer fixes). */
        val appKm: Double = 0.0,
        /**
         * When the rider read [odoBaseKm] off the dash (epoch ms). Only km ridden after this moment
         * are added to [kmSinceBase]; an older ride imported later is already inside the reading.
         * Null on a book saved before this was stored: see [legacyBaseTime].
         */
        val odoBaseAt: Long? = null,
    ) {
        val odometer: Double? get() = odoBaseKm?.let { it + kmSinceBase }
    }

    enum class Level { OK, SOON, DUE, UNKNOWN }

    data class Status(val task: Task, val level: Level, val kmLeft: Double?, val daysLeft: Int?, val fraction: Float)

    // ---------------------------------------------------------------- storage

    fun bikeKey(ctx: Context): String = BikeMemory.selected(ctx)?.raw?.hashCode()?.toString() ?: "default"

    fun load(ctx: Context, key: String = bikeKey(ctx)): Book {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("book_$key", null)
            ?: return Book(key, null, 0.0, defaultTasks(ctx), emptyList())
        return try {
            val o = JSONObject(raw)
            Book(
                key,
                if (o.has("odoBase")) o.getDouble("odoBase") else null,
                o.optDouble("kmSince", 0.0),
                o.optJSONArray("tasks")?.let { a -> (0 until a.length()).map { taskFrom(a.getJSONObject(it)) } } ?: defaultTasks(ctx),
                o.optJSONArray("log")?.let { a -> (0 until a.length()).map { entryFrom(a.getJSONObject(it)) } } ?: emptyList(),
                o.optDouble("appKm", 0.0),
                if (o.has("odoBaseAt")) o.getLong("odoBaseAt") else null,
            )
        } catch (_: Exception) {
            Book(key, null, 0.0, defaultTasks(ctx), emptyList())
        }
    }

    @Synchronized
    fun save(ctx: Context, b: Book) {
        val o = JSONObject()
        b.odoBaseKm?.let { o.put("odoBase", it) }
        b.odoBaseAt?.let { o.put("odoBaseAt", it) }
        o.put("kmSince", b.kmSinceBase)
        o.put("appKm", b.appKm)
        o.put("tasks", JSONArray().apply { b.tasks.forEach { put(taskJson(it)) } })
        o.put("log", JSONArray().apply { b.log.takeLast(300).forEach { put(entryJson(it)) } })
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("book_${b.bikeKey}", o.toString()).apply()
    }

    /**
     * The Zontes 125X owner's manual maintenance table: first service at 1,000 km, the second at
     * 6,000 and then every 6,000; parts on their own cycles (oil filter every 12,000 after the
     * first, air filter every 12,000, gearbox oil at 6,000 / 18,000 / 30,000, belt every 20,000,
     * spark plug every 24,000, brake fluid every 2 years, coolant every 30,000 km or 3 years).
     * Notes 3 and 5: steering lock and drive/driven pulley bushings cleaned and greased every 10,000;
     * note 7: valve clearance (cold) inlet 0.08–0.12 mm, exhaust 0.18–0.22 mm.
     */
    fun zontes125Plan(ctx: Context) = listOf(
        Task("service", ctx.getString(R.string.maint_service), 6000, 15, null, null, firstKm = 1000),
        Task("oil", ctx.getString(R.string.maint_oil_engine), 6000, 15, null, null, firstKm = 1000),
        Task("oil_filter", ctx.getString(R.string.maint_oil_filter), 12000, null, null, null, firstKm = 1000),
        Task("gear_oil", ctx.getString(R.string.maint_gear_oil), 12000, null, null, null, firstKm = 6000),
        Task("air", ctx.getString(R.string.maint_air), 12000, null, null, null, firstKm = 12000),
        Task("valves", ctx.getString(R.string.maint_valves), 6000, null, null, null, firstKm = 6000),
        Task("belt", ctx.getString(R.string.maint_belt), 20000, null, null, null, firstKm = 20000),
        Task("pulleys", ctx.getString(R.string.maint_pulleys), 10000, null, null, null, firstKm = 10000),
        Task("steer_lock", ctx.getString(R.string.maint_steer_lock), 10000, null, null, null, firstKm = 10000),
        Task("spark", ctx.getString(R.string.maint_spark), 24000, null, null, null, firstKm = 24000),
        Task("brake_fluid", ctx.getString(R.string.maint_brake_fluid), null, 24, null, null),
        Task("coolant", ctx.getString(R.string.maint_coolant), 30000, 36, null, null, firstKm = 30000),
        Task("brakes", ctx.getString(R.string.maint_brakes), 6000, 12, null, null, firstKm = 6000),
        Task("tyres", ctx.getString(R.string.maint_tyres), 6000, 12, null, null, firstKm = 6000),
        Task("itv", ctx.getString(R.string.maint_itv), null, 24, null, null),
        Task("insurance", ctx.getString(R.string.maint_insurance), null, 12, null, null),
    )

    /** Swap the task list for [plan], keeping when each matching task was last done and the rider's own tasks. */
    fun applyPlan(ctx: Context, plan: List<Task>) {
        val b = load(ctx)
        val old = b.tasks.associateBy { it.id }
        val merged = plan.map { p -> old[p.id]?.let { o -> p.copy(lastKm = o.lastKm, lastAt = o.lastAt) } ?: p }
        val custom = b.tasks.filter { it.id.startsWith("custom_") }
        save(ctx, b.copy(tasks = merged + custom))
        LogBus.log("[MAINT] plan applied: ${plan.size} tasks (+${custom.size} custom)")
    }

    private fun defaultTasks(ctx: Context) = listOf(
        Task("oil", ctx.getString(R.string.maint_oil), 3000, 12, null, null),
        Task("air", ctx.getString(R.string.maint_air), 6000, 12, null, null),
        Task("belt", ctx.getString(R.string.maint_belt), 12000, null, null, null),
        Task("brakes", ctx.getString(R.string.maint_brakes), 6000, null, null, null),
        Task("tyres", ctx.getString(R.string.maint_tyres), 5000, null, null, null),
        Task("service", ctx.getString(R.string.maint_service), 6000, 12, null, null),
        Task("itv", ctx.getString(R.string.maint_itv), null, 24, null, null),
        Task("insurance", ctx.getString(R.string.maint_insurance), null, 12, null, null),
    )

    private fun taskJson(t: Task) = JSONObject().put("id", t.id).put("name", t.name).put("on", t.enabled).apply {
        t.everyKm?.let { put("km", it) }
        t.everyMonths?.let { put("months", it) }
        t.lastKm?.let { put("lastKm", it) }
        t.lastAt?.let { put("lastAt", it) }
        t.firstKm?.let { put("firstKm", it) }
    }

    private fun taskFrom(o: JSONObject) = Task(
        o.getString("id"), o.optString("name"),
        if (o.has("km")) o.getInt("km") else null,
        if (o.has("months")) o.getInt("months") else null,
        if (o.has("lastKm")) o.getDouble("lastKm") else null,
        if (o.has("lastAt")) o.getLong("lastAt") else null,
        o.optBoolean("on", true),
        if (o.has("firstKm")) o.getInt("firstKm") else null,
    )

    private fun entryJson(e: Entry) = JSONObject().put("at", e.at).put("km", e.km).put("kind", e.kind).apply {
        e.taskId?.let { put("task", it) }
        e.liters?.let { put("l", it) }
        e.euros?.let { put("eur", it) }
    }

    private fun entryFrom(o: JSONObject) = Entry(
        o.getLong("at"), o.optDouble("km", 0.0), o.optString("kind"),
        o.optString("task").ifBlank { null },
        if (o.has("l")) o.getDouble("l") else null,
        if (o.has("eur")) o.getDouble("eur") else null,
    )

    // ---------------------------------------------------------------- actions

    fun setOdometer(ctx: Context, km: Double) {
        val b = load(ctx)
        save(ctx, b.copy(odoBaseKm = km, kmSinceBase = 0.0, odoBaseAt = System.currentTimeMillis()))
    }

    fun markDone(ctx: Context, taskId: String, atKm: Double? = null) {
        val b = load(ctx)
        // Unknown km stays unknown (not 0): otherwise setting the odometer later makes it look overdue.
        val km = atKm ?: b.odometer
        val now = System.currentTimeMillis()
        save(
            ctx,
            b.copy(
                tasks = b.tasks.map { if (it.id == taskId) it.copy(lastKm = km, lastAt = now) else it },
                log = b.log + Entry(now, km ?: 0.0, "task", taskId, null, null),
            ),
        )
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("notified_${b.bikeKey}_$taskId").apply()
    }

    fun updateTask(ctx: Context, task: Task) {
        val b = load(ctx)
        save(ctx, b.copy(tasks = b.tasks.map { if (it.id == task.id) task else it }))
    }

    fun addTask(ctx: Context, name: String, everyKm: Int?, everyMonths: Int?, firstKm: Int? = null) {
        val b = load(ctx)
        val id = "custom_${System.currentTimeMillis()}"
        // With a first-service km the count runs on the odometer, so it doesn't start "now".
        val lastKm = if (firstKm != null) null else b.odometer
        save(ctx, b.copy(tasks = b.tasks + Task(id, name, everyKm, everyMonths, lastKm, System.currentTimeMillis(), firstKm = firstKm)))
    }

    fun deleteTask(ctx: Context, taskId: String) {
        val b = load(ctx)
        save(ctx, b.copy(tasks = b.tasks.filter { it.id != taskId }))
    }

    fun addRefuel(ctx: Context, liters: Double, euros: Double?) {
        val b = load(ctx)
        // Refuels are positioned on the never-reset app km, so "Correct km" doesn't skew consumption.
        save(ctx, b.copy(log = b.log + Entry(System.currentTimeMillis(), b.appKm, "fuel", null, liters, euros)))
    }

    /**
     * Every saved ride adds its km to the selected bike's odometer estimate — but only the km ridden
     * after the rider's reading ([Book.odoBaseAt]): the reading already contains everything before
     * it, so importing last month's GPX must not push the estimate up.
     */
    fun onTripSaved(ctx: Context, trip: Trip) {
        val b = load(ctx)
        var since = b.odoBaseAt
        if (since == null && b.odoBaseKm != null) since = legacyBaseTime(trip, TripStore.summaries(ctx))
        val add = kmAfter(trip, since)
        if (add < trip.distanceKm) {
            LogBus.log(
                "[MAINT] ride ${trip.id}: ${String.format(Locale.US, "%.1f of %.1f", add, trip.distanceKm)} km " +
                    "are after the odometer reading — only those are added to the odometer"
            )
        }
        save(ctx, b.copy(kmSinceBase = b.kmSinceBase + add, appKm = b.appKm + trip.distanceKm, odoBaseAt = since))
    }

    /**
     * How many of [trip]'s km were ridden after [since] (epoch ms; null = unknown, count them all).
     * A ride that starts after the reading counts in full, one that ended before it not at all.
     * When the reading was typed in mid-ride (the usual case: the rider reads the dash with the
     * bike on, so a ride is already being recorded) the ride counts in proportion to the track
     * length after that moment, or to the time after it when there is no usable track.
     */
    internal fun kmAfter(trip: Trip, since: Long?): Double {
        if (since == null || trip.start >= since) return trip.distanceKm
        if (trip.end <= since) return 0.0
        val p = trip.points
        var total = 0.0
        var after = 0.0
        for (i in 1 until p.size) {
            val d = TripCompare.metres(p[i - 1].lat, p[i - 1].lon, p[i].lat, p[i].lon)
            total += d
            if (p[i].time > since) after += d
        }
        val share = if (total > 0.0) after / total
            else (trip.end - since).toDouble() / (trip.end - trip.start).coerceAtLeast(1L)
        return trip.distanceKm * share.coerceIn(0.0, 1.0)
    }

    /**
     * Base time for a book saved by an older version: it has a reading but not when it was taken.
     * "Now" would be wrong — the first ride saved after the update can be one recovered from a
     * crashed session, which started before now and must count. What is known is that every ride
     * already stored has been dealt with (added when it was saved, or wiped by the reading). So
     * the start of the newest stored ride other than [trip] stands in for the reading's time: all
     * the recorder or crash recovery will ever save is newer and counts as before, km counted so
     * far stay untouched, and a ride older than that — which only a GPX import can produce — adds
     * nothing. With no other ride stored there is nothing to compare with: null, [trip] counts as
     * before and the book stays unstamped. The next "Correct km" stores the real time.
     */
    internal fun legacyBaseTime(trip: Trip, stored: List<Trip>): Long? =
        stored.filter { it.id != trip.id }.maxOfOrNull { it.start }

    // ---------------------------------------------------------------- status

    /**
     * The odometer readings a manufacturer-style task falls on: [Task.firstKm], then the
     * [Task.everyKm] grid. A small first service (running-in: 1,000 of 6,000) is followed by the
     * plain grid (6,000 · 12,000 …); a first service far into the interval sets the grid's phase
     * (gearbox oil 6,000 of 12,000 → 18,000 · 30,000 …).
     */
    internal fun milestones(firstKm: Int, everyKm: Int?, upTo: Double): List<Double> {
        val out = arrayListOf(firstKm.toDouble())
        if (everyKm == null || everyKm <= 0) return out
        val rem = firstKm % everyKm
        val phase = if (rem * 4 >= everyKm) rem else 0
        var n = 1
        while ((out.size < 2 || out.last() <= upTo) && n < 10_000) {
            val g = (phase + n * everyKm).toDouble()
            if (g > firstKm) out += g
            n++
        }
        return out
    }

    /**
     * How far ahead of a mark a service still counts as that mark's service ("a bit early"), as a
     * share of the interval: 15 % — 900 km on a 6,000 km interval. It covers a rider who goes in
     * when the "due soon" notice shows ([DUE_SOON_KM]) with room to spare, and caps the stretch to
     * the following mark at 1.15 intervals.
     */
    internal const val EARLY_SERVICE_SHARE = 0.15

    /**
     * (previous mark, next due km) for a task's km cycle, or null when it can't be known yet.
     *
     * Manufacturer-style tasks ([Task.firstKm] set), once a service is logged at `last`:
     *  1. The service covers every mark up to `last + 15 % of the interval`
     *     ([EARLY_SERVICE_SHARE]): the marks already passed (done late, however late) and one that
     *     was at most that far ahead (done a bit early).
     *  2. The next due is the first mark after that. So 1,000 → 6,000 with a service at 5,800
     *     (200 early) or 6,400 (late) is due again at 12,000, while a service at 3,600 (2,400
     *     before the mark: not "a bit early") is still due at 6,000.
     *  3. When no mark was close enough to count as done early, the next due is never more than
     *     one interval after `last` (only bites before a first mark that lies further away than the
     *     interval). A service that did count as early is due at the following mark, which is at
     *     most 1.15 intervals away.
     */
    internal fun kmWindow(t: Task, odo: Double?): Pair<Double, Double>? {
        val first = t.firstKm
        if (first == null) {
            val every = t.everyKm ?: return null
            val last = t.lastKm ?: return null
            return last to last + every
        }
        val last = t.lastKm
        if (last == null) {
            // Never logged: the first service is due, unless the bike is clearly past it already
            // (then ask when it was last done rather than shouting "overdue by 9,000 km").
            if (odo == null || odo > first + (t.everyKm ?: first) / 2.0) return null
            return 0.0 to first.toDouble()
        }
        // A one-off (first service only, no interval) is finished once it has been logged.
        val every = t.everyKm?.takeIf { it > 0 } ?: return null
        val covered = last + EARLY_SERVICE_SHARE * every
        val ms = milestones(first, every, last + 2.0 * every)
        val next = ms.firstOrNull { it > covered } ?: return null
        val doneEarly = ms.any { it > last && it <= covered }
        return last to if (doneEarly) next else minOf(next, last + every)
    }

    fun status(b: Book, t: Task, now: Long = System.currentTimeMillis()): Status {
        val odo = b.odometer
        var kmLeft: Double? = null
        var daysLeft: Int? = null
        var frac = 0f
        val w = kmWindow(t, odo)
        if (w != null && odo != null) {
            kmLeft = w.second - odo
            frac = maxOf(frac, ((odo - w.first) / (w.second - w.first).coerceAtLeast(1.0)).toFloat())
        }
        if (t.everyMonths != null && t.lastAt != null) {
            val due = Calendar.getInstance().apply { timeInMillis = t.lastAt; add(Calendar.MONTH, t.everyMonths) }.timeInMillis
            daysLeft = ((due - now) / 86_400_000L).toInt()
            frac = maxOf(frac, ((now - t.lastAt).toFloat() / (due - t.lastAt).coerceAtLeast(1)))
        }
        val level = when {
            kmLeft == null && daysLeft == null -> Level.UNKNOWN
            (kmLeft != null && kmLeft <= 0) || (daysLeft != null && daysLeft <= 0) -> Level.DUE
            (kmLeft != null && kmLeft <= DUE_SOON_KM) || (daysLeft != null && daysLeft <= DUE_SOON_DAYS) -> Level.SOON
            else -> Level.OK
        }
        return Status(t, level, kmLeft, daysLeft, frac.coerceIn(0f, 1f))
    }

    fun statusText(ctx: Context, s: Status): String = when {
        s.level == Level.UNKNOWN -> ctx.getString(R.string.maint_status_unknown)
        s.kmLeft != null && (s.daysLeft == null || s.kmLeft / 30.0 < s.daysLeft) ->
            if (s.kmLeft <= 0) ctx.getString(R.string.maint_status_over_km, fmtKm(-s.kmLeft))
            else ctx.getString(R.string.maint_status_km_left, fmtKm(s.kmLeft))
        s.daysLeft != null ->
            if (s.daysLeft <= 0) ctx.getString(R.string.maint_status_over_days, -s.daysLeft)
            else ctx.getString(R.string.maint_status_days_left, s.daysLeft)
        else -> ""
    }

    /** The most urgent known item, for the main-screen pill. */
    fun mostUrgent(ctx: Context): Status? {
        val b = load(ctx)
        return b.tasks.filter { it.enabled }.map { status(b, it) }
            .filter { it.level != Level.UNKNOWN }
            .maxByOrNull { it.fraction }
    }

    /** Real consumption (full-to-full refuels): litres after the first fill / km between first and last. */
    fun consumption(b: Book): Triple<Double, Double?, Int>? {
        val fills = b.log.filter { it.kind == "fuel" && it.liters != null }.sortedBy { it.at }
        if (fills.size < 2) return null
        val km = fills.last().km - fills.first().km
        if (km < 20) return null
        val liters = fills.drop(1).sumOf { it.liters ?: 0.0 }
        val euros = fills.drop(1).mapNotNull { it.euros }.takeIf { it.size == fills.size - 1 }?.sum()
        return Triple(liters / km * 100.0, euros?.let { it / km }, fills.size)
    }

    fun fmtKm(v: Double): String = OpenCfMotoApp.str(R.string.code_unit_km, String.format(Locale.US, "%,.0f", v))

    /** On connecting to the bike: one heads-up per item that is due or close (not repeated daily). */
    fun checkDue(ctx: Context) {
        val app = ctx.applicationContext
        val b = load(app)
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val due = b.tasks.filter { it.enabled }.map { status(b, it) }
            .filter { it.level == Level.DUE || it.level == Level.SOON }
            .filter { s -> prefs.getString("notified_${b.bikeKey}_${s.task.id}", null) != s.level.name }
        if (due.isEmpty()) return
        prefs.edit().apply { due.forEach { putString("notified_${b.bikeKey}_${it.task.id}", it.level.name) } }.apply()
        val lines = due.map { "${it.task.name}: ${statusText(app, it)}" }
        LogBus.log("[MAINT] due: ${lines.joinToString(" · ")}")
        val nm = app.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, app.getString(R.string.maint_channel), NotificationManager.IMPORTANCE_DEFAULT)
        )
        val open = PendingIntent.getActivity(
            app, 4, Intent(app, MaintenanceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(app.getString(R.string.maint_notif_title))
            .setContentText(lines.joinToString(" · "))
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try {
            nm.notify(NOTIF_ID, n)
        } catch (_: SecurityException) {
        }
    }
}
