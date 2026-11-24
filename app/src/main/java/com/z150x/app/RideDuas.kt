package com.z150x.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Ride duas (أدعية الركوب): a daily checklist (leaving home, riding, travelling, protection, coming
 * home) and one reminder notification a day when the app opens, before the ride. Never shown on the
 * dash: reading while riding is exactly the distraction to avoid. Riders can switch it off.
 */
object RideDuas {
    data class Dua(val id: String, @StringRes val title: Int, @StringRes val text: Int, @StringRes val note: Int?)

    val ALL = listOf(
        Dua("leave_home", R.string.dua_1_title, R.string.dua_1_text, R.string.dua_1_note),
        Dua("riding", R.string.dua_2_title, R.string.dua_2_text, R.string.dua_2_note),
        Dua("travel", R.string.dua_3_title, R.string.dua_3_text, R.string.dua_3_note),
        Dua("protection", R.string.dua_4_title, R.string.dua_4_text, R.string.dua_4_note),
        Dua("coming_home", R.string.dua_5_title, R.string.dua_5_text, null),
    )

    private const val PREFS = "ride_duas"
    private const val KEY_ON = "on"
    private const val KEY_DAY = "day"
    private const val KEY_DONE = "done"
    private const val KEY_NOTIFIED = "notified_day"
    private const val CHANNEL = "ride_duas"
    private const val NOTIF_ID = 7710

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun today() = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    fun enabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ON, true)

    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ON, on).apply()
        if (!on) cancelReminder(ctx)
    }

    /** Duas marked as read today; a new day starts with an empty list. */
    fun doneToday(ctx: Context): Set<String> {
        val p = prefs(ctx)
        if (p.getString(KEY_DAY, null) != today()) return emptySet()
        return p.getStringSet(KEY_DONE, emptySet()).orEmpty()
    }

    fun toggle(ctx: Context, id: String) {
        val done = doneToday(ctx).toMutableSet()
        if (!done.remove(id)) done.add(id)
        prefs(ctx).edit().putString(KEY_DAY, today()).putStringSet(KEY_DONE, done).apply()
        if (done.size == ALL.size) cancelReminder(ctx)
    }

    fun reset(ctx: Context) {
        prefs(ctx).edit().putString(KEY_DAY, today()).putStringSet(KEY_DONE, emptySet()).apply()
    }

    /** At most once a day, when the app opens and the list isn't finished yet. */
    fun maybeRemind(ctx: Context) {
        val p = prefs(ctx)
        if (!enabled(ctx) || p.getString(KEY_NOTIFIED, null) == today()) return
        if (doneToday(ctx).size >= ALL.size) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, ctx.getString(R.string.duas_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(
            ctx, NOTIF_ID, Intent(ctx, DuasActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(ctx.getString(R.string.duas_notif_title))
            .setContentText(ctx.getString(R.string.duas_notif_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(ctx.getString(R.string.dua_1_text)))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(NOTIF_ID, n)
            p.edit().putString(KEY_NOTIFIED, today()).apply()
        } catch (_: SecurityException) {
        }
    }

    fun cancelReminder(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID)
    }
}
