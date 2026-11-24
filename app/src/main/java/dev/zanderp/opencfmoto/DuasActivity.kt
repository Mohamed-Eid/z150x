package dev.zanderp.opencfmoto

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat

/** The ride duas as a checklist for today: read each one and tap it off. */
class DuasActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContentView(R.layout.activity_duas)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.duas_root)) { v, insets ->
            val b = insets.getInsets(SafeInsets.BARS)
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        list = findViewById(R.id.duas_list)
        findViewById<View>(R.id.duas_reset).setOnClickListener { RideDuas.reset(this); render() }
        RideDuas.cancelReminder(this)
        render()
    }

    private fun render() {
        val done = RideDuas.doneToday(this)
        val total = RideDuas.ALL.size
        val count = RideDuas.ALL.count { it.id in done }
        findViewById<TextView>(R.id.duas_progress_text).text =
            if (count == total) getString(R.string.duas_all_done) else getString(R.string.duas_progress, count, total)
        findViewById<ProgressBar>(R.id.duas_progress).apply { max = total; progress = count }

        list.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (dua in RideDuas.ALL) {
            val card = inflater.inflate(R.layout.item_dua, list, false)
            val isDone = dua.id in done
            card.findViewById<TextView>(R.id.dua_title).setText(dua.title)
            card.findViewById<TextView>(R.id.dua_text).setText(dua.text)
            card.findViewById<TextView>(R.id.dua_note).apply {
                if (dua.note != null) setText(dua.note) else visibility = View.GONE
            }
            card.findViewById<TextView>(R.id.dua_button).apply {
                setText(if (isDone) R.string.duas_done else R.string.duas_mark)
                setBackgroundResource(if (isDone) R.drawable.bg_z_dua_done else R.drawable.bg_z_outline_btn)
                setTextColor(ContextCompat.getColor(context, if (isDone) R.color.white else R.color.text_primary))
                setOnClickListener { RideDuas.toggle(this@DuasActivity, dua.id); render() }
            }
            card.alpha = if (isDone) 0.7f else 1f
            list.addView(card)
        }
    }

    companion object {
        fun start(ctx: Context) = ctx.startActivity(Intent(ctx, DuasActivity::class.java))
    }
}
