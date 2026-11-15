package com.cablecam.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Outline
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings as AndroidSettings
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.cablecam.R
import com.cablecam.net.Link
import com.cablecam.net.LinkKind
import com.cablecam.net.Links
import com.cablecam.service.StreamService
import com.cablecam.settings.Mode
import com.cablecam.settings.Presets
import com.cablecam.settings.Settings

class MainActivity : Activity() {
    private lateinit var settings: Settings
    private var service: StreamService? = null
    private var status = StreamService.Status()
    private var links: List<Link> = emptyList()
    private var vpn = false
    private var torchOn = false
    private var showAdb = false
    private var urlKey = ""
    private val main = Handler(Looper.getMainLooper())
    private var pendingSurface: android.view.Surface? = null

    private lateinit var surface: SurfaceView
    private lateinit var liveBadge: TextView
    private lateinit var cameraOff: TextView
    private lateinit var modeSeg: Segmented
    private lateinit var statusDot: View
    private lateinit var statusLabel: TextView
    private lateinit var statusDetail: TextView
    private lateinit var startButton: TextView
    private lateinit var bannerBox: LinearLayout
    private lateinit var urlList: LinearLayout
    private lateinit var hint: TextView
    private lateinit var tetherButton: TextView
    private lateinit var adbToggle: TextView
    private lateinit var adbBox: LinearLayout
    private lateinit var segCamera: Segmented
    private lateinit var segSize: Segmented
    private lateinit var segFps: Segmented
    private lateinit var bitrateValue: TextView
    private lateinit var swStabilise: Switch
    private lateinit var swTorch: Switch
    private lateinit var dimOverlay: View

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName, b: IBinder) {
            val s = (b as StreamService.LocalBinder).service
            service = s
            s.onStatus = { st -> status = st; refresh() }
            s.setPreview(pendingSurface)
        }
        override fun onServiceDisconnected(n: ComponentName) { service = null }
    }

    private val linkTicker = object : Runnable {
        override fun run() { refreshLinks(); main.postDelayed(this, 2000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashReport.install(this)
        CrashReport.pending(this)?.let { setContentView(CrashReport.screen(this, it)); return }
        settings = Settings(this)
        setContentView(buildUi())
        refresh()
    }

    override fun onStart() {
        super.onStart()
        if (!::settings.isInitialized) return
        bindService(Intent(this, StreamService::class.java), connection, Context.BIND_AUTO_CREATE)
        main.post(linkTicker)
    }

    override fun onStop() {
        if (!::settings.isInitialized) { super.onStop(); return }
        main.removeCallbacks(linkTicker)
        service?.onStatus = null
        service?.setPreview(null)
        runCatching { unbindService(connection) }
        service = null
        super.onStop()
    }

    // ---- building the screen ----------------------------------------------------------------

    private fun lp(w: Int = -1, h: Int = -2, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

    private fun buildUi(): View {
        val root = FrameLayout(this)
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(28)) }

        // Title and mode.
        page.addView(label("Cable Cam", 26f, bold = true), lp().apply { topMargin = dp(8) })
        page.addView(label("Phone camera for OBS", 14f, col(R.color.text_dim)))
        modeSeg = Segmented(this, Mode.entries.map { if (it == Mode.CABLE) "USB cable" else "Wi-Fi / Bluetooth" }) {
            settings.mode = Mode.entries[it]; urlKey = ""; refresh()
        }
        page.addView(modeSeg, lp().apply { topMargin = dp(14) })

        // Hero: preview + status + start.
        val hero = card()
        val heroRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        surface = SurfaceView(this).apply {
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, dp(14).toFloat())
            }
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(h: SurfaceHolder) { pendingSurface = h.surface; service?.setPreview(h.surface) }
                override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
                override fun surfaceDestroyed(h: SurfaceHolder) { pendingSurface = null; service?.setPreview(null) }
            })
            holder.setFixedSize(1280, 720)       // the camera delivers landscape buffers; the system rotates them
        }
        liveBadge = label("PREVIEW", 11f, Color.WHITE, bold = true).apply {
            background = rounded(0x99000000.toInt(), 8); setPadding(dp(8), dp(3), dp(8), dp(3))
        }
        cameraOff = label("Camera off", 13f, 0xFF8A8F9C.toInt())
        val previewBox = FrameLayout(this).apply {
            background = rounded(Color.BLACK, 14)
            addView(surface, FrameLayout.LayoutParams(-1, -1))
            addView(cameraOff, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            addView(liveBadge, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(8), dp(8), 0, 0) })
        }
        heroRow.addView(previewBox, lp(dp(132), dp(234)).apply { rightMargin = dp(14) })
        val side = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val pill = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        statusDot = View(this)
        pill.addView(statusDot, lp(dp(10), dp(10)).apply { rightMargin = dp(8) })
        statusLabel = label("", 18f, bold = true)
        pill.addView(statusLabel)
        side.addView(pill)
        statusDetail = label("", 13f, col(R.color.text_dim))
        side.addView(statusDetail, lp().apply { topMargin = dp(6) })
        startButton = filledButton("Start") { toggleStreaming() }
        side.addView(startButton, lp().apply { topMargin = dp(16) })
        heroRow.addView(side, lp(0, -2, 1f))
        hero.addView(heroRow)
        page.addView(hero, lp().apply { topMargin = dp(16) })

        bannerBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(bannerBox, lp())

        // Connect.
        val connect = card()
        connect.addView(label("Connect OBS", 17f, bold = true))
        connect.addView(label("OBS > Sources > + > Media Source > untick Local File > Input:", 13f, col(R.color.text_dim)), lp().apply { topMargin = dp(4) })
        urlList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        connect.addView(urlList, lp().apply { topMargin = dp(8) })
        hint = label("", 13f, col(R.color.text_dim))
        connect.addView(hint, lp().apply { topMargin = dp(4) })
        tetherButton = tonalButton("Open mobile network settings") { openTetherSettings() }
        connect.addView(tetherButton, lp().apply { topMargin = dp(10) })
        adbToggle = textButton("") { showAdb = !showAdb; urlKey = ""; refresh() }
        connect.addView(adbToggle, lp())
        adbBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        connect.addView(adbBox, lp())
        page.addView(connect, lp().apply { topMargin = dp(12) })

        // Camera settings, built once and updated by refresh().
        val cam = card()
        cam.addView(label("Camera", 17f, bold = true))
        segCamera = Segmented(this, listOf("Back", "Front")) { settings.frontCamera = it == 1; changed() }
        cam.addView(settingRow("Lens", segCamera))
        segSize = Segmented(this, Presets.HEIGHTS.map { "${it}p" }) { settings.height = Presets.HEIGHTS[it]; changed() }
        cam.addView(settingRow("Size", segSize))
        segFps = Segmented(this, Presets.FPS.map { "$it" }) {
            settings.fps = Presets.FPS[it]; if (settings.fps > 30) settings.height = 720; changed()
        }
        cam.addView(settingRow("Frame rate", segFps))
        bitrateValue = label("", 14f, col(R.color.accent), bold = true).apply {
            setPadding(dp(14), dp(10), dp(14), dp(10)); background = pressable(col(R.color.field), 12)
            setOnClickListener { chooseBitrate() }
        }
        cam.addView(settingRow("Bitrate", bitrateValue))
        swStabilise = Switch(this)
        cam.addView(settingRow("Stabilisation", swStabilise))
        swTorch = Switch(this)
        cam.addView(settingRow("Torch", swTorch))
        cam.addView(label("60 fps needs 720p. 1080p is 30 fps.", 12f, col(R.color.text_dim)), lp().apply { topMargin = dp(6) })
        page.addView(cam, lp().apply { topMargin = dp(12) })

        // Footer actions.
        val foot = LinearLayout(this)
        foot.addView(tonalButton("Setup guide") { showGuide() }, lp(0, -2, 1f).apply { rightMargin = dp(6) })
        foot.addView(tonalButton("Dim screen") { dim(true) }, lp(0, -2, 1f).apply { leftMargin = dp(6) })
        page.addView(foot, lp().apply { topMargin = dp(12) })

        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(page) }
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))

        dimOverlay = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK); visibility = View.GONE
            addView(TextView(context).apply { text = "Streaming. Tap to wake."; setTextColor(0xFF333333.toInt()); gravity = Gravity.CENTER },
                FrameLayout.LayoutParams(-1, -1))
            setOnClickListener { dim(false) }
        }
        root.addView(dimOverlay, FrameLayout.LayoutParams(-1, -1))

        root.setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                scroll.setPadding(b.left, b.top, b.right, b.bottom)
            } else {
                @Suppress("DEPRECATION")
                scroll.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        return root
    }

    private fun settingRow(name: String, control: View) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(label(name, 15f), lp(0, -2, 1f))
        addView(control, LinearLayout.LayoutParams(-2, -2))
        layoutParams = lp().apply { topMargin = dp(12) }
    }

    private fun banner(text: String, fill: Int, color: Int) = label(text, 13f, color).apply {
        background = rounded(fill, 14); setPadding(dp(14), dp(10), dp(14), dp(10))
        layoutParams = lp().apply { topMargin = dp(10) }
    }

    // ---- state -> views ---------------------------------------------------------------------

    private fun refreshLinks() {
        val now = Links.current()
        val v = Links.vpnActive()
        if (now != links || v != vpn) { links = now; vpn = v; refresh() }
    }

    private fun shownLinks(): List<Link> =
        if (settings.mode == Mode.CABLE) links.filter { it.kind == LinkKind.USB } else links.filter { it.kind != LinkKind.USB }

    private fun refresh() {
        if (!::settings.isInitialized) return
        val s = status
        val mode = settings.mode
        modeSeg.selected = mode.ordinal

        // Status.
        val (dot, name) = when {
            s.error != null -> R.color.warn to "Problem"
            !s.running -> R.color.text_dim to "Stopped"
            s.clients.isEmpty() -> R.color.amber to "Waiting for OBS"
            else -> R.color.good to "Streaming"
        }
        statusDot.background = dotBackground(this, col(dot))
        statusLabel.text = name
        statusDetail.text = when {
            !s.running -> "Tap Start, then add the address in OBS."
            s.encoding -> "${s.width}x${s.height} · ${s.fps.toInt()} fps\n${"%.1f".format(s.kbps / 1000f)} of ${"%.1f".format(s.targetKbps / 1000f)} Mbit/s" +
                "\n${s.clients.size} viewer${if (s.clients.size == 1) "" else "s"} · phone delay ${s.latencyMs} ms"
            else -> "The camera is on; video is encoded when OBS connects."
        }
        // A stopped stream shows no stale last frame: the surface is hidden, so the black panel shows.
        surface.visibility = if (s.running) View.VISIBLE else View.INVISIBLE
        cameraOff.visibility = if (s.running) View.GONE else View.VISIBLE
        startButton.text = if (s.running) "Stop" else "Start"
        startButton.background = if (s.running) pressable(col(R.color.warn_container), 14) else pressable(col(R.color.accent), 14)
        startButton.setTextColor(col(if (s.running) R.color.warn else R.color.on_accent))
        liveBadge.text = if (s.running && s.clients.isNotEmpty()) "● LIVE" else "PREVIEW"
        liveBadge.background = rounded(if (s.running && s.clients.isNotEmpty()) 0xCCD32F2F.toInt() else 0x99000000.toInt(), 8)
        if (s.running) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Banners.
        bannerBox.removeAllViews()
        s.error?.let { bannerBox.addView(banner(it, col(R.color.warn_container), col(R.color.warn))) }
        if (vpn) bannerBox.addView(banner("A VPN is active on the phone (for example a DNS or ad blocker). It can block connections " +
            "from the PC: pause it if OBS cannot connect.", col(R.color.amber_container), col(R.color.amber)))
        if (s.thermal >= 3) bannerBox.addView(banner("The phone is hot: quality is reduced.", col(R.color.amber_container), col(R.color.amber)))
        if (s.dropped > 0) bannerBox.addView(banner("Dropping frames (${s.dropped} in the last 10 s): the link is too slow. Lower the size or bitrate.",
            col(R.color.amber_container), col(R.color.amber)))

        // Connect card. Rebuilt only when the addresses change, so taps are not interrupted.
        val shown = shownLinks()
        val key = "$mode|${shown.map { it.url }}|$showAdb"
        if (key != urlKey) {
            urlKey = key
            urlList.removeAllViews()
            for (l in shown) urlList.addView(urlRow("${l.kind.label} (${l.iface})", l.url))
            adbBox.removeAllViews()
            if (mode == Mode.CABLE && showAdb) {
                adbBox.addView(label(Guide.adbAlternative, 13f, col(R.color.text_dim)), lp().apply { topMargin = dp(4) })
                adbBox.addView(urlRow("With adb forward running on the PC", Links.ADB_URL))
            }
        }
        hint.text = when {
            shown.isEmpty() && mode == Mode.CABLE -> "No USB network yet. Plug in the cable, then turn on USB tethering (Personal hotspot > USB tethering)."
            shown.isEmpty() -> "No Wi-Fi, hotspot or Bluetooth network found."
            shown.any { it.kind == LinkKind.BLUETOOTH } -> "Bluetooth link: the bitrate is lowered automatically."
            else -> "Open the address without /cam.ts in a browser to test the connection."
        }
        tetherButton.visibility = if (mode == Mode.CABLE) View.VISIBLE else View.GONE
        adbToggle.visibility = if (mode == Mode.CABLE) View.VISIBLE else View.GONE
        adbToggle.text = if (showAdb) "Hide adb alternative" else "No tethering? Use adb instead"

        // Camera card.
        segCamera.selected = if (settings.frontCamera) 1 else 0
        segSize.selected = Presets.HEIGHTS.indexOf(settings.height)
        segFps.selected = Presets.FPS.indexOf(settings.fps)
        bitrateValue.text = if (settings.bitrateKbps == 0) "Automatic" else "${settings.bitrateKbps / 1000f} Mbit/s".replace(".0 ", " ")
        bind(swStabilise, settings.stabilise) { settings.stabilise = it; changed() }
        bind(swTorch, torchOn) { torchOn = it; service?.setTorch(it) }
    }

    /** Sets a switch without triggering its own listener. */
    private fun bind(sw: Switch, on: Boolean, onChange: (Boolean) -> Unit) {
        sw.setOnCheckedChangeListener(null)
        if (sw.isChecked != on) sw.isChecked = on
        sw.setOnCheckedChangeListener { _, v -> onChange(v) }
    }

    private fun urlRow(name: String, url: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = lp().apply { topMargin = dp(8) }
        addView(label(name, 12f, col(R.color.text_dim)))
        val field = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = pressable(col(R.color.field), 14); setPadding(dp(14), dp(4), dp(6), dp(4))
            setOnClickListener { copy(url) }
        }
        field.addView(label(url, 15f, bold = true, mono = true).apply { setPadding(0, dp(8), 0, dp(8)) }, lp(0, -2, 1f))
        field.addView(textButton("Copy") { copy(url) }, LinearLayout.LayoutParams(-2, -2))
        addView(field, lp().apply { topMargin = dp(4) })
    }

    private fun copy(s: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Cable Cam URL", s))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun changed() { service?.applySettings(); refresh() }

    private fun chooseBitrate() {
        val items = Presets.BITRATES_KBPS.map { if (it == 0) "Automatic (by link)" else "${it / 1000f} Mbit/s".replace(".0 ", " ") }
        AlertDialog.Builder(this).setTitle("Bitrate")
            .setSingleChoiceItems(items.toTypedArray(), Presets.BITRATES_KBPS.indexOf(settings.bitrateKbps)) { d, i ->
                settings.bitrateKbps = Presets.BITRATES_KBPS[i]; d.dismiss(); changed()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun dim(on: Boolean) {
        dimOverlay.visibility = if (on) View.VISIBLE else View.GONE
        window.attributes = window.attributes.apply { screenBrightness = if (on) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
    }

    private fun showGuide() {
        val body = Guide.connect(settings.mode) + "\n\n" + (if (settings.mode == Mode.CABLE) Guide.adbAlternative + "\n\n" else "") +
            Guide.obs + "\n\n" + Guide.vpn + "\n\n" + Guide.keepAlive
        AlertDialog.Builder(this).setTitle("Setup guide").setMessage(body).setPositiveButton("Close", null).show()
    }

    /**
     * Opens the page that leads to tethering. The direct tethering screens are not reachable on MagicOS
     * (the intents start and close at once), but the mobile network page is, and it lists "Personal hotspot".
     */
    private fun openTetherSettings() {
        val routes = listOf(
            Intent(AndroidSettings.ACTION_NETWORK_OPERATOR_SETTINGS),
            Intent(AndroidSettings.ACTION_WIRELESS_SETTINGS),
        )
        for (i in routes) {
            try { startActivity(i); return } catch (e: Exception) { /* try the next one */ }
        }
        Toast.makeText(this, "Open Settings > Mobile network > Personal hotspot yourself", Toast.LENGTH_LONG).show()
    }

    // ---- start / stop and permissions -------------------------------------------------------

    private fun toggleStreaming() {
        if (status.running) { StreamService.stop(this); return }
        val need = listOf(Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isEmpty()) StreamService.start(this) else requestPermissions(need.toTypedArray(), REQ_PERMS)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        if (code != REQ_PERMS) return
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) StreamService.start(this)
        else Toast.makeText(this, "Camera permission is needed to stream", Toast.LENGTH_LONG).show()
    }

    companion object { private const val REQ_PERMS = 1 }
}
