
package dev.zanderp.opencfmoto

import android.app.Activity
import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * v2.3 · "Which bike is it?" — asked once after scanning a new bike's QR (and from the Garage).
 * The choice names the bike and, for confirmed models, applies the screen setup we know works
 * ([KnownDashes]) even when the dash's QR doesn't identify the model. "Other" keeps automatic.
 */
object BikePicker {
    enum class Model(val label: String, val dashKey: String?) {
        // Z150X: its 5" TFT panel isn't in [KnownDashes] yet — learned automatically on first connect.
        Z150X("Zontes Z150X", null),
        OTHER("", null),
    }

    private const val PREFS = "bike_model"

    private fun key(raw: String) = "m_" + raw.hashCode().toString(16)

    fun modelFor(ctx: Context, raw: String?): Model? {
        if (raw == null) return null
        val name = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key(raw), null)
        return name?.let { runCatching { Model.valueOf(it) }.getOrNull() }
    }

    /** The selected bike's model, if the rider picked one. */
    fun selected(ctx: Context): Model? = modelFor(ctx, BikeMemory.lastRaw(ctx))

    private fun set(ctx: Context, raw: String, model: Model) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(key(raw), model.name).apply()
        if (model.label.isNotEmpty()) BikeMemory.rename(ctx, raw, model.label)
        LogBus.log("[bike] model for this bike: ${model.name}${model.dashKey?.let { " → known screen setup '$it'" } ?: " (automatic)"}")
    }

    /** Z150X app: a newly scanned dash is a Z150X; the Garage can still change it. */
    fun maybeAsk(activity: Activity, raw: String, onDone: () -> Unit = {}) {
        if (modelFor(activity, raw) == null) markZ150X(activity, raw)
        onDone()
    }

    fun markZ150X(ctx: Context, raw: String) = set(ctx, raw, Model.Z150X)

    fun show(activity: Activity, raw: String, onDone: () -> Unit = {}) {
        if (activity.isFinishing || activity.isDestroyed) { onDone(); return }
        val models = Model.values()
        val labels = models.map { if (it == Model.OTHER) activity.getString(R.string.bike_picker_other) else it.label }.toTypedArray()
        val current = modelFor(activity, raw)?.let { models.indexOf(it) } ?: -1
        var done = false
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.bike_picker_title)
            .setSingleChoiceItems(labels, current) { d, which ->
                set(activity, raw, models[which])
                done = true
                d.dismiss()
            }
            .setOnDismissListener { if (!done) set(activity, raw, Model.OTHER); onDone() }
            .show()
    }
}
