package dev.zanderp.opencfmoto

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Share Logs: this session, the crash report and the tail of earlier sessions as one text file. */
object LogShare {
    fun share(activity: Activity) {
        try {
            CrashGuard.persistSession(activity)
            val dir = File(activity.cacheDir, "logs").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            // .txt, not .log: some apps (Claude among them) only accept known document extensions.
            val file = File(dir, "z150x-log-$stamp.txt")
            // ONE text file: this session first (what we almost always need), then the crash report
            // and earlier sessions below clear separators. Separate attachments confused which one
            // was "the" log (field feedback, 8 Oct 2026).
            file.bufferedWriter().use { w ->
                w.write(LogBus.snapshot())
                val crash = CrashGuard.crashFile(activity)
                if (crash.exists() && crash.length() > 0L) {
                    w.write("\n\n===== CRASH REPORT (${crash.name}) =====\n")
                    w.write(crash.readText())
                    LogBus.log("including crash report: ${crash.name} (${crash.length()} bytes)")
                }
                // Previous sessions (rides whose process was killed afterwards), newest first,
                // only their tail: the end of a session is where it went wrong.
                CrashGuard.historyDir(activity).listFiles()?.sortedByDescending { it.name }?.take(2)?.forEach { h ->
                    val text = h.readText()
                    w.write("\n\n===== EARLIER SESSION (${h.name}) =====\n")
                    w.write(if (text.length > 80_000) "…\n" + text.takeLast(80_000) else text)
                }
            }
            val uris = ArrayList<Uri>()
            uris.add(FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file))

            // Attach any diagnostic H.264 dumps (VideoPipeline writes these to <externalFiles>/video).
            val videoDir = File(activity.getExternalFilesDir(null), "video")
            val dumps = videoDir.listFiles { f -> f.name.endsWith(".h264") }?.sortedBy { it.name } ?: emptyList()
            for (d in dumps) {
                uris.add(FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", d))
                LogBus.log("attaching video dump: ${d.name} (${d.length()} bytes)")
            }

            val send = Intent(if (uris.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND).apply {
                type = if (uris.size > 1) "*/*" else "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, activity.getString(R.string.code_share_log_subject, stamp))
                if (uris.size > 1) putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                else putExtra(Intent.EXTRA_STREAM, uris[0])
                // Some apps (Claude among them) only read the shared file through ClipData; without it
                // they open with nothing attached. ClipData also carries the read grant.
                clipData = android.content.ClipData.newRawUri(file.name, uris[0]).also { c ->
                    uris.drop(1).forEach { c.addItem(android.content.ClipData.Item(it)) }
                }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(Intent.createChooser(send, activity.getString(R.string.code_btn_share_logs)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            LogBus.log("log saved: ${file.absolutePath} (${file.length()} bytes)")
        } catch (e: Exception) {
            LogBus.log("share failed: $e")
        }
    }
}
