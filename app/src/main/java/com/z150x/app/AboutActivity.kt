
package com.z150x.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton

/** Who made the app, how to reach him, the disclaimer and the short legal notice. */
class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_about)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.about_root)) { v, insets ->
            val b = insets.getInsets(SafeInsets.BARS)
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }

        findViewById<TextView>(R.id.about_version).text =
            getString(R.string.code_about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)

        findViewById<TextView>(R.id.btn_about_license).setOnClickListener { openUrl(URL_SOURCE) }
        findViewById<TextView>(R.id.btn_about_privacy).setOnClickListener { openUrl(URL_PRIVACY) }
        findViewById<MaterialButton>(R.id.btn_about_whatsapp).setOnClickListener { openUrl(URL_AUTHOR_WHATSAPP) }
        findViewById<MaterialButton>(R.id.btn_about_author_github).setOnClickListener { openUrl(URL_AUTHOR_GITHUB) }
        findViewById<MaterialButton>(R.id.btn_about_done).setOnClickListener { finish() }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(this, R.string.code_toast_link_failed, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val URL_AUTHOR_WHATSAPP = "https://wa.me/201275262482"
        private const val URL_AUTHOR_GITHUB = "https://github.com/Mohamed-Eid"
        /** This app's own source, LICENSE and NOTICE (the AGPL source offer). */
        private const val URL_SOURCE = "https://github.com/Mohamed-Eid/z150x"
        private const val URL_PRIVACY = "https://github.com/Mohamed-Eid/z150x/blob/main/PRIVACY.md"

        fun start(ctx: Context) {
            ctx.startActivity(Intent(ctx, AboutActivity::class.java))
        }
    }
}
