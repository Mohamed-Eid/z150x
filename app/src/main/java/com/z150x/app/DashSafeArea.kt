package com.z150x.app

import android.content.Context
import com.z150x.app.aa.proto.Media

/**
 * The Z150X dash draws its own bars over the Android Auto picture: TC / ABS / clock / Bluetooth on
 * top, rpm / fuel / temperature / speed at the bottom. Instead of shrinking the picture (black
 * borders, [ScreenMargins]), we tell Android Auto those strips are covered ("content insets"): the
 * map still fills the screen and Android Auto moves its search bar, cards and buttons clear of them.
 * Sizes are a share of the Android Auto picture height; adjustable live in Settings.
 */
object DashSafeArea {
    private const val PREFS = "dash_safe_area"
    private const val KEY_ON = "on"
    private const val KEY_TOP = "top_pct"
    private const val KEY_BOTTOM = "bottom_pct"

    /** First guesses from a photo of the Z150X dash; riders fine-tune them on the bike. */
    const val DEFAULT_TOP_PCT = 14
    const val DEFAULT_BOTTOM_PCT = 17
    const val MAX_PCT = 30

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun on(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ON, true)
    fun topPct(ctx: Context): Int = prefs(ctx).getInt(KEY_TOP, DEFAULT_TOP_PCT).coerceIn(0, MAX_PCT)
    fun bottomPct(ctx: Context): Int = prefs(ctx).getInt(KEY_BOTTOM, DEFAULT_BOTTOM_PCT).coerceIn(0, MAX_PCT)

    fun setOn(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean(KEY_ON, on).apply(); pushLive() }
    fun setTopPct(ctx: Context, pct: Int) { prefs(ctx).edit().putInt(KEY_TOP, pct.coerceIn(0, MAX_PCT)).apply(); pushLive() }
    fun setBottomPct(ctx: Context, pct: Int) { prefs(ctx).edit().putInt(KEY_BOTTOM, pct.coerceIn(0, MAX_PCT)).apply(); pushLive() }

    /** Re-send to Android Auto right away when a session is live, so the rider sees it on the dash. */
    private fun pushLive() {
        try { AaVideoBridge.uiConfigSink?.invoke() } catch (_: Exception) {}
    }

    /** The UI config for an Android Auto picture [videoHeight] px tall (zero insets when off). */
    fun uiConfig(ctx: Context, videoHeight: Int): Media.UiConfig {
        val top = if (on(ctx)) videoHeight * topPct(ctx) / 100 else 0
        val bottom = if (on(ctx)) videoHeight * bottomPct(ctx) / 100 else 0
        val insets = Media.Insets.newBuilder().setTop(top).setBottom(bottom).setLeft(0).setRight(0).build()
        return Media.UiConfig.newBuilder()
            .setContentInsets(insets)
            .setStableContentInsets(insets)
            .build()
    }
}
