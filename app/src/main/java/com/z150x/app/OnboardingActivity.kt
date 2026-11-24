package com.z150x.app

import android.Manifest
import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat

/** First-run flag. Riders who finished the old setup wizard count as onboarded. */
object Onboarding {
    private const val PREFS = "z150x_onboarding"
    private const val KEY_DONE = "done"
    internal const val KEY_STEP = "step"

    internal fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isDone(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_DONE, false) || SetupActivity.hasSeen(ctx)

    fun markDone(ctx: Context) {
        prefs(ctx).edit().putBoolean(KEY_DONE, true).remove(KEY_STEP).apply()
        SetupActivity.markSeen(ctx)
    }
}

/**
 * Four short steps on a "road": 1 language, 2 permissions, 3 the one-time Android Auto developer
 * switch, 4 scan the dash's pairing QR (hold ▲ on the Z150X). Finishing starts Home and connects.
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var progress: RideProgressView
    private lateinit var steps: List<View>
    private var step = 0
    private val running = ArrayList<ValueAnimator>()

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        renderPermissionChecks()
        steps[1].postDelayed({ if (!isFinishing) showStep(2) }, 500)
    }

    private val scanLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val raw = result.data?.getStringExtra(QrScanActivity.RESULT_QR)
        if (result.resultCode != RESULT_OK || raw == null) return@registerForActivityResult
        val qr = QrData.parse(raw)
        if (qr == null) {
            Toast.makeText(this, QrData.parseFailureHintRes(raw)?.let { getString(it) } ?: getString(R.string.main_invalid_qr), Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        LogBus.log("[onboarding] dash QR saved: ssid=${qr.ssid} modelId=${qr.modelId}")
        BikeMemory.save(this, raw, qr)
        BikePicker.markZ150X(this, raw)
        finishOnboarding(connect = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContentView(R.layout.activity_onboarding)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.ob_root)) { v, insets ->
            val b = insets.getInsets(SafeInsets.BARS)
            val pad = (24 * resources.displayMetrics.density).toInt()
            v.setPadding(b.left + pad, b.top + pad / 2, b.right + pad, b.bottom + pad * 3 / 4)
            insets
        }
        progress = findViewById(R.id.ob_progress)
        steps = listOf(R.id.ob_step0, R.id.ob_step1, R.id.ob_step2, R.id.ob_step3).map { findViewById(it) }
        listOf(R.id.ob_lang_title, R.id.ob_perm_title, R.id.ob_aa_title, R.id.ob_scan_title)
            .forEach { redDot(findViewById(it)) }

        findViewById<View>(R.id.ob_lang_ar).setOnClickListener { pickLanguage("ar") }
        findViewById<View>(R.id.ob_lang_en).setOnClickListener { pickLanguage("en") }
        findViewById<View>(R.id.ob_perm_allow).setOnClickListener { askPermissions() }
        findViewById<View>(R.id.ob_aa_open).setOnClickListener { openAndroidAuto() }
        findViewById<View>(R.id.ob_aa_next).setOnClickListener { showStep(3) }
        findViewById<View>(R.id.ob_scan_start).setOnClickListener {
            try { scanLauncher.launch(Intent(this, QrScanActivity::class.java)) } catch (e: Exception) {
                LogBus.log("[onboarding] scan launch failed: $e")
            }
        }
        findViewById<View>(R.id.ob_scan_later).setOnClickListener { finishOnboarding(connect = false) }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (step > 0) showStep(step - 1) else finish()
            }
        })
        showStep(Onboarding.prefs(this).getInt(Onboarding.KEY_STEP, 0), animate = false)
    }

    override fun onResume() {
        super.onResume()
        if (step == 1) renderPermissionChecks()
        if (step == 2) renderAaButton()
    }

    override fun onDestroy() {
        stopAnimations()
        super.onDestroy()
    }

    private fun showStep(index: Int, animate: Boolean = true) {
        step = index.coerceIn(0, 3)
        Onboarding.prefs(this).edit().putInt(Onboarding.KEY_STEP, step).apply()
        progress.step = step
        steps.forEachIndexed { i, v -> v.visibility = if (i == step) View.VISIBLE else View.GONE }
        stopAnimations()
        val shown = steps[step]
        if (animate && ValueAnimator.areAnimatorsEnabled()) {
            shown.alpha = 0f
            shown.translationY = 16 * resources.displayMetrics.density
            shown.animate().alpha(1f).translationY(0f).setDuration(320).start()
        }
        when (step) {
            1 -> { renderPermissionChecks(); slideInRows() }
            2 -> { renderAaButton(); startAaAnimations() }
            3 -> startScanAnimation()
        }
    }

    // ── Step 1: language ────────────────────────────────────────────────────────────────────

    private fun pickLanguage(lang: String) {
        val current = resources.configuration.locales[0].language
        if (current == lang) { showStep(1); return }
        // AppCompat recreates this activity in the new language; come back on step 2.
        Onboarding.prefs(this).edit().putInt(Onboarding.KEY_STEP, 1).apply()
        AppLanguage.set(lang)
    }

    // ── Step 2: permissions ────────────────────────────────────────────────────────────────

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun wanted(): List<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        // Must be asked together with precise location on Android 12+, or the request is ignored.
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun renderPermissionChecks() {
        val nearby = (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || granted(Manifest.permission.BLUETOOTH_CONNECT)) &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || granted(Manifest.permission.NEARBY_WIFI_DEVICES))
        val notif = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || granted(Manifest.permission.POST_NOTIFICATIONS)
        fun show(id: Int, ok: Boolean) { findViewById<View>(id).visibility = if (ok) View.VISIBLE else View.GONE }
        show(R.id.ob_ok_nearby, nearby)
        show(R.id.ob_ok_location, granted(Manifest.permission.ACCESS_FINE_LOCATION))
        show(R.id.ob_ok_camera, granted(Manifest.permission.CAMERA))
        show(R.id.ob_ok_notif, notif)
        val missing = wanted().any { !granted(it) }
        findViewById<TextView>(R.id.ob_perm_allow).setText(if (missing) R.string.z_allow_all else R.string.z_continue)
    }

    private fun askPermissions() {
        val missing = wanted().filterNot { granted(it) }
        if (missing.isEmpty()) showStep(2) else permLauncher.launch(missing.toTypedArray())
    }

    private fun slideInRows() {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        val rows = findViewById<android.view.ViewGroup>(R.id.ob_perm_rows)
        val dy = 14 * resources.displayMetrics.density
        for (i in 0 until rows.childCount) {
            rows.getChildAt(i).apply {
                alpha = 0f
                translationY = dy
                animate().alpha(1f).translationY(0f).setStartDelay(120L * i + 150).setDuration(420).start()
            }
        }
    }

    // ── Step 3: Android Auto developer switch ─────────────────────────────────────────────────

    private fun renderAaButton() {
        findViewById<TextView>(R.id.ob_aa_open).setText(
            if (HeadUnitServer.androidAutoInstalled(this)) R.string.z_aa_open else R.string.z_aa_install,
        )
    }

    private fun openAndroidAuto() {
        if (HeadUnitServer.androidAutoInstalled(this)) {
            HeadUnitServer.openSettings(this)
            return
        }
        val pkg = "com.google.android.projection.gearhead"
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")))
        } catch (_: Exception) {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg"))) }
            catch (_: Exception) { Toast.makeText(this, R.string.open_failed, Toast.LENGTH_SHORT).show() }
        }
    }

    private fun startAaAnimations() {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        val tap = findViewById<View>(R.id.ob_aa_tap)
        running += ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 450
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                val f = it.animatedFraction
                tap.scaleX = 0.3f + 1.3f * f
                tap.scaleY = tap.scaleX
                tap.alpha = 0.8f * (1f - f)
            }
            start()
        }
        animateToggle(R.id.ob_aa_track1, R.id.ob_aa_knob1, 0f)
        animateToggle(R.id.ob_aa_track2, R.id.ob_aa_knob2, 0.2f)
    }

    /** Off for 30% of a 3 s loop, slides on, stays on; [offset] staggers the second switch. */
    private fun animateToggle(trackId: Int, knobId: Int, offset: Float) {
        val track = findViewById<View>(trackId)
        val knob = findViewById<View>(knobId)
        val travel = 20 * resources.displayMetrics.density * (if (track.layoutDirection == View.LAYOUT_DIRECTION_RTL) -1 else 1)
        val offColor = ContextCompat.getColor(this, R.color.ring_track)
        val onColor = ContextCompat.getColor(this, R.color.brand_orange)
        val argb = ArgbEvaluator()
        running += ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3000
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                val f = (it.animatedFraction + 1f - offset) % 1f
                val k = ((f - 0.30f) / 0.15f).coerceIn(0f, 1f)
                knob.translationX = travel * k
                track.backgroundTintList = ColorStateList.valueOf(argb.evaluate(k, offColor, onColor) as Int)
            }
            start()
        }
    }

    // ── Step 4: scan the dash ───────────────────────────────────────────────────────────────

    private fun startScanAnimation() {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        val line = findViewById<View>(R.id.ob_scan_line)
        running += ObjectAnimator.ofFloat(line, View.TRANSLATION_Y, 0f, 178 * resources.displayMetrics.density).apply {
            duration = 2000
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
    }

    private fun stopAnimations() {
        running.forEach { it.cancel() }
        running.clear()
    }

    private fun finishOnboarding(connect: Boolean) {
        Onboarding.markDone(this)
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_WIDGET_CONNECT, connect),
        )
        finish()
    }

    companion object {
        fun start(ctx: Context) = ctx.startActivity(Intent(ctx, OnboardingActivity::class.java))
    }

    /** Titles end in a red full stop ("LET'S RIDE."), like the wordmark's red X. */
    private fun redDot(tv: TextView) {
        val t = tv.text.toString()
        if (!t.endsWith(".")) return
        tv.text = SpannableString(t).apply {
            setSpan(
                ForegroundColorSpan(ContextCompat.getColor(this@OnboardingActivity, R.color.brand_orange)),
                t.length - 1, t.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }
}
