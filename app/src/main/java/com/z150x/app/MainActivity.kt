
package com.z150x.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.text.method.ScrollingMovementMethod
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (RemotePad.consume(this, event)) return true
        return super.dispatchKeyEvent(event)
    }

    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var logPanel: View
    private lateinit var statusView: TextView
    private lateinit var statusDot: View
    private lateinit var bikeView: TextView
    private lateinit var connectBtn: ConnectRingView
    private lateinit var ringLabel: TextView
    private lateinit var ringSub: TextView
    private lateinit var ringIcon: android.widget.ImageView
    private lateinit var rideStrip: RideStripView
    private lateinit var prober: EasyConnProber
    private val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    /** Guards the "close the official CFMoto app" prompt so it shows once per error, not every redraw. */
    private var rivalPromptShown = false
    /** Guards the VPN kill-switch prompt so it shows once per error, not every redraw. */
    private var vpnPromptShown = false

    private val scanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val raw = result.data?.getStringExtra(QrScanActivity.RESULT_QR)
        if (result.resultCode != RESULT_OK || raw == null) {
            log("QR scan cancelled")
            return@registerForActivityResult
        }
        log("QR raw: $raw")
        val qr = QrData.parse(raw)
        if (qr == null) {
            val hint = QrData.parseFailureHintRes(raw)?.let { getString(it) }
            log("QR parse FAILED — missing ssid/pwd?" + (hint?.let { " ($it)" } ?: ""))
            Toast.makeText(
                this,
                hint ?: getString(R.string.main_invalid_qr),
                Toast.LENGTH_LONG,
            ).show()
            return@registerForActivityResult
        }
        log(
            "QR parsed: ssid=${qr.ssid} mac=${qr.mac} action=${qr.action} " +
                "(ap=${qr.supportsAp}, p2p=${qr.supportsP2p}, hotspot=${qr.supportsPhoneHotspot}) " +
                "modelId=${qr.modelId} sn=${qr.sn}"
        )
        // Remember this bike so the next ride is a one-tap reconnect (skips the scan entirely).
        BikeMemory.save(this, raw, qr)
        refreshBikeLabel()
        // v2.3: which bike is it? (once per bike; names it and applies known screen setups)
        BikePicker.maybeAsk(this, raw) { refreshBikeLabel() }

        startAaFlow(qr)
    }

    /** Pick the bike profile from the QR up front — it drives the Android Auto resolution/orientation,
     *  which must be set before AA starts. CLIENT_INFO refines it later during the PXC handshake. */
    private fun applyProfile(qr: QrData) {
        AppSettings.applyToHolder(this)
        BikeProfileHolder.active = BikeProfiles.selectByQr(qr, this)
        DashMemory.setLastDashTouch(this, BikeProfileHolder.advertisesScreenTouch)
        val userOverride = VideoPrefs.resolutionOverride(this, BikeProfileHolder.active)
        // In AUTO mode, if a previous session revealed this dash is a different orientation than the
        // profile assumes, flip AA to match. Learned from the dash's REQ_CONFIG_CAPTURE (see DashMemory).
        val autoGeo = if (userOverride == null) DashMemory.specFor(this, qr.ssid, BikeProfileHolder.active) else null
        BikeProfileHolder.aaVideoOverride = userOverride ?: autoGeo
        val spec = BikeProfileHolder.aaVideo
        BikeProfileHolder.aaContentMargins = VideoPrefs.aaMarginsFor(this, spec, qr.ssid)
        val aspectMode = VideoPrefs.matchAspectMode(this)
        val aspectNote = BikeProfileHolder.aaContentMargins.let { m ->
            when {
                m.any -> " [match-aspect ${aspectMode.name}: margins ${m.marginW}x${m.marginH}]"
                aspectMode == MatchAspectMode.OFF -> " [match-aspect OFF]"
                VideoPrefs.detectedPanelSize(this) == null -> " [match-aspect: waiting for panel size]"
                else -> " [match-aspect ${aspectMode.name}: margins 0]"
            }
        }
        val note = when {
            userOverride != null -> " (override: ${VideoPrefs.resolution(this).label})"
            autoGeo != null -> " (auto-orientation from last connect)"
            else -> ""
        }
        val touchNote = when {
            BikeProfileHolder.forceNonTouch -> " [Disable touchscreen ON — focus/knob AA]"
            BikeProfileHolder.forceTouch -> " [Force touchscreen ON — touch AA]"
            else -> ""
        }
        val ov = BikeProfileHolder.profileOverride
        val ovNote = if (ov != ProfileOverride.AUTO) " [profile override: ${ov.shortLabel}]" else ""
        log("→ bike profile (QR ssid=${qr.ssid} modelId=${qr.modelId}): ${BikeProfileHolder.active.name} " +
            "→ AA ${spec.width}x${spec.height} @${spec.dpi}dpi$note$touchNote$ovNote$aspectNote")
    }

    /**
     * Stop any previous dash projection so a fresh Connect opens a clean PXC session.
     * Keeps bike Wi‑Fi (fast re-probe).
     */
    private fun tearDownForModeSwitch() {
        log("→ mode switch: stop previous projection (keep Wi‑Fi)")
        try { AaVideoBridge.onSteadyVideo = null } catch (_: Exception) {}
        AaVideoBridge.pipeline = null
        AaVideoBridge.touchSink = null
        AaVideoBridge.keySink = null
        AaVideoBridge.scrollSink = null
        AaVideoBridge.previewTouchSink = null
        AaVideoBridge.nightSink = null
        try { AndroidAutoService.stop(this) } catch (e: Exception) { log("AA stop: $e") }
        try { BikeLink.prober?.stop() } catch (e: Exception) { log("prober stop: $e") }
        try {
            if (::prober.isInitialized && BikeLink.prober !== prober) prober.stop()
        } catch (_: Exception) {}
        BikeLink.beginHandoff(this)
    }

    /** Start the Android Auto → bike projection for [qr]. Shared by the one-tap Connect reconnect
     *  and a fresh scan, so both paths behave identically. */
    private fun startAaFlow(qr: QrData) {
        connectStartedAt = android.os.SystemClock.elapsedRealtime()
        try {
            if (!WifiGate.ensureEnabledOrPrompt(this)) {
                pendingAaQr = qr
                return
            }
            // AA Connect needs Nearby + Bluetooth so HUR (START_WIRELESS_PROJECTION) can run when
            // WirelessStartupActivity is a no-op (common on AA 16.4+/17.4+).
            if (!ensureNearbyBtForAaConnect()) {
                pendingAaQr = qr
                return
            }
            if (!ensureMicForAaConnect()) {
                pendingAaQr = qr
                return
            }
            if (!ensureLocalNetworkForConnect()) {
                pendingAaQr = qr
                return
            }
            pendingAaQr = null
            BikeWatchService.stop(this)
            // A stale PXC session must die first — half-switches leave framesSent=0.
            tearDownForModeSwitch()
            applyProfile(qr)
            val bikeName = BikeMemory.lastBikeName(this) ?: qr.ssid
            ConnectionState.set(Phase.STARTING_AA, bikeName)
            log("→ starting Android Auto receiver (loopback self-mode). Ensure Android Auto is installed & set up.")

            // Parallel startup: the two slow steps (AA reaching steady video, and the user accepting the
            // bike Wi-Fi dialog) now overlap. [BikeLink] gates the actual bike probe until BOTH complete,
            // so the bike is never contacted before AA has frames to serve. These callbacks fire against
            // process-global state (applicationContext + BikeLink.prober), NOT this activity: launching
            // Google AA can destroy/recreate MainActivity mid-startup and the hand-off must still finish.
            BikeLink.beginHandoff(this)
            AaVideoBridge.onSteadyVideo = {
                AaVideoBridge.onSteadyVideo = null
                ConnectionState.set(Phase.AA_VIDEO_LIVE)
                LogBus.log("→ Android Auto video is live")
                AndroidAutoService.upgradeForegroundForMicrophone()
                BikeLink.markAaVideoSteady()
            }
            AndroidAutoService.start(this)
            // Trigger Google AA to project from the FOREGROUND activity (background-activity-launch
            // safe on Android 12+/15), after giving the service's :5288 server time to bind.
            // AA 16.4+/17.4+ often need the broadcast fallbacks; retry once if video never arrives
            // (common when the first WirelessStartupReceiver is ignored).
            logView.postDelayed({
                try {
                    com.z150x.app.aa.AaSelfMode.trigger(this, log = ::log)
                } catch (e: Exception) {
                    log("AA self-mode trigger failed: $e")
                }
            }, 900)
            // Additive retry for AA 16.4+/17.4+ that ignore the first broadcast — skipped if
            // a session is already live (re-firing START_WIRELESS_PROJECTION kills AAP).
            logView.postDelayed({
                if (aaAlreadyLiveOrTerminal()) {
                    if (AaVideoBridge.aaSessionLive || AaVideoBridge.aaDecoding) {
                        log("[AA] skip re-trigger — AA already connected/decoding")
                    }
                    return@postDelayed
                }
                try {
                    log("[AA] no video yet — re-triggering self-mode (AA 16.4+/17.4+ fallbacks)")
                    com.z150x.app.aa.AaSelfMode.trigger(this, log = ::log)
                } catch (e: Exception) {
                    log("AA self-mode re-trigger failed: $e")
                }
            }, 4_500)
            // Give up the silent black/QR reconnect loop — surface a clear error after several tries.
            logView.postDelayed({
                val p = ConnectionState.phase
                if (p == Phase.IDLE || p == Phase.STOPPED || p == Phase.ERROR ||
                    p == Phase.STREAMING || p == Phase.MIRRORING
                ) return@postDelayed
                if (AaVideoBridge.aaSessionLive || AaVideoBridge.aaDecoding) return@postDelayed
                // Still no AA after retries — stop thrashing (bike SoftAP may already be up).
                log("[AA] Android Auto never attached — stopping silent retry")
                val detail = if (AaVideoBridge.husWedged) {
                    getString(R.string.conn_detail_hus_wedged)
                } else if (AaVideoBridge.aaSessionSeen) {
                    getString(R.string.conn_detail_aa_dropped)
                } else {
                    getString(R.string.conn_detail_aa_not_started)
                }
                ConnectionState.set(Phase.ERROR, detail)
            }, 18_000)
            // Kick off the Wi-Fi join right away, in parallel with AA boot.
            joinWifi(qr, gateOnAaSteady = true)
        } catch (e: Exception) {
            log("Connect failed (app stays open): $e")
            CrashGuard.persistSession(this)
            ConnectionState.set(Phase.ERROR, getString(R.string.conn_detail_connect_failed))
        }
    }

    private fun aaAlreadyLiveOrTerminal(): Boolean {
        val p = ConnectionState.phase
        return AaVideoBridge.aaSessionLive || AaVideoBridge.aaDecoding ||
            p == Phase.AA_VIDEO_LIVE || p == Phase.PXC_CONNECTING || p == Phase.STREAMING ||
            p == Phase.IDLE || p == Phase.STOPPED || p == Phase.ERROR ||
            p == Phase.MIRRORING
    }

    /**
     * Block AA Connect until Nearby (API 31+) is granted and Bluetooth is on — otherwise HUR
     * never runs and Activity/Receiver alone often leave SoftAP up with no AA on the phone.
     */
    private fun ensureNearbyBtForAaConnect(): Boolean {
        if (com.z150x.app.aa.AaSelfMode.canAttemptHur(this)) return true
        val needNearby = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        if (needNearby) {
            ConnectionState.set(Phase.ERROR, getString(R.string.conn_detail_need_nearby))
            val asked = getSharedPreferences(PREFS_PERMS, MODE_PRIVATE).getBoolean(KEY_BT_ASKED, false)
            val canAsk = !asked || ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.BLUETOOTH_CONNECT)
            if (canAsk) {
                // The system dialog alone; Connect carries on by itself once it's granted.
                getSharedPreferences(PREFS_PERMS, MODE_PRIVATE).edit().putBoolean(KEY_BT_ASKED, true).apply()
                log("→ Nearby devices needed for Android Auto — asking")
                ActivityCompat.requestPermissions(
                    this,
                    // Same «Nearby devices» group: ask the Wi‑Fi half too, Wi‑Fi Direct dashes need it.
                    (listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN) +
                        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) listOf(Manifest.permission.NEARBY_WIFI_DEVICES) else emptyList())).toTypedArray(),
                    REQ_BT_FOR_AA,
                )
            } else {
                // Denied for good: Android won't show its dialog again → straight to the app's permissions.
                log("→ Nearby devices denied permanently — offering app settings")
                showNearbyBtForAaDialog(needNearby = true)
            }
            return false
        }
        // Permission fine, Bluetooth off: one tap on Android's own "turn on Bluetooth?" dialog.
        ConnectionState.set(Phase.ERROR, getString(R.string.conn_detail_need_bt_on))
        log("→ Bluetooth is off — asking to turn it on")
        try {
            @Suppress("MissingPermission")
            btEnableLauncher.launch(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } catch (e: Exception) {
            log("Bluetooth enable request failed: $e")
            showNearbyBtForAaDialog(needNearby = false)
        }
        return false
    }

    // ── 📸 Park with photo (v2.1): optional, separate from Stop. Photo first, then disconnect.
    private val parkPrefs by lazy { getSharedPreferences("park_photo_flow", MODE_PRIVATE) }
    /** True while the camera is open for "Park with photo" (persisted: the camera may outlive us). */
    private var photoThenStop: Boolean
        get() = parkPrefs.getBoolean("then_stop", false)
        set(v) { parkPrefs.edit().putBoolean("then_stop", v).apply() }

    private val takePhotoLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { _ ->
        val thenStop = photoThenStop
        photoThenStop = false
        // Some camera apps report "cancelled" after writing the file: trust the file, not the flag.
        val saved = Parking.commitPendingPhoto(this)
        // Parking usually starts with the bike's Wi-Fi going away: "reconnecting" still counts.
        val live = sessionAlive(ConnectionState.phase)
        if (thenStop && saved && live) {
            log("→ Park with photo: photo taken — saving the spot and disconnecting")
            Parking.saveNow(this)
            stopEverything()
        } else if (thenStop && !saved) {
            log("→ Park with photo: no photo taken — staying connected")
        }
        if (saved) Toast.makeText(this, R.string.park_photo_saved, Toast.LENGTH_SHORT).show()
        renderParked()
    }

    private val cameraPermLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchParkingCamera() else {
            photoThenStop = false
            Toast.makeText(this, R.string.park_photo_need_camera, Toast.LENGTH_LONG).show()
        }
    }

    private fun takeParkingPhoto(thenStop: Boolean) {
        photoThenStop = thenStop
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        launchParkingCamera()
    }

    /** A projection session exists (connected, or reconnecting after the bike's Wi-Fi blipped). */
    private fun sessionAlive(phase: Phase) = phase == Phase.STREAMING || phase == Phase.MIRRORING ||
        ((phase == Phase.RECONNECTING || phase == Phase.WAITING_FOR_BIKE) && AndroidAutoService.isRunning)

    private fun launchParkingCamera() {
        Parking.pendingPhotoFile(this).delete()
        try {
            takePhotoLauncher.launch(Intent(this, ParkingCameraActivity::class.java))
        } catch (e: Exception) {
            log("parking camera failed: $e")
            photoThenStop = false
            Toast.makeText(this, R.string.park_photo_no_camera, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showParkedOptions(spot: Parking.Spot) {
        val hasPhoto = Parking.hasPhoto(this)
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        labels += getString(R.string.parked_walk)
        actions += {
            try { startActivity(Parking.walkIntent(spot)) } catch (_: Exception) {
                Toast.makeText(this, R.string.open_failed, Toast.LENGTH_SHORT).show()
            }
        }
        if (hasPhoto) {
            labels += getString(R.string.parked_view_photo)
            actions += {
                ParkingPhotoActivity.start(this)   // v2.2: in-app viewer, no external gallery app
            }
        }
        labels += getString(if (hasPhoto) R.string.parked_retake_photo else R.string.parked_add_photo)
        actions += { takeParkingPhoto(thenStop = false) }
        labels += getString(R.string.parked_delete)
        actions += {
            Parking.clear(this)
            Toast.makeText(this, R.string.parked_cleared, Toast.LENGTH_SHORT).show()
            renderParked()
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.parked_options_title)
            .setItems(labels.toTypedArray()) { _, i -> actions[i]() }
            .show()
    }

    /** Connect waiting for Nearby / Bluetooth: resumed as soon as both are OK. */
    private var pendingAaQr: QrData? = null

    private val btEnableLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { res ->
        if (res.resultCode == RESULT_OK) resumePendingAaConnect("Bluetooth on")
        else log("→ Bluetooth left off — Connect needs it for Android Auto")
    }

    private fun resumePendingAaConnect(why: String) {
        val qr = pendingAaQr ?: return
        if (!com.z150x.app.aa.AaSelfMode.canAttemptHur(this)) {
            // e.g. permission granted but Bluetooth still off → next step of the same flow
            ensureNearbyBtForAaConnect()
            return
        }
        pendingAaQr = null
        log("→ $why — continuing Connect")
        startAaFlow(qr)
    }

    private var localNetAsked = false

    /**
     * Android 17 · Local network protection: without ACCESS_LOCAL_NETWORK the dash's Wi‑Fi joins
     * fine but every connection to it times out (Galaxy S25 Ultra on Android 17, 20 Oct 2026:
     * "NSD register error: Missing local network permission", :10930 timeouts, port scan empty).
     * It sits in the «Nearby devices» group. Asked at most once per app run; Connect goes on either way.
     */
    private fun ensureLocalNetworkForConnect(): Boolean {
        if (Build.VERSION.SDK_INT < 37) return true
        if (ContextCompat.checkSelfPermission(this, PERM_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED) return true
        if (localNetAsked) {
            log("→ local network access NOT allowed — the dash will not answer (Settings → Apps → Z150X → Permissions → Nearby devices)")
            return true
        }
        localNetAsked = true
        log("→ Android 17: local network access needed to talk to the dash — asking")
        ConnectionState.set(Phase.ERROR, getString(R.string.conn_local_network))
        ActivityCompat.requestPermissions(this, arrayOf(PERM_LOCAL_NETWORK), REQ_LOCAL_NET)
        return false
    }

    /** Asked at most once per app run, so Connect never loops on the microphone question. */
    private var micPromptDone = false

    /**
     * Voice (Ok Google, the intercom / watch voice button) goes through our "head unit microphone".
     * Without RECORD_AUDIO Android Auto opens the Assistant, we answer the mic request with an error
     * and it closes after a blip — the v2 bug. "Ask every time" leaves it ungranted, so ask here,
     * explaining which option to pick. Never blocks Connect: any answer carries on.
     * @return true to continue now; false when a dialog is up (Connect resumes after it).
     */
    private fun ensureMicForAaConnect(): Boolean {
        if (micPromptDone) return true
        val mic = Manifest.permission.RECORD_AUDIO
        if (ContextCompat.checkSelfPermission(this, mic) == PackageManager.PERMISSION_GRANTED) return true
        val prefs = getSharedPreferences(PREFS_PERMS, MODE_PRIVATE)
        val declinedAt = prefs.getLong(KEY_MIC_DECLINED, 0L)
        if (System.currentTimeMillis() - declinedAt < 14L * 24 * 3600_000L) return true
        micPromptDone = true
        if (isFinishing || isDestroyed) return true
        val asked = prefs.getBoolean(KEY_MIC_ASKED, false)
        val blocked = asked && !ActivityCompat.shouldShowRequestPermissionRationale(this, mic)
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.mic_perm_title)
            .setMessage(R.string.mic_perm_message)
            .setPositiveButton(if (blocked) R.string.mic_perm_settings else R.string.mic_perm_allow) { _, _ ->
                prefs.edit().putBoolean(KEY_MIC_ASKED, true).apply()
                if (blocked) {
                    try {
                        startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", packageName, null)))
                    } catch (_: Exception) {}
                    // Connect carries on from onResume (pendingAaQr) when they come back.
                } else {
                    ActivityCompat.requestPermissions(this, arrayOf(mic), REQ_MIC_FOR_AA)
                }
            }
            .setNegativeButton(R.string.mic_perm_not_now) { _, _ ->
                prefs.edit().putLong(KEY_MIC_DECLINED, System.currentTimeMillis()).apply()
                log("→ microphone not allowed — voice commands won't work while connected")
                resumePendingAaConnect("mic skipped")
            }
            .setCancelable(false)
            .show()
        return false
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC_FOR_AA) {
            val ok = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            log(if (ok) "→ microphone allowed — voice commands will work" else "→ microphone denied — voice commands won't work")
            resumePendingAaConnect("mic answered")
            return
        }
        if (requestCode == REQ_LOCAL_NET) {
            val ok = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            log(if (ok) "→ local network access allowed" else "→ local network access denied — the dash won't answer")
            resumePendingAaConnect("local network answered")
            return
        }
        if (requestCode == REQ_P2P_PERM) {
            val ok = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            val p = pendingP2p; pendingP2p = null
            if (ok && p != null) {
                log("→ Wi‑Fi Direct permission granted — joining the dash")
                joinWifiP2p(p.first, p.second)
            } else if (!ok) {
                log("→ Wi‑Fi Direct permission denied — can't reach a Wi‑Fi Direct dash")
                ConnectionState.set(Phase.ERROR, getString(R.string.conn_p2p_permission))
            }
            return
        }
        if (requestCode == REQ_NEARBY_UPFRONT) {
            log("→ Nearby devices up front: ${if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) "granted" else "not granted (asked again at Connect)"}")
            return
        }
        if (requestCode != REQ_BT_FOR_AA) return
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            resumePendingAaConnect("Nearby devices granted")
        } else if (!ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.BLUETOOTH_CONNECT)) {
            showNearbyBtForAaDialog(needNearby = true)
        } else {
            log("→ Nearby devices denied — Android Auto Connect needs it")
        }
    }

    /**
     * v2.3 · Ask for «Nearby devices» once, on launch, with one line of why — instead of only when
     * Connect is tapped (telemetry, 19 Oct: ~40 riders a week hit the permission error at Connect).
     * @return true when the explanation was shown (so other one-time sheets wait).
     */
    private fun maybeAskNearbyUpfront(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED) return false
        val prefs = getSharedPreferences(PREFS_PERMS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_BT_ASKED, false) || isFinishing || isDestroyed) return false
        prefs.edit().putBoolean(KEY_BT_ASKED, true).apply()
        log("→ asking for Nearby devices up front (needed to reach the dash)")
        val perms = mutableListOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms += Manifest.permission.NEARBY_WIFI_DEVICES
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.nearby_upfront_title)
            .setMessage(R.string.nearby_upfront_body)
            .setPositiveButton(R.string.nearby_upfront_ok) { _, _ ->
                ActivityCompat.requestPermissions(this, perms.toTypedArray(), REQ_NEARBY_UPFRONT)
            }
            .setNegativeButton(R.string.nearby_upfront_later, null)
            .show()
        return true
    }

    /**
     * v2.3 · Xiaomi / Redmi / POCO (HyperOS, MIUI) block a closed app from waking on the dash's
     * Bluetooth unless «Autostart» is on (field log 19 Oct: the trigger never fired after Stop).
     * Once a bike is saved, explain it once and open that screen.
     * @return true when the explanation was shown.
     */
    private fun maybeAskXiaomiAutostart(): Boolean {
        val brand = (Build.MANUFACTURER + " " + Build.BRAND).lowercase()
        if (!(brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco"))) return false
        if (BikeMemory.lastQr(this) == null || isFinishing || isDestroyed) return false
        val prefs = getSharedPreferences(PREFS_PERMS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_XIAOMI_AUTOSTART_ASKED, false)) return false
        prefs.edit().putBoolean(KEY_XIAOMI_AUTOSTART_ASKED, true).apply()
        log("→ Xiaomi: explaining Autostart (needed to connect by itself when the bike's Bluetooth comes up)")
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.xiaomi_autostart_title)
            .setMessage(R.string.xiaomi_autostart_body)
            .setPositiveButton(R.string.xiaomi_autostart_open) { _, _ -> openXiaomiAutostart() }
            .setNegativeButton(R.string.nearby_upfront_later, null)
            .show()
        return true
    }

    private fun openXiaomiAutostart() {
        val tries = listOf(
            Intent().setComponent(android.content.ComponentName(
                "com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.fromParts("package", packageName, null)),
        )
        for (i in tries) {
            try {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                log("→ Xiaomi Autostart: opened ${i.component?.className ?: i.action}")
                return
            } catch (e: Exception) {
                log("→ Xiaomi Autostart: ${i.component?.className ?: i.action} failed (${e.javaClass.simpleName})")
            }
        }
    }

    private fun showNearbyBtForAaDialog(needNearby: Boolean) {
        if (isFinishing || isDestroyed) return
        try {
            val msg = if (needNearby) {
                getString(R.string.main_aa_need_nearby_message)
            } else {
                getString(R.string.main_aa_need_bt_on_message)
            }
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.main_aa_need_nearby_title)
                .setMessage(msg)
                .setPositiveButton(R.string.main_aa_need_nearby_settings) { _, _ ->
                    try {
                        if (needNearby) {
                            startActivity(
                                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                    .setData(Uri.fromParts("package", packageName, null)),
                            )
                        } else {
                            startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
                        }
                    } catch (_: Exception) {
                        try {
                            startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
                        } catch (_: Exception) {
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } catch (e: Exception) {
            log("Nearby/BT dialog failed: $e")
        }
    }

    /** After a fatal crash, tell the rider logs survived and keep Share Logs useful. */
    private fun maybeShowCrashRecovery() {
        if (CrashGuard.pendingCrashText(this) == null) return
        try {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.main_crash_title)
                .setMessage(R.string.code_crash_recovery_msg)
                .setPositiveButton(R.string.code_btn_share_logs) { _, _ -> shareLog() }
                .setNegativeButton(R.string.code_btn_keep_logs) { _, _ -> }
                .setNeutralButton(R.string.code_btn_dismiss) { _, _ -> CrashGuard.clearCrash(this) }
                .show()
        } catch (e: Exception) {
            log("crash recovery UI failed: $e")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppSettings.applyToHolder(this)
        enableEdgeToEdge(
            androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContentView(R.layout.activity_main)
        // The hero's dark band runs up under the status bar; only its content is pushed down.
        val hero = findViewById<View>(R.id.hero)
        val heroTop = hero.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val b = insets.getInsets(SafeInsets.BARS)
            v.setPadding(b.left, 0, b.right, b.bottom)
            hero.setPadding(hero.paddingLeft, heroTop + b.top, hero.paddingRight, hero.paddingBottom)
            insets
        }

        logView = findViewById(R.id.log_view)
        logScroll = findViewById(R.id.log_scroll)
        logPanel = findViewById(R.id.log_panel)
        statusView = findViewById(R.id.status_view)
        statusDot = findViewById(R.id.status_dot)
        bikeView = findViewById(R.id.bike_view)
        connectBtn = findViewById(R.id.btn_connect)
        ringLabel = findViewById(R.id.ring_label)
        ringSub = findViewById(R.id.ring_sub)
        ringIcon = findViewById(R.id.ring_icon)
        rideStrip = findViewById(R.id.ride_strip)
        logView.movementMethod = ScrollingMovementMethod()
        renderWordmark()


        // All components (bike PXC, Android Auto receiver, video pipeline — including those
        // running in the foreground service) log through LogBus; mirror it into the view.
        LogBus.listener = { line ->
            runOnUiThread {
                logView.append("$line\n")
                logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }
        // Application already hydrated a prior session/crash into LogBus — show it in the view.
        val prior = LogBus.snapshot()
        if (prior.isNotBlank()) {
            logView.text = prior
            logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
        maybeShowCrashRecovery()
        // Soft dep check after auto-connect has had time to start — never race Connect.
        logView.postDelayed({ DependencyPrompt.showOnLaunchIfNeeded(this) }, 2500)

        // Reflect the coarse connection state in the big status header (so users don't read the log).
        ConnectionState.listener = { phase, detail ->
            runOnUiThread { renderStatus(phase, detail) }
        }
        renderStatus(ConnectionState.phase, ConnectionState.detail)
        refreshBikeLabel()

        // Reuse the process-global prober if one already exists (e.g. this activity was recreated
        // while the Android Auto receiver kept running in the foreground service). Constructing a
        // fresh one here would orphan the running instance — leaking its sockets/threads and making
        // the Stop button operate on the wrong object. See [BikeLink].
        prober = BikeLink.prober ?: EasyConnProber(applicationContext, LogBus::log).also { BikeLink.prober = it }

        // Android 13+: request notification permission up front so the Android Auto
        // foreground-service notification can be posted (some setups gate the FGS on it).
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3,
            )
        }

        // Connect while idle; Stop while busy / projecting (merged former Stop button).
        connectBtn.setOnClickListener {
            val phase = ConnectionState.phase
            if (phase.busy || phase == Phase.STREAMING || phase == Phase.MIRRORING) {
                // Luis Gil: Cancel got hit by accident while putting the phone in a bag. For the first
                // seconds of a connect attempt the button does nothing.
                val since = android.os.SystemClock.elapsedRealtime() - connectStartedAt
                if (phase.busy && since in 0 until CANCEL_LOCK_MS) {
                    log("→ Cancel tap ${since} ms after starting — ignored (lock ${CANCEL_LOCK_MS} ms)")
                    return@setOnClickListener
                }
                stopEverything()
                return@setOnClickListener
            }
            if (DependencyPrompt.showForConnect(this, forScan = false)) {
                log("→ Connect blocked — missing dependencies (see dialog)")
                return@setOnClickListener
            }
            val saved = BikeMemory.lastQr(this)
            if (saved != null) {
                log("→ Connect: reusing saved bike '${BikeMemory.lastBikeName(this)}' (no scan needed)")
                PxcHandshake.resetResyncBudget()
                ensureLocationPermission()
                startAaFlow(saved)
            } else {
                log("→ Connect: no saved bike — scan the dash QR.")
                startAaScan()
            }
        }

        // Long-press the wordmark: diagnostics (log, share, clear) for field debugging.
        findViewById<View>(R.id.brand_title).setOnLongClickListener {
            val show = logPanel.visibility != View.VISIBLE
            logPanel.visibility = if (show) View.VISIBLE else View.GONE
            if (show) findViewById<androidx.core.widget.NestedScrollView>(R.id.main_scroll)?.post {
                findViewById<androidx.core.widget.NestedScrollView>(R.id.main_scroll)?.smoothScrollTo(0, logPanel.top)
            }
            true
        }
        findViewById<View>(R.id.btn_lang).setOnClickListener { AppLanguage.toggle(this) }
        findViewById<View>(R.id.card_park).setOnClickListener { onParkCard() }
        findViewById<View>(R.id.card_service).setOnClickListener { MaintenanceActivity.start(this) }
        findViewById<View>(R.id.card_ride).setOnClickListener { TripsListActivity.start(this) }
        findViewById<View>(R.id.card_duas).setOnClickListener { DuasActivity.start(this) }
        findViewById<View>(R.id.card_controls).setOnClickListener { startActivity(Intent(this, ControlsActivity::class.java)) }
        findViewById<View>(R.id.nav_bike).setOnClickListener { GarageActivity.start(this) }
        findViewById<View>(R.id.nav_settings).setOnClickListener { SettingsActivity.start(this) }
        findViewById<View>(R.id.hus_open).setOnClickListener { HeadUnitServer.openSettings(this) }
        findViewById<View>(R.id.hus_how).setOnClickListener { HeadUnitServer.showHowTo(this) }
        findViewById<Button>(R.id.btn_share_log).setOnClickListener { shareLog() }

        findViewById<Button>(R.id.btn_clear).setOnClickListener {
            LogBus.clear()
            CrashGuard.clearSession(this)
            logView.text = ""
            LogBus.logSessionBanner()
        }

        log("Ready. Tap Connect to project Android Auto to your dash.")

        // First launch: the 4-step onboarding (language, permissions, Android Auto, scan the dash).
        try {
            if (!Onboarding.isDone(this)) {
                OnboardingActivity.start(this)
                finish()
                return
            }
            maybeAutoConnect()
            if (!maybeAskNearbyUpfront()) maybeAskXiaomiAutostart()
            RideDuas.maybeRemind(this)
            maybeResumeFromParked(intent)
            maybeStartFromBtTrigger(intent)
            maybeStartFromWidget(intent)
        } catch (e: Exception) {
            log("startup failed (UI still up): $e")
            CrashGuard.persistSession(this)
        }
    }

    override fun onPause() {
        statsHandler.removeCallbacks(cardTicker)
        statsHandler.removeCallbacks(husTicker)
        CrashGuard.persistSession(this)
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        maybeResumeFromParked(intent)
        maybeStartFromBtTrigger(intent)
        maybeStartFromWidget(intent)
        if (intent.getBooleanExtra(EXTRA_RECONNECT, false)) {
            intent.removeExtra(EXTRA_RECONNECT)
            log("→ Fit to dash: restarting Android Auto with the new panel shape")
            stopEverything()
            connectBtn.postDelayed({ reconnectSavedBike() }, 2500)
        }
    }

    override fun onResume() {
        super.onResume()
        // Back from the app's permission page / Bluetooth / Wi‑Fi settings: carry on with Connect.
        if (pendingAaQr != null && com.z150x.app.aa.AaSelfMode.canAttemptHur(this) &&
            WifiGate.isWifiEnabled(this)
        ) {
            resumePendingAaConnect("permissions OK")
        }
        // The active bike (and its name) may have changed in the Garage.
        refreshBikeLabel()
        statsHandler.removeCallbacks(cardTicker)
        statsHandler.post(cardTicker)
        statsHandler.removeCallbacks(husTicker)
        statsHandler.post(husTicker)
        renderStatus(ConnectionState.phase, ConnectionState.detail)
        if (WifiGate.isWifiEnabled(this)) WifiGate.cancelNotification(this)
        // Retry auto-connect on resume: after onboarding, or once the bike's Wi-Fi comes into range
        // shortly after launch. Guarded so it only ever starts one attempt.
        if (SetupActivity.hasSeen(this)) maybeAutoConnect()
        maybeResumeFromParked(intent)
    }

    /**
     * Guaranteed, BAL-safe resume after the service parked Android Auto (long bike outage). Re-launching
     * Google AA needs a foreground Activity, so when the rider taps the "Bike reconnected" notification
     * (or just opens the app while parked and the bike looks in range) we finish the resume here — this
     * `startActivity(gearhead)` is allowed because we're in the foreground.
     */
    private fun maybeResumeFromParked(intent: Intent?) {
        val explicit = intent?.getBooleanExtra(AndroidAutoService.EXTRA_RESUME, false) == true
        if (!explicit && !AndroidAutoService.isParked && ConnectionState.phase != Phase.WAITING_FOR_BIKE) return
        val saved = BikeMemory.lastQr(this) ?: return
        // On a plain open (not an explicit tap), only resume when the bike doesn't look clearly absent.
        if (!explicit && BikeWifi.isSsidInRange(this, saved.ssid) == false) return
        intent?.removeExtra(AndroidAutoService.EXTRA_RESUME)
        log("→ Resuming projection to '${BikeMemory.lastBikeName(this)}' from the foreground")
        autoConnectStarted = true
        AndroidAutoService.notifyForegroundResuming()
        ensureLocationPermission()
        startAaFlow(saved)
    }

    /**
     * 1x1 Connect widget. Idle → Connect with the saved bike. Connecting or projecting → nothing (the
     * widget never cancels: a brush must not undo a connect or cut a ride).
     */
    private fun maybeStartFromWidget(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_WIDGET_CONNECT, false) != true) return
        intent.removeExtra(EXTRA_WIDGET_CONNECT)
        if (!SetupActivity.hasSeen(this)) return
        val now = android.os.SystemClock.elapsedRealtime()
        val phase = ConnectionState.phase
        if (phase == Phase.STREAMING || phase == Phase.MIRRORING) {
            log("→ widget tap: already connected — ignored")
            Toast.makeText(this, R.string.widget_connect_already, Toast.LENGTH_SHORT).show()
            return
        }
        if (phase.busy) {
            // Cancelling lives on the app's own button (locked for the first seconds); the widget only
            // brings the app up so a brush can't undo a connect.
            log("→ widget tap while connecting — nothing to do")
            return
        }
        if (AndroidAutoService.isParked) {
            log("→ widget tap: resuming the parked session")
            connectStartedAt = now
            AndroidAutoService.requestResume()
            Toast.makeText(this, R.string.widget_connect_starting, Toast.LENGTH_SHORT).show()
            return
        }
        val saved = BikeMemory.lastQr(this)
        if (saved == null) {
            Toast.makeText(this, R.string.widget_connect_no_bike, Toast.LENGTH_LONG).show()
            return
        }
        log("→ widget tap: starting Connect for '${BikeMemory.lastBikeName(this)}'")
        autoConnectStarted = true
        Toast.makeText(this, R.string.widget_connect_starting, Toast.LENGTH_SHORT).show()
        ensureLocationPermission()
        startAaFlow(saved)
    }

    /** Helmet remote / watch ACL → same one-tap Connect as a saved-QR tap. */
    private fun maybeStartFromBtTrigger(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_BT_TRIGGER, false) != true) return
        intent.removeExtra(EXTRA_BT_TRIGGER)
        BtConnectTrigger.clearNotification(this)
        if (AndroidAutoService.isRunning && !AndroidAutoService.isOrphan) return
        if (ConnectionState.phase.busy ||
            ConnectionState.phase == Phase.STREAMING ||
            ConnectionState.phase == Phase.MIRRORING
        ) return
        val saved = BikeMemory.lastQr(this) ?: return
        log("→ Bluetooth trigger: starting Connect for '${BikeMemory.lastBikeName(this)}'")
        autoConnectStarted = true
        ensureLocationPermission()
        startAaFlow(saved)
    }

    /**
     * Auto-connect on launch: if the rider left the feature on, a bike is paired, Android Auto is
     * installed, nothing is already running, and the bike's Wi-Fi looks in range, kick off the same
     * one-tap Connect flow automatically. Fires at most once per process ([autoConnectStarted]) — but
     * only that success latches it, so a "not in range yet" launch is retried from [onResume] once the
     * bike's Wi-Fi appears. Never interrupts an active session or the setup wizard.
     */
    private fun maybeAutoConnect() {
        if (autoConnectStarted) return
        if (!AppSettings.autoConnect(this)) return
        if (AndroidAutoService.isRunning && !AndroidAutoService.isOrphan) return
        if (ConnectionState.phase.busy ||
            ConnectionState.phase == Phase.STREAMING ||
            ConnectionState.phase == Phase.MIRRORING) return
        val saved = BikeMemory.lastQr(this)
        if (saved == null) {
            logAutoConnectSkipOnce("no bike paired")
            return
        }
        if (!SetupHelper.isAndroidAutoInstalled(this)) {
            logAutoConnectSkipOnce("Android Auto not installed")
            return
        }
        if (!WifiGate.isWifiEnabled(this)) {
            // Don't latch autoConnectStarted — retry once the rider turns Wi‑Fi back on.
            WifiGate.ensureEnabledOrPrompt(this, direct = false)
            logAutoConnectSkipOnce("phone Wi‑Fi is off — turn it on to connect")
            return
        }

        val inRange = BikeWifi.isSsidInRange(this, saved.ssid)
        if (inRange == false) {
            logAutoConnectSkipOnce("'${BikeMemory.lastBikeName(this)}' not in range — will retry when its Wi-Fi appears")
            return
        }
        if (inRange == null) {
            // No fresh Wi-Fi scan to judge by (isSsidInRange has just asked for one). Connecting
            // blind meant that opening the app at home — to look at a ride — started Android Auto
            // and a Wi-Fi join that kept retrying for 20 minutes. Give the scan a few seconds and
            // look again; only if it is still unknown then, try anyway.
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - rangeRecheckAskedAt > RANGE_RECHECK_WINDOW_MS) {
                rangeRecheckAskedAt = now
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (!isFinishing && !isDestroyed) maybeAutoConnect()
                }, RANGE_RECHECK_DELAY_MS)
                return
            }
        }

        // Committed to connecting — latch so an activity recreation (Google AA foregrounding) or a
        // later onResume doesn't fire a second attempt. Use the main looper (not the view) so a
        // dependency dialog can't cancel the delayed start with the view.
        autoConnectStarted = true
        val why = if (inRange == true) "Wi-Fi in range" else "range unknown — trying anyway"
        log("→ Auto-connect: '${BikeMemory.lastBikeName(this)}' ($why). Disable in Setup ▸ Startup.")
        ensureLocationPermission()
        val activity = this
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            try {
                if (activity.isFinishing || activity.isDestroyed) {
                    // Recreated before we fired — let the new instance retry.
                    autoConnectStarted = false
                    return@postDelayed
                }
                if (AndroidAutoService.isOrphan) {
                    log("→ Auto-connect: Android Auto service running with no session (left over) — starting a fresh Connect")
                }
                if ((!AndroidAutoService.isRunning || AndroidAutoService.isOrphan) && !ConnectionState.phase.busy) {
                    activity.startAaFlow(saved)
                }
            } catch (e: Exception) {
                LogBus.log("auto-connect failed (app stays open): $e")
                CrashGuard.persistSession(activity)
                ConnectionState.set(Phase.ERROR, getString(R.string.conn_detail_auto_connect_failed))
            }
        }, 1200)
    }

    /** Log an auto-connect skip reason at most once per process, so onResume retries don't spam. */
    private fun logAutoConnectSkipOnce(reason: String) {
        if (autoConnectSkipLogged) return
        autoConnectSkipLogged = true
        log("→ Auto-connect idle: $reason.")
    }

    override fun onDestroy() {
        LogBus.listener = null
        ConnectionState.listener = null
        // When the Android Auto receiver service is running, the whole AA→bike chain (receiver +
        // encoder in the FGS, plus the Wi-Fi + prober in process globals) must OUTLIVE this activity:
        // launching Google Android Auto can destroy/recreate MainActivity mid-hand-off, and tearing
        // the bike down here is exactly what left the dash on a black screen (the pending
        // onSteadyVideo hand-off was cancelled before it could fire). Only tear down when AA is NOT
        // running — i.e. a genuine exit. Full teardown is the "Stop" button.
        if (!AndroidAutoService.isRunning) {
            AaVideoBridge.onSteadyVideo = null
            prober.stop()
            // NOTE: AndroidAutoService is intentionally NOT stopped here — it is a foreground service
            // meant to keep running when the phone is backgrounded/locked. Use "Stop Android Auto".
            BikeWifi.leave(this, ::log)
            BikeWifiP2p.stop(::log)
        }
        super.onDestroy()
    }

    /** Launch the QR scanner for the Android Auto path (profile is chosen from the scan result). */
    private fun startAaScan() {
        if (DependencyPrompt.showForConnect(this, forScan = true)) {
            log("→ Scan blocked — missing dependencies (see dialog)")
            return
        }
        log("→ Android Auto: scan the bike QR first so we pick the right screen profile.")
        ensureLocationPermission()
        try {
            scanLauncher.launch(Intent(this, QrScanActivity::class.java))
        } catch (e: Exception) {
            log("scan launch failed ($e)")
        }
    }

    /** AA 17.4 HUS banner: the rider is pointed at Head Unit Server instead of a generic error. */
    private fun isHusConnectNoise(detail: String): Boolean {
        if (detail == getString(R.string.conn_detail_aa_not_started)) return true
        val d = detail.lowercase()
        return d.contains("head unit server") || d.contains("start head unit")
    }

    /** The wordmark: "Z150" with the X in red. */
    private fun renderWordmark() {
        findViewById<TextView>(R.id.brand_title).apply {
            val t = getString(R.string.z_brand)
            text = android.text.SpannableString(t).apply {
                setSpan(
                    android.text.style.ForegroundColorSpan(ContextCompat.getColor(this@MainActivity, R.color.brand_orange)),
                    t.length - 1, t.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
    }

    /** Android Auto head unit server check: on resume (e.g. back from AA settings), then every 15 s. */
    private val husTicker = object : Runnable {
        override fun run() {
            checkHeadUnitServer()
            statsHandler.postDelayed(this, 15_000L)
        }
    }

    private fun checkHeadUnitServer() {
        val banner = findViewById<View?>(R.id.hus_banner) ?: return
        if (!HeadUnitServer.androidAutoInstalled(this)) return
        Thread({
            val running = HeadUnitServer.probe(this)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                // Only warn on a definite "no", and never during a live session (it's clearly running).
                val phase = ConnectionState.phase
                val aaError = phase == Phase.ERROR && isHusConnectNoise(ConnectionState.detail)
                val live = sessionAlive(phase) || AaVideoBridge.aaSessionLive
                val stopped = running == false && !live && HeadUnitServer.serverRequired(this)
                banner.visibility = if (stopped || aaError) View.VISIBLE else View.GONE
            }
        }, "hus-probe").start()
    }

    /** Cards change slowly (parking distance, service, last ride): refresh every 30 s while in front. */
    private val cardTicker = object : Runnable {
        override fun run() {
            renderCards()
            statsHandler.postDelayed(this, 30_000L)
        }
    }
    private val statsHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun renderCards() {
        renderParked()
        renderService()
        renderLastRide()
        renderDuas()
    }

    private fun renderDuas() {
        val card = findViewById<View?>(R.id.card_duas) ?: return
        card.visibility = if (RideDuas.enabled(this)) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.duas_home_sub).text =
            getString(R.string.duas_home_sub, RideDuas.doneToday(this).size, RideDuas.ALL.size)
    }

    private fun renderParked() {
        val sub = findViewById<TextView?>(R.id.park_sub) ?: return
        val spot = Parking.get(this)
        if (spot == null) {
            sub.setText(R.string.z_park_none)
            return
        }
        val ago = android.text.format.DateUtils.getRelativeTimeSpanString(
            spot.time, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS,
        )
        val dist = Parking.distanceFromHere(this, spot)
        sub.text = if (dist != null) getString(R.string.z_park_sub, Parking.distanceText(dist), ago) else ago
    }

    /** Find bike: walk / photo / delete for a saved spot, or save one here. */
    private fun onParkCard() {
        val spot = Parking.get(this)
        if (spot != null) {
            showParkedOptions(spot)
            return
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.z_find_bike)
            .setItems(arrayOf(getString(R.string.z_park_save_here), getString(R.string.z_park_save_photo))) { _, i ->
                Parking.saveNow(this)
                Toast.makeText(this, R.string.z_park_saved, Toast.LENGTH_SHORT).show()
                if (i == 1) takeParkingPhoto(thenStop = false)
                renderParked()
            }
            .show()
    }

    private fun renderService() {
        val s = try { Maintenance.mostUrgent(this) } catch (_: Exception) { null }
        findViewById<TextView>(R.id.svc_title).text = s?.task?.name ?: getString(R.string.z_service)
        findViewById<TextView>(R.id.svc_sub).text =
            s?.let { Maintenance.statusText(this, it) } ?: getString(R.string.z_service_none)
    }

    private fun renderLastRide() {
        Thread({
            val last = try { TripStore.summaries(this).maxByOrNull { it.start } } catch (_: Exception) { null }
            val title = last?.let { runCatching { TripNames.title(this, it) }.getOrNull() }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val sub = findViewById<TextView>(R.id.ride_sub)
                val km = findViewById<TextView>(R.id.ride_km)
                if (last == null) {
                    sub.setText(R.string.z_no_rides)
                    km.text = ""
                    return@runOnUiThread
                }
                sub.text = getString(R.string.z_ride_sub, title ?: last.dateText(), (last.movingTimeMs / 60_000L).toInt())
                km.text = android.text.SpannableStringBuilder()
                    .append(String.format(Locale.getDefault(), "%.1f", last.distanceKm))
                    .append(" " + getString(R.string.z_km), android.text.style.RelativeSizeSpan(0.55f), 0)
            }
        }, "last-ride").start()
    }


    /** Status line, Connect ring and the scooter strip from a [ConnectionState] transition. */
    private fun renderStatus(phase: Phase, detail: String) {
        val live = phase == Phase.STREAMING || phase == Phase.MIRRORING
        val idle = !phase.busy && !live && phase != Phase.ERROR
        statusView.text = if (idle && AppSettings.autoConnect(this) && BikeMemory.hasSaved(this)) {
            getString(R.string.z_status_auto_on)
        } else {
            getString(phase.labelRes) +
                (if (ClockLab.resyncInProgress() && phase != Phase.STOPPED && phase != Phase.IDLE)
                    " · " + getString(R.string.clock_resyncing) else "")
        }
        bikeView.text = if (detail.isNotBlank()) detail else bikeLabelText()
        val color = when (phase) {
            Phase.STREAMING, Phase.MIRRORING -> ContextCompat.getColor(this, R.color.status_live)
            Phase.ERROR -> ContextCompat.getColor(this, R.color.status_error)
            else -> if (phase.busy) ContextCompat.getColor(this, R.color.status_busy)
                    else ContextCompat.getColor(this, R.color.status_idle)
        }
        statusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(color)

        // The bike's link ports are held by another EasyConnect app — offer to close it (see
        // EasyConnProber's bind-conflict path). Show once per error so we don't nag on every redraw.
        if (phase == Phase.ERROR && detail == getString(R.string.code_err_rival_app)) {
            if (!rivalPromptShown) { rivalPromptShown = true; promptCloseRival() }
        } else {
            rivalPromptShown = false
        }
        // Only a confirmed kill-switch with a live internet VPN.
        if (phase == Phase.ERROR &&
            detail == getString(R.string.code_err_vpn_killswitch) &&
            BikeWifi.isVpnActive(this)
        ) {
            if (!vpnPromptShown) { vpnPromptShown = true; promptVpnKillSwitch() }
        } else {
            vpnPromptShown = false
        }

        connectBtn.state = when {
            live -> ConnectRingView.State.LIVE
            phase.busy -> ConnectRingView.State.BUSY
            phase == Phase.ERROR -> ConnectRingView.State.ERROR
            else -> ConnectRingView.State.IDLE
        }
        ringLabel.setText(
            when {
                live -> R.string.z_ring_live
                phase.busy -> R.string.z_ring_linking
                phase == Phase.ERROR -> R.string.z_ring_retry
                else -> R.string.z_ring_connect
            },
        )
        ringSub.setText(
            when {
                live -> R.string.z_ring_sub_live
                phase.busy -> R.string.z_ring_sub_busy
                phase == Phase.ERROR -> R.string.z_ring_sub_error
                BikeMemory.hasSaved(this) -> R.string.z_ring_sub_idle
                else -> R.string.z_ring_sub_new
            },
        )
        ringIcon.setColorFilter(
            ContextCompat.getColor(this, if (live) R.color.status_live else R.color.brand_orange),
        )
        connectBtn.contentDescription = ringLabel.text
        rideStrip.mode = when {
            live -> RideStripView.Mode.CRUISING
            phase.busy -> RideStripView.Mode.RIDING
            else -> RideStripView.Mode.IDLE
        }
        updateButtonStates(phase)
    }

    /** Connect is always tappable; surface the head unit server shortcut right under an AA failure. */
    private fun updateButtonStates(phase: Phase) {
        connectBtn.isEnabled = true
        applyCancelLockLook()
        // "Android Auto didn't attach / attached then dropped" is almost always its head unit
        // server (stopped, or hung after the last session). Show the shortcut at once instead of
        // waiting for the 15 s probe, which can't see the hung case at all.
        val aaFailed = phase == Phase.ERROR && (
            ConnectionState.detail == getString(R.string.conn_detail_aa_not_started) ||
                ConnectionState.detail == getString(R.string.conn_detail_aa_dropped) ||
                ConnectionState.detail == getString(R.string.conn_detail_hus_wedged) ||
                isHusConnectNoise(ConnectionState.detail))
        findViewById<View?>(R.id.hus_banner)?.let { banner ->
            if (aaFailed) banner.visibility = View.VISIBLE
            else if (sessionAlive(phase)) banner.visibility = View.GONE
        }
        renderParked()
    }

    private val cancelLockRestore = Runnable { applyCancelLockLook() }

    /** While the Stop tap is ignored (first seconds of a connect), show the button faded so it reads as not pressable. */
    private fun applyCancelLockLook() {
        connectBtn.removeCallbacks(cancelLockRestore)
        val left = CANCEL_LOCK_MS - (android.os.SystemClock.elapsedRealtime() - connectStartedAt)
        if (ConnectionState.phase.busy && left in 1..CANCEL_LOCK_MS) {
            connectBtn.animate().cancel()
            connectBtn.alpha = 0.4f
            connectBtn.postDelayed(cancelLockRestore, left)
        } else if (connectBtn.alpha < 1f) {
            connectBtn.animate().alpha(1f).setDuration(250).start()
        }
    }

    /** Show which bike (if any) is remembered, under the status header. */
    private fun refreshBikeLabel() {
        bikeView.text = bikeLabelText()
    }

    /**
     * Another dash app is holding the link ports. Android won't let one app stop another, so offer
     * a one-tap jump to its App-info screen (Force stop) and a retry.
     */
    private fun promptCloseRival() {
        val installed = RivalClient.isInstalled(this)
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.main_rival_title)
            .setMessage(R.string.code_rival_msg)
            .setPositiveButton(R.string.code_btn_close_retry) { _, _ ->
                log("→ retrying after the link ports were held by another app…")
                connectBtn.postDelayed({ reconnectSavedBike() }, 500)
            }
            .setNeutralButton(R.string.code_btn_app_settings) { _, _ ->
                if (!RivalClient.openAppInfo(this)) {
                    Toast.makeText(this, R.string.main_settings_failed, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.code_btn_dismiss, null)
            .setCancelable(true)
            .apply { if (!installed) setMessage(R.string.code_rival_msg_unknown) }
            .show()
    }

    /**
     * Always-on VPN with connection blocking prevents pinning sockets to bike Wi-Fi (EPERM).
     * Offer a shortcut into system VPN settings; the user must disable the kill-switch or VPN.
     */
    private fun promptVpnKillSwitch() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.code_vpn_title)
            .setMessage(R.string.code_vpn_msg)
            .setPositiveButton(R.string.code_btn_vpn_settings) { _, _ ->
                try {
                    startActivity(Intent("android.net.vpn.SETTINGS"))
                } catch (_: Exception) {
                    try {
                        startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
                    } catch (_: Exception) {
                        Toast.makeText(this, R.string.code_toast_open_vpn_settings, Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton(R.string.code_btn_dismiss, null)
            .setCancelable(true)
            .show()
    }

    /** Re-run the one-tap Connect for the saved bike (used after closing the rival app). */
    private fun reconnectSavedBike() {
        val saved = BikeMemory.lastQr(this) ?: return
        ensureLocationPermission()
        startAaFlow(saved)
    }

    private fun bikeLabelText(): String {
        val name = BikeMemory.lastBikeName(this)
        return if (name != null) getString(R.string.main_paired, name)
        else getString(R.string.main_no_bike_paired)
    }

    /**
     * Join the bike Wi-Fi and start the PXC prober.
     *
     * When [gateOnAaSteady] is true (Android Auto path), the prober start is handed to [BikeLink],
     * which waits until AA video is also steady — so this Wi-Fi join can run in parallel with AA
     * boot. When false (mirror path), the prober starts as soon as Wi-Fi is bound.
     *
     * Uses applicationContext + the process-global prober (not this activity) so the hand-off
     * completes even if the activity is destroyed/recreated after it was armed.
     */
    private fun joinWifi(qr: QrData, gateOnAaSteady: Boolean) {
        if (!WifiGate.ensureEnabledOrPrompt(this)) return
        ConnectionState.set(Phase.JOINING_WIFI)
        val transport = AppSettings.transport(this)
        ClockLab.phoneHotspotDash = qr.supportsPhoneHotspot && qr.pwd.isEmpty()
        // Phone-hosts-hotspot (Zontes action=128 / no SoftAP pwd): dash joins the phone.
        if (qr.supportsPhoneHotspot && qr.pwd.isEmpty()) {
            joinPhoneHotspot(qr, gateOnAaSteady)
            return
        }
        // AUTO: P2P when the QR is P2P-only (incl. non-DIRECT SSIDs — join by MAC), or DIRECT-*.
        // SoftAP fallback remains in joinWifiP2p.onFailed for SoftAP-capable units.
        // Never force P2P when the QR is SoftAP-only (action bit3 clear) — Setup→P2P on those
        // bikes only burns 25s then falls back (Benelli TRK / bj* SSIDs in the field).
        val useP2p = when (transport) {
            WifiTransport.P2P -> qr.supportsP2p || !qr.supportsAp
            WifiTransport.AP -> false
            WifiTransport.AUTO ->
                (qr.supportsP2p && !qr.supportsAp) ||
                    (qr.ssid.startsWith("DIRECT-", ignoreCase = true) &&
                        (qr.supportsP2p || !qr.supportsAp))
        }
        // Per-bike memory refines AUTO only (an explicit P2P/AP setting is the rider's call): once a
        // transport has produced a live link for THIS bike, use it and skip the dead path. Some dashes
        // advertise DIRECT-* + SoftAP, so AUTO tries P2P first and burns the whole timeout on every
        // connect even when P2P never forms a group on this phone, while SoftAP connects in seconds.
        val remembered = if (transport == WifiTransport.AUTO) BikeMemory.winningTransport(this, qr.ssid) else null
        val effUseP2p = when {
            remembered == "AP" && qr.supportsAp -> false
            remembered == "P2P" && qr.supportsP2p -> true
            else -> useP2p
        }
        if (remembered != null && effUseP2p != useP2p) {
            log("→ transport memory: '$remembered' connected before for '${qr.ssid}' — using it, skipping the dead path")
        }
        if (transport == WifiTransport.P2P && !useP2p) {
            log(
                "→ Setup Wi‑Fi transport is P2P but QR is SoftAP-only " +
                    "(action=${qr.action} ssid=${qr.ssid}) — joining SoftAP instead",
            )
        }
        if (effUseP2p) {
            val isDirect = qr.ssid.startsWith("DIRECT-", ignoreCase = true)
            // P2P-only QRs with a SoftAP-style SSID and a password burn ~40s on MAC ERROR
            // (Voge-5G / ZT5G…). Prefer SoftAP immediately, MAC or not: the Zontes T350X (QR
            // action=8 + ssid ZT5G… + pwd + mac, 20 Oct 2026) never formed a P2P group, then joined
            // its SoftAP in 3 s.
            if (!isDirect && qr.pwd.isNotEmpty()) {
                log(
                    "→ QR says P2P-only; SSID '${qr.ssid}' is not DIRECT-* and has a password — " +
                        "joining SoftAP first (skip Wi‑Fi Direct)",
                )
            } else {
                if (!isDirect) {
                    log(
                        "→ QR says P2P-only; SSID '${qr.ssid}' is not DIRECT-* — " +
                            "trying Wi‑Fi Direct by MAC (then SoftAP fallback)",
                    )
                }
                joinWifiP2p(qr, gateOnAaSteady)
                return
            }
        }
        // Wi‑Fi Direct dash whose group we join as a normal network (Zontes T350X: QR action=8 with
        // ssid + password). Its network rarely appears in plain scans: probe for it by name and keep
        // Wi‑Fi Direct discovery running alongside (see [P2pWarmUp]).
        val p2pGroupAsAp = qr.supportsP2p && !qr.supportsAp && qr.pwd.isNotEmpty() &&
            !qr.ssid.startsWith("DIRECT-", ignoreCase = true)
        if (p2pGroupAsAp) P2pWarmUp.start(applicationContext, qr, LogBus::log)
        BikeWifi.reuseOrJoin(
            context = applicationContext,
            ssid = qr.ssid,
            psk = qr.pwd,
            hidden = p2pGroupAsAp,
            onAvailable = { network ->
                WifiGate.cancelNotification(applicationContext)
                P2pWarmUp.stop("bike Wi‑Fi joined")
                // SoftAP got Wi-Fi up for this bike → remember it so AUTO skips P2P next time.
                BikeMemory.setWinningTransport(applicationContext, qr.ssid, "AP")
                if (gateOnAaSteady) {
                    LogBus.log("→ bike Wi-Fi bound (waiting for AA video to go steady)")
                    BikeLink.markWifiReady(network)
                } else {
                    // No AA gating — rebuild PXC immediately on the bound network.
                    ConnectionState.set(Phase.PXC_CONNECTING)
                    LogBus.log("→ Wi-Fi bound; starting EasyConn PXC flow …")
                    try {
                        (BikeLink.prober ?: prober).start(network ?: BikeWifi.currentNetwork)
                    } catch (e: Exception) {
                        LogBus.log("prober start failed: $e")
                    }
                }
            },
            onLost = { LogBus.log("bike network lost") },
            log = LogBus::log,
        )
    }

    /**
     * Dash is the Wi‑Fi client (Carbit `action=128`). Android cannot create a hotspot with the
     * dash SSID/password for us — show assist dialog, open tether settings, then poll for EasyConn.
     */
    private fun joinPhoneHotspot(qr: QrData, gateOnAaSteady: Boolean) {
        LogBus.log(
            "→ phone-hotspot mode (action=${qr.action} mac=${qr.mac}) — " +
                "assist dialog (app cannot create AP; set dash SSID/pwd in system hotspot)",
        )
        ConnectionState.set(Phase.JOINING_WIFI, getString(R.string.main_phone_hotspot_status))
        // v2.3: do what Carbit Ride does — our own hotspot + its details to the dash over Bluetooth.
        if (CarbitBtNet.findDash(this, qr.mac) != null) {
            Thread({
                val ok = CarbitBtNet.run(applicationContext, qr.mac)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (ok) startPhoneHotspotScan(gateOnAaSteady) else showHotspotAssist(qr, gateOnAaSteady)
                }
            }, "carbit-btnet").start()
            return
        }
        showHotspotAssist(qr, gateOnAaSteady)
    }

    private fun showHotspotAssist(qr: QrData, gateOnAaSteady: Boolean) {
        PhoneHotspotAssist.showSetupDialog(
            activity = this,
            qr = qr,
            onContinue = { startPhoneHotspotScan(gateOnAaSteady) },
            onCancel = {
                ConnectionState.set(Phase.ERROR, getString(R.string.main_phone_hotspot_cancelled))
            },
        )
    }

    /** Poll for tether iface (~45s) then NSD / subnet EasyConn scan. */
    private fun startPhoneHotspotScan(gateOnAaSteady: Boolean) {
        // 125M (18 Oct): the dash told us over BLE that it joined OUR hotspot and gave its address —
        // go straight there. Scanning for a "tether interface" missed the local-only hotspot.
        CarbitBleNet.takeJoined()?.let { (us, dash) ->
            ConnectionState.set(Phase.JOINING_WIFI, getString(R.string.main_phone_hotspot_status))
            Thread({
                try {
                    val bind = java.net.InetAddress.getByName(us) as java.net.Inet4Address
                    val peer = java.net.InetAddress.getByName(dash) as java.net.Inet4Address
                    LogBus.log("[HOTSPOT] dash is on our own hotspot at $dash (we are $us) — no scan needed")
                    hotspotHandOff(bind, peer, gateOnAaSteady)
                } catch (e: Exception) {
                    LogBus.log("[HOTSPOT] direct hand-off failed: $e")
                    runOnUiThread { ConnectionState.set(Phase.ERROR, getString(R.string.code_err_hotspot_handoff)) }
                }
            }, "phone-hotspot-direct").apply { isDaemon = true }.start()
            return
        }
        val creds = PhoneHotspotAssist.loadCreds(this)
        if (creds.ssid.isNotEmpty()) {
            Toast.makeText(
                this,
                getString(R.string.main_phone_hotspot_waiting_named, creds.ssid),
                Toast.LENGTH_LONG,
            ).show()
        } else {
            Toast.makeText(this, R.string.main_phone_hotspot_hint, Toast.LENGTH_LONG).show()
        }
        ConnectionState.set(Phase.JOINING_WIFI, getString(R.string.main_phone_hotspot_status))
        Thread({
            try {
                var subnets = emptyList<PhoneHotspotScan.Subnet>()
                val pollRounds = 30 // ~45s at 1.5s
                for (i in 0 until pollRounds) {
                    if (ConnectionState.phase == Phase.STOPPED) return@Thread
                    // Addresses the phone has as a CLIENT (mobile data, home Wi‑Fi…) are never the
                    // hotspot: the 125M log (15 Oct) "found" rmnet_data3 / home wlan0 and then
                    // claimed the hotspot was on. Tethering interfaces aren't client networks.
                    val clientAddrs = try {
                        val cm = getSystemService(android.net.ConnectivityManager::class.java)
                        @Suppress("DEPRECATION")
                        cm.allNetworks.flatMap { n -> cm.getLinkProperties(n)?.linkAddresses?.map { it.address }.orEmpty() }.toSet()
                    } catch (_: Exception) { emptySet() }
                    subnets = PhoneHotspotScan.tetheringSubnets(excluding = clientAddrs)
                        .filterNot { sn -> listOf("rmnet", "ccmni", "v4-", "clat", "tun", "ppp", "dummy").any { sn.interfaceName.lowercase().startsWith(it) } }
                    if (subnets.isNotEmpty()) break
                    if (i == 0 || i % 5 == 0) {
                        LogBus.log(
                            "[HOTSPOT] waiting for tether interface… " +
                                "(${i + 1}/$pollRounds) set Android hotspot" +
                                creds.ssid.takeIf { it.isNotEmpty() }?.let { " SSID='$it'" }.orEmpty(),
                        )
                    }
                    try {
                        Thread.sleep(1_500)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                }
                if (subnets.isEmpty()) {
                    // What the phone does have, so a log tells "hotspot off" from "hotspot not recognised".
                    LogBus.log("[HOTSPOT] interfaces: " + PhoneHotspotScan.snapshotInterfaces()
                        .filter { it.isUp && !it.isLoopback }
                        .joinToString(" · ") { i -> i.name + " " + i.addresses.filter { it.first is java.net.Inet4Address }.joinToString(",") { "${it.first.hostAddress}/${it.second}" } })
                    LogBus.log(
                        "[HOTSPOT] no tether interface after wait — open hotspot settings " +
                            "and set the dash SSID/password, then Connect again",
                    )
                    runOnUiThread {
                        ConnectionState.set(Phase.ERROR, getString(R.string.main_phone_hotspot_no_iface))
                        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                            .setTitle(R.string.main_phone_hotspot_dialog_title)
                            .setMessage(R.string.main_phone_hotspot_no_iface)
                            .setPositiveButton(R.string.main_phone_hotspot_open_settings) { _, _ ->
                                PhoneHotspotAssist.openHotspotSettings(this)
                            }
                            .setNegativeButton(android.R.string.ok, null)
                            .show()
                    }
                    return@Thread
                }
                val subnet = subnets.first()
                LogBus.log(
                    "[HOTSPOT] using ${subnet.interfaceName} " +
                        "${subnet.localAddress.hostAddress}/${subnet.prefixLength}",
                )
                // Prefer NSD first (works when multicast reaches the tether iface).
                val nsd = EasyConnDiscovery.discoverNsd(applicationContext, LogBus::log)
                val peer = nsd?.host
                    ?: PhoneHotspotScan.findEasyConnPeer(subnet, LogBus::log)
                if (peer == null) {
                    runOnUiThread {
                        ConnectionState.set(Phase.ERROR, getString(R.string.main_phone_hotspot_no_peer))
                        Toast.makeText(
                            this,
                            getString(R.string.main_phone_hotspot_no_peer),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    return@Thread
                }
                hotspotHandOff(subnet.localAddress, peer, gateOnAaSteady)
            } catch (e: Exception) {
                LogBus.log("[HOTSPOT] failed: $e")
                runOnUiThread {
                    ConnectionState.set(Phase.ERROR, getString(R.string.code_err_hotspot_scan))
                }
            }
        }, "phone-hotspot-scan").apply { isDaemon = true }.start()
    }

    /** Dash found on the phone's hotspot: hand the link to EasyConn (now, or once AA is steady). */
    private fun hotspotHandOff(bindIp: java.net.Inet4Address, peer: java.net.Inet4Address, gateOnAaSteady: Boolean) {
        runOnUiThread {
            WifiGate.cancelNotification(applicationContext)
            if (gateOnAaSteady) {
                LogBus.log(
                    "→ hotspot peer ${peer.hostAddress} (waiting for AA); " +
                        "bind=${bindIp.hostAddress}",
                )
                BikeLink.markP2pReady(bindIp, peer)
            } else {
                ConnectionState.set(Phase.PXC_CONNECTING)
                LogBus.log("→ hotspot peer ${peer.hostAddress}; starting EasyConn PXC …")
                try {
                    (BikeLink.prober ?: prober).start(
                        network = null,
                        gatewayOverride = peer,
                        bindIpOverride = bindIp,
                    )
                } catch (e: Exception) {
                    LogBus.log("prober start failed: $e")
                }
            }
        }
    }

    /** Wi‑Fi Direct Group Owner path (CL‑C450 / some DIRECT- SSIDs / Zontes MAC join). */
    /** A Wi‑Fi Direct join waiting for its permission answer. */
    private var pendingP2p: Pair<QrData, Boolean>? = null

    private fun joinWifiP2p(qr: QrData, gateOnAaSteady: Boolean) {
        if (!WifiGate.ensureEnabledOrPrompt(this)) return
        if (!BikeWifiP2p.hasPermission(this)) {
            // Without it every Wi‑Fi Direct call fails with a bare ERROR (350D, 20 Oct 2026).
            LogBus.log("→ Wi‑Fi Direct needs ${BikeWifiP2p.permissionName().substringAfterLast('.')} — asking")
            pendingP2p = qr to gateOnAaSteady
            ActivityCompat.requestPermissions(this, arrayOf(BikeWifiP2p.permissionName()), REQ_P2P_PERM)
            return
        }
        LogBus.log("→ joining via Wi‑Fi Direct (P2P)")
        // How long to wait for a P2P group before failing over to SoftAP:
        //  - "P2P" remembered  → proven P2P device on this phone; give it the full window.
        //  - SoftAP advertised → a working fallback exists, so bail (12s) when P2P is hopeless
        //    (some phones ERROR on every join attempt in <1s but the group never forms).
        //  - otherwise (P2P-only) → full window; there is nothing to fall back to.
        val known = BikeMemory.winningTransport(this, qr.ssid)
        val p2pTimeout = when {
            known == "P2P" -> BikeWifiP2p.CONNECT_TIMEOUT_MS
            qr.supportsAp && qr.pwd.isNotEmpty() -> 12_000L
            else -> BikeWifiP2p.CONNECT_TIMEOUT_MS
        }
        BikeWifiP2p.connect(
            context = applicationContext,
            qr = qr,
            timeoutMs = p2pTimeout,
            onConnected = { bindIp, gatewayIp ->
                WifiGate.cancelNotification(applicationContext)
                // P2P actually formed a group for this bike → remember it (some DIRECT-* bikes need P2P).
                BikeMemory.setWinningTransport(applicationContext, qr.ssid, "P2P")
                if (gateOnAaSteady) {
                    LogBus.log("→ P2P bound (waiting for AA video); bike=${gatewayIp.hostAddress}")
                    BikeLink.markP2pReady(bindIp, gatewayIp)
                } else {
                    ConnectionState.set(Phase.PXC_CONNECTING)
                    LogBus.log("→ P2P bound; starting EasyConn PXC flow …")
                    try {
                        (BikeLink.prober ?: prober).start(
                            network = null,
                            gatewayOverride = gatewayIp,
                            bindIpOverride = bindIp,
                        )
                    } catch (e: Exception) {
                        LogBus.log("prober start failed: $e")
                    }
                }
            },
            onFailed = { reason ->
                if (qr.pwd.isEmpty() || qr.ssid.startsWith("PHONE-HOTSPOT", ignoreCase = true)) {
                    // v2.3 · Zontes 350D (QR with only mac + name): Wi‑Fi Direct never formed a group.
                    // If the dash's own group shows up in the scan cache (same name, or the QR address
                    // apart from the first octet), join it like a normal network instead: open if it
                    // is open, else with the Carbit default password seen on other Carbit dashes.
                    val seen = P2pWarmUp.matchingScanResults(applicationContext, qr.name ?: qr.ssid, qr.mac)
                    seen.take(3).forEach {
                        @Suppress("DEPRECATION")
                        LogBus.log("[P2P] dash network in the scan cache: '${it.SSID}' ${it.BSSID} ${it.frequency} MHz ${it.level} dBm ${it.capabilities}")
                    }
                    val legacy = seen.firstOrNull()
                    @Suppress("DEPRECATION")
                    val legacySsid = legacy?.SSID?.takeIf { it.isNotBlank() }
                    if (legacy != null && legacySsid != null && !qr.ssid.startsWith("PHONE-HOTSPOT", ignoreCase = true)) {
                        val caps = legacy.capabilities.orEmpty()
                        val open = !caps.contains("WPA") && !caps.contains("SAE") && !caps.contains("WEP") && !caps.contains("PSK")
                        val psk = if (open) "" else CARBIT_DEFAULT_PSK
                        LogBus.log("P2P join failed: $reason — trying the dash's network '$legacySsid' as normal Wi‑Fi (${if (open) "open" else "Carbit default password"})")
                        if (!WifiGate.ensureEnabledOrNotify(applicationContext)) return@connect
                        BikeWifi.join(
                            context = applicationContext,
                            ssid = legacySsid,
                            psk = psk,
                            onAvailable = { network ->
                                WifiGate.cancelNotification(applicationContext)
                                LogBus.log("[P2P] joined the dash's network '$legacySsid' as normal Wi‑Fi")
                                if (gateOnAaSteady) BikeLink.markWifiReady(network)
                                else {
                                    ConnectionState.set(Phase.PXC_CONNECTING)
                                    try { (BikeLink.prober ?: prober).start(network) } catch (e: Exception) { LogBus.log("prober start failed: $e") }
                                }
                            },
                            onLost = { LogBus.log("bike network lost") },
                            log = LogBus::log,
                        )
                        return@connect
                    }
                    if (seen.isEmpty()) LogBus.log("[P2P] no network from the dash in the scan cache (name '${qr.name ?: qr.ssid}', address ${qr.mac})")
                    LogBus.log("P2P join failed: $reason — no SoftAP credentials to fall back to")
                    ConnectionState.set(Phase.ERROR, getString(R.string.code_err_wifi_direct))
                    return@connect
                }
                LogBus.log("P2P join failed: $reason — falling back to AP join")
                if (!WifiGate.ensureEnabledOrNotify(applicationContext)) return@connect
                BikeWifi.join(
                    context = applicationContext,
                    ssid = qr.ssid,
                    psk = qr.pwd,
                    onAvailable = { network ->
                        WifiGate.cancelNotification(applicationContext)
                        // SoftAP fallback connected → remember AP so AUTO goes straight here next time.
                        BikeMemory.setWinningTransport(applicationContext, qr.ssid, "AP")
                        if (gateOnAaSteady) BikeLink.markWifiReady(network)
                        else {
                            ConnectionState.set(Phase.PXC_CONNECTING)
                            try {
                                (BikeLink.prober ?: prober).start(network)
                            } catch (e: Exception) {
                                LogBus.log("prober start failed: $e")
                            }
                        }
                    },
                    onLost = { LogBus.log("bike network lost") },
                    log = LogBus::log,
                )
            },
            log = LogBus::log,
        )
    }

    private fun ensureLocationPermission() {
        // Some OEMs require fine location to associate via WifiNetworkSpecifier.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // Android 12+ ignores a request for precise location that doesn't also ask for
            // approximate location (the rider chooses between the two in the system dialog).
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                1,
            )
        }
    }

    private fun stopEverything() {
        log("→ stopping everything (Android Auto + bike)")
        try { AaVideoBridge.onSteadyVideo = null } catch (_: Exception) {}
        try { AndroidAutoService.stop(this) } catch (e: Exception) { log("AA stop: $e") }
        try { if (::prober.isInitialized) prober.stop() } catch (e: Exception) { log("prober stop: $e") }
        try { DashClockBle.stop() } catch (_: Exception) {}
        try { BikeWifi.releaseSession(this, ::log) } catch (e: Exception) { log("wifi leave: $e") }
        ConnectionState.set(Phase.STOPPED, "")
    }

    private fun shareLog() = LogShare.share(this)

    private fun log(msg: String) = LogBus.log(msg)

    companion object {
        const val EXTRA_BT_TRIGGER = "bt_trigger"
        const val EXTRA_WIDGET_CONNECT = "widget_connect"
        private const val ENGINE_ON_V = 13.2f
        private const val ENGINE_OFF_V = 12.8f
        private const val CANCEL_LOCK_MS = 3_000L
        @Volatile private var connectStartedAt = 0L
        /** From "Fit to dash": restart Android Auto so a new panel shape takes effect. */
        const val EXTRA_RECONNECT = "fit_reconnect"
        private const val REQ_BT_FOR_AA = 4
        private const val REQ_NEARBY_UPFRONT = 7343
        private const val REQ_P2P_PERM = 7344
        /** Group password of the Carbit Wi‑Fi Direct dashes we know (CFMoto 800MT "DIRECT-CF-…" QR). */
        private const val CARBIT_DEFAULT_PSK = "12345678"
        private const val REQ_LOCAL_NET = 7345
        /** Android 17 (API 37); spelled out while compileSdk is older. */
        private const val PERM_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
        private const val KEY_XIAOMI_AUTOSTART_ASKED = "xiaomi_autostart_asked"
        private const val PREFS_PERMS = "perm_flow"
        private const val KEY_BT_ASKED = "bt_asked"
        private const val KEY_MIC_ASKED = "mic_asked"
        private const val KEY_MIC_DECLINED = "mic_declined_at"
        private const val REQ_MIC_FOR_AA = 7342
        /** Latched once an auto-connect attempt actually starts, so it fires only once per process. */
        @Volatile private var autoConnectStarted = false
        /** When auto-connect last waited for a fresh Wi-Fi scan (see maybeAutoConnect). */
        @Volatile private var rangeRecheckAskedAt = -60_000L
        private const val RANGE_RECHECK_DELAY_MS = 4_000L
        private const val RANGE_RECHECK_WINDOW_MS = 15_000L
        /** So the "idle/not-in-range" reason is logged once, not on every onResume retry. */
        @Volatile private var autoConnectSkipLogged = false
    }
}
