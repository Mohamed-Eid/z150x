package com.z150x.app

import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/** Launcher: the scooter rides in under the wordmark and the Basmala, then Home or onboarding. */
class SplashActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val next = Runnable { route() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContentView(R.layout.activity_splash)
        findViewById<TextView>(R.id.splash_mark).apply {
            val t = getString(R.string.z_brand)
            text = SpannableString(t).apply {
                setSpan(
                    ForegroundColorSpan(ContextCompat.getColor(this@SplashActivity, R.color.brand_orange)),
                    t.length - 1, t.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
        findViewById<RideStripView>(R.id.splash_strip).mode = RideStripView.Mode.ARRIVE
        val words = findViewById<android.view.View>(R.id.splash_words)
        if (ValueAnimator.areAnimatorsEnabled()) {
            words.alpha = 0f
            words.translationY = 12 * resources.displayMetrics.density
            words.animate().alpha(1f).translationY(0f).setStartDelay(700).setDuration(700).start()
        }
        handler.postDelayed(next, if (ValueAnimator.areAnimatorsEnabled()) SPLASH_MS else 600L)
    }

    override fun onDestroy() {
        handler.removeCallbacks(next)
        super.onDestroy()
    }

    private fun route() {
        if (isFinishing) return
        startActivity(
            if (Onboarding.isDone(this)) Intent(this, MainActivity::class.java)
            else Intent(this, OnboardingActivity::class.java),
        )
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private companion object {
        const val SPLASH_MS = 2_600L
    }
}
