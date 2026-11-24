package com.z150x.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider

/**
 * The rider's settings: dash fit and safe area, map layout and colours, auto-connect and the ride
 * extras. Everything else keeps its recommended default and lives in Advanced settings
 * ([SetupActivity]).
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContentView(R.layout.activity_settings)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.set_root)) { v, insets ->
            val b = insets.getInsets(SafeInsets.BARS)
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }

        findViewById<View>(R.id.set_fit).setOnClickListener { ScreenFitActivity.start(this) }
        findViewById<View>(R.id.set_controls).setOnClickListener { startActivity(Intent(this, ControlsActivity::class.java)) }
        findViewById<View>(R.id.set_language).setOnClickListener { AppLanguage.toggle(this) }
        findViewById<View>(R.id.set_share_log).setOnClickListener { LogShare.share(this) }
        findViewById<View>(R.id.set_about).setOnClickListener { AboutActivity.start(this) }
        findViewById<View>(R.id.set_advanced).setOnClickListener { SetupActivity.start(this) }

        bindSwitch(R.id.set_safe, DashSafeArea.on(this)) { DashSafeArea.setOn(this, it); renderSafeArea() }
        bindSwitch(R.id.set_auto_connect, AppSettings.autoConnect(this)) { AppSettings.setAutoConnect(this, it) }
        bindSwitch(R.id.set_log_rides, AppSettings.logTrips(this)) { AppSettings.setLogTrips(this, it) }
        bindSwitch(R.id.set_name_rides, AppSettings.nameRides(this)) { AppSettings.setNameRides(this, it) }
        bindSwitch(R.id.set_parking, RideExtras.parking(this)) { RideExtras.setParking(this, it) }
        bindSwitch(R.id.set_rain, RideExtras.rain(this)) { RideExtras.setRain(this, it) }
        bindSwitch(R.id.set_duas, RideDuas.enabled(this)) { RideDuas.setEnabled(this, it); renderDuas() }
        findViewById<View>(R.id.set_duas_open).setOnClickListener { DuasActivity.start(this) }
        renderDuas()

        bindSlider(R.id.set_top, R.id.set_top_val, DashSafeArea.topPct(this)) { DashSafeArea.setTopPct(this, it) }
        bindSlider(R.id.set_bottom, R.id.set_bottom_val, DashSafeArea.bottomPct(this)) { DashSafeArea.setBottomPct(this, it) }
        renderSafeArea()

        findViewById<View>(R.id.set_layout_split).setOnClickListener { MapLayout.set(this, full = false); renderSegments() }
        findViewById<View>(R.id.set_layout_full).setOnClickListener { MapLayout.set(this, full = true); renderSegments() }
        findViewById<View>(R.id.set_theme_auto).setOnClickListener { setTheme(MapTheme.AUTO) }
        findViewById<View>(R.id.set_theme_day).setOnClickListener { setTheme(MapTheme.DAY) }
        findViewById<View>(R.id.set_theme_night).setOnClickListener { setTheme(MapTheme.NIGHT) }
        renderSegments()
    }

    override fun onResume() {
        super.onResume()
        renderSegments()
    }

    private fun bindSwitch(id: Int, value: Boolean, onChange: (Boolean) -> Unit) {
        findViewById<MaterialSwitch>(id).apply {
            isChecked = value
            setOnCheckedChangeListener { _, on -> onChange(on) }
        }
    }

    private fun bindSlider(id: Int, valueId: Int, value: Int, onChange: (Int) -> Unit) {
        val label = findViewById<TextView>(valueId)
        findViewById<Slider>(id).apply {
            valueFrom = 0f
            valueTo = DashSafeArea.MAX_PCT.toFloat()
            stepSize = 1f
            isTickVisible = false
            this.value = value.toFloat()
            label.text = getString(R.string.z_set_pct, value)
            addOnChangeListener { _, v, fromUser ->
                label.text = getString(R.string.z_set_pct, v.toInt())
                if (fromUser) onChange(v.toInt())
            }
        }
    }

    private fun renderDuas() {
        findViewById<View>(R.id.set_duas_open).visibility = if (RideDuas.enabled(this)) View.VISIBLE else View.GONE
    }

    private fun renderSafeArea() {
        findViewById<View>(R.id.set_safe_sliders).visibility = if (DashSafeArea.on(this)) View.VISIBLE else View.GONE
    }

    private fun setTheme(theme: MapTheme) {
        NightPrefs.setTheme(this, theme)
        AaVideoBridge.nightSink?.invoke(NightPrefs.isNightNow(this))
        renderSegments()
    }

    private fun renderSegments() {
        val full = MapLayout.isFull(this)
        select(R.id.set_layout_split, !full)
        select(R.id.set_layout_full, full)
        val theme = NightPrefs.theme(this)
        select(R.id.set_theme_auto, theme == MapTheme.AUTO)
        select(R.id.set_theme_day, theme == MapTheme.DAY)
        select(R.id.set_theme_night, theme == MapTheme.NIGHT)
    }

    private fun select(id: Int, on: Boolean) {
        findViewById<TextView>(id).apply {
            setBackgroundResource(if (on) R.drawable.bg_z_seg_on else R.drawable.bg_z_seg_off)
            setTextColor(ContextCompat.getColor(context, if (on) R.color.white else R.color.text_primary))
            isSelected = on
        }
    }

    companion object {
        fun start(ctx: Context) = ctx.startActivity(Intent(ctx, SettingsActivity::class.java))
    }
}

/**
 * Map layout: Android Auto shows the map with a column of cards when the screen is wide in dp, and
 * the map alone when it's narrower. A higher density makes the same 800-px dash "narrower" for
 * Android Auto. Takes effect at the next connect (the density is part of the session setup).
 */
object MapLayout {
    /** Density used for the full-screen map; to be confirmed on the Z150X dash. */
    const val FULL_MAP_DPI = 240

    fun isFull(ctx: Context): Boolean = (VideoPrefs.dpiOverride(ctx) ?: 0) >= FULL_MAP_DPI

    fun set(ctx: Context, full: Boolean) {
        VideoPrefs.setDpiOverride(ctx, if (full) FULL_MAP_DPI else null)
        LogBus.log("[settings] map layout → ${if (full) "full-screen map (dpi $FULL_MAP_DPI)" else "map + cards (profile dpi)"}")
    }
}
