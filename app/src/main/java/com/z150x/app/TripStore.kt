
package com.z150x.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Persists recorded rides as one JSON file per trip under `filesDir/trips/`.
 *
 * A file per trip keeps writes cheap (no rewriting a growing master list) and makes delete a single
 * unlink. Every file this store writes (a trip, the index, the ride-in-progress checkpoint) goes
 * through [writeAtomic]: temp file in the same folder, flush + fsync, then a rename over the target,
 * so a crash or a full disk leaves either the old file or the new one, never half of one. A trip is
 * only added to the index once its file is on disk, and [save] reports whether that happened.
 * Each file holds the summary plus the full GPS track so the map can redraw the route offline.
 * Points are stored as compact `[lat, lon, timeMs, speedMs]` arrays.
 */
object TripStore {
    /**
     * Replace [target] with [text] without ever exposing a half-written file: the bytes go to
     * `<name>.tmp` next to it, are flushed and fsynced, and only then renamed over [target]. On any
     * failure the temp file is removed, the previous [target] is left as it was and an [IOException]
     * says why. Plain java.io (no Android types) so it is unit-tested on the JVM.
     */
    @Throws(IOException::class)
    internal fun writeAtomic(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(target)) throw IOException("could not rename ${tmp.name} to ${target.name}")
        } catch (e: Exception) {
            tmp.delete()
            throw if (e is IOException) e else IOException(e)
        }
    }

    private fun dir(ctx: Context): File =
        File(ctx.applicationContext.filesDir, "trips").apply { if (!exists()) mkdirs() }

    // A small index of trip summaries (no GPS points) so lists, records, the widget
    // and the watch don't parse every track. Lives outside trips/ so list() never sees it.
    private fun indexFile(ctx: Context) = File(ctx.applicationContext.filesDir, "trips-index.json")
    @Volatile private var summaryCache: List<Trip>? = null

    /** All trips without their points (cheap), most recent first. Use [get] for a full track. */
    @Synchronized
    fun summaries(ctx: Context): List<Trip> {
        summaryCache?.let { return it }
        val files = dir(ctx).listFiles { f -> f.name.endsWith(".json") } ?: emptyArray()
        val ids = files.map { it.name.removeSuffix(".json") }.toSet()
        val fromIndex = runCatching { readIndex(indexFile(ctx)) }.getOrNull()
        val result = if (fromIndex != null && fromIndex.map { it.id }.toSet() == ids) {
            fromIndex
        } else {
            // First run (or files changed behind our back): rebuild once from the full files.
            files.mapNotNull { f -> parse(f)?.copy(points = emptyList()) }.also { writeIndex(ctx, it) }
        }.sortedByDescending { it.start }
        summaryCache = result
        return result
    }

    @Synchronized
    private fun updateIndex(ctx: Context, change: (MutableList<Trip>) -> Unit) {
        val current = summaryCache ?: runCatching { readIndex(indexFile(ctx)) }.getOrNull()
        if (current == null) {
            summaryCache = null // rebuilt lazily by summaries()
            return
        }
        val list = current.toMutableList()
        change(list)
        val sorted = list.sortedByDescending { it.start }
        writeIndex(ctx, sorted)
        summaryCache = sorted
    }

    private fun readIndex(f: File): List<Trip>? {
        if (!f.exists()) return null
        val a = JSONArray(f.readText())
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Trip(
                id = o.getString("id"), start = o.optLong("start"), end = o.optLong("end"),
                distanceMeters = o.optDouble("distanceMeters"), movingTimeMs = o.optLong("movingTimeMs"),
                maxSpeedMs = o.optDouble("maxSpeedMs").toFloat(), points = emptyList(),
            )
        }
    }

    private fun writeIndex(ctx: Context, trips: List<Trip>) {
        val a = JSONArray()
        for (t in trips) {
            a.put(
                JSONObject().put("id", t.id).put("start", t.start).put("end", t.end)
                    .put("distanceMeters", t.distanceMeters).put("movingTimeMs", t.movingTimeMs)
                    .put("maxSpeedMs", t.maxSpeedMs.toDouble())
            )
        }
        // If this fails the in-memory list is still right, and the next start notices the stale
        // index (its ids no longer match the files) and rebuilds it.
        try {
            writeAtomic(indexFile(ctx), a.toString())
        } catch (e: Exception) {
            LogBus.log("[trip] could not write the rides index: ${e.message ?: e}")
        }
    }

    /**
     * Writes the trip to its own file ([writeAtomic]) and then adds it to the index. Returns false,
     * after a `[trip]` log line, when the file could not be written: nothing is indexed and
     * whatever was on disk before is untouched, so the caller must not treat the ride as saved.
     */
    fun save(ctx: Context, trip: Trip): Boolean {
        try {
            val obj = JSONObject()
                .put("id", trip.id)
                .put("start", trip.start)
                .put("end", trip.end)
                .put("distanceMeters", trip.distanceMeters)
                .put("movingTimeMs", trip.movingTimeMs)
                .put("maxSpeedMs", trip.maxSpeedMs.toDouble())
            val pts = JSONArray()
            for (p in trip.points) {
                pts.put(JSONArray().put(p.lat).put(p.lon).put(p.time).put(p.speedMs.toDouble()))
            }
            obj.put("points", pts)
            writeAtomic(File(dir(ctx), "${trip.id}.json"), obj.toString())
        } catch (e: Exception) {
            LogBus.log("[trip] SAVE FAILED for ride ${trip.id} (${trip.points.size} points): ${e.message ?: e}")
            return false
        }
        updateIndex(ctx) { l -> l.removeAll { it.id == trip.id }; l.add(trip.copy(points = emptyList())) }
        return true
    }

    /** All saved trips, most recent first. */
    fun list(ctx: Context): List<Trip> {
        val files = dir(ctx).listFiles { f -> f.name.endsWith(".json") } ?: return emptyList()
        return files.mapNotNull { parse(it) }.sortedByDescending { it.start }
    }

    fun get(ctx: Context, id: String): Trip? {
        val f = File(dir(ctx), "$id.json")
        return if (f.exists()) parse(f) else null
    }

    fun delete(ctx: Context, id: String) {
        runCatching { File(dir(ctx), "$id.json").delete() }
        updateIndex(ctx) { l -> l.removeAll { it.id == id } }
    }

    // ── v2.3: the ride in progress, written every ~30 s by TripRecorder. If the app process dies mid-ride
    // (22 Oct 2026: a native crash while restarting the video decoder) the ride used to exist only
    // in memory and was lost. On the next start [recoverCheckpoint] saves it as a normal trip.

    private fun checkpointFile(ctx: Context) = File(ctx.applicationContext.filesDir, "trip-in-progress.json")

    fun writeCheckpoint(ctx: Context, trip: Trip) {
        try {
            val obj = JSONObject()
                .put("id", trip.id).put("start", trip.start).put("end", trip.end)
                .put("distanceMeters", trip.distanceMeters).put("movingTimeMs", trip.movingTimeMs)
                .put("maxSpeedMs", trip.maxSpeedMs.toDouble())
            val pts = JSONArray()
            for (p in trip.points) pts.put(JSONArray().put(p.lat).put(p.lon).put(p.time).put(p.speedMs.toDouble()))
            obj.put("points", pts)
            writeAtomic(checkpointFile(ctx), obj.toString())
        } catch (e: Exception) {
            LogBus.log("[trip] could not write the ride-in-progress checkpoint: ${e.message ?: e}")
        }
    }

    fun clearCheckpoint(ctx: Context) {
        runCatching {
            val f = checkpointFile(ctx)
            f.delete()
            File(f.parentFile, f.name + ".tmp").delete()
        }
    }

    /**
     * A ride left behind by a process that died while recording, if any. The file is left in place:
     * the caller removes it with [clearCheckpoint] once the ride is safely saved (or not worth keeping).
     */
    fun readCheckpoint(ctx: Context): Trip? {
        val f = checkpointFile(ctx)
        return if (f.isFile) parse(f) else null
    }

    private fun parse(file: File): Trip? = runCatching {
        val o = JSONObject(file.readText())
        val ptsArr = o.optJSONArray("points") ?: JSONArray()
        val points = ArrayList<TrackPoint>(ptsArr.length())
        for (i in 0 until ptsArr.length()) {
            val a = ptsArr.optJSONArray(i) ?: continue
            points.add(
                TrackPoint(
                    lat = a.optDouble(0),
                    lon = a.optDouble(1),
                    time = a.optLong(2),
                    speedMs = a.optDouble(3).toFloat(),
                )
            )
        }
        Trip(
            id = o.optString("id"),
            start = o.optLong("start"),
            end = o.optLong("end"),
            distanceMeters = o.optDouble("distanceMeters"),
            movingTimeMs = o.optLong("movingTimeMs"),
            maxSpeedMs = o.optDouble("maxSpeedMs").toFloat(),
            points = points,
        )
    }.getOrNull()
}
