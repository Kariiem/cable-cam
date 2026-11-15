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
import android.graphics.Typeface
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
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
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
    private var torchOn = false
    private val main = Handler(Looper.getMainLooper())

    private lateinit var surface: SurfaceView
    private lateinit var urlList: LinearLayout
    private lateinit var hint: TextView
    private lateinit var modeButton: Button
    private lateinit var startButton: Button
    private lateinit var statusText: TextView
    private lateinit var settingsButtons: LinearLayout
    private lateinit var tetherButton: Button
    private lateinit var dimOverlay: View
    private var pendingSurface: android.view.Surface? = null

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

    // ---- UI -------------------------------------------------------------------------------

    private fun color(id: Int) = getColor(id)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(s: String, size: Float = 14f, dim: Boolean = false, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size
        setTextColor(color(if (dim) R.color.text_dim else R.color.text))
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun buildUi(): View {
        val root = FrameLayout(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        // Left: preview, 16:9.
        surface = SurfaceView(this)
        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) { pendingSurface = h.surface; service?.setPreview(h.surface) }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
            override fun surfaceDestroyed(h: SurfaceHolder) { pendingSurface = null; service?.setPreview(null) }
        })
        surface.holder.setFixedSize(1280, 720)
        val previewBox = object : FrameLayout(this) {
            override fun onMeasure(w: Int, h: Int) {
                val width = MeasureSpec.getSize(w)
                super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(width * 9 / 16, MeasureSpec.EXACTLY))
            }
        }.apply { addView(surface, ViewGroup.LayoutParams(-1, -1)); setBackgroundColor(Color.BLACK) }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
            addView(previewBox, LinearLayout.LayoutParams(-1, -2))
        }
        content.addView(left, LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(dp(12), dp(8), dp(6), dp(8)) })

        // Right: controls.
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(16), dp(16)) }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(text("Cable Cam", 22f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        modeButton = button("") { chooseMode() }
        header.addView(modeButton)
        col.addView(header)

        col.addView(text("Address for OBS (Media Source > Input)", 13f, dim = true).apply { setPadding(0, dp(8), 0, 0) })
        urlList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(urlList)
        hint = text("", 13f, dim = true)
        col.addView(hint)
        tetherButton = button("Open tethering settings") { openTetherSettings() }
        col.addView(tetherButton)

        startButton = button("Start") { toggleStreaming() }.apply { textSize = 18f }
        col.addView(startButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        statusText = text("", 13f)
        col.addView(statusText)

        settingsButtons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(settingsButtons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        val row = LinearLayout(this)
        row.addView(button("OBS guide") { showGuide() }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(button("Dim screen") { dim(true) }, LinearLayout.LayoutParams(0, -2, 1f))
        col.addView(row)

        content.addView(ScrollView(this).apply { addView(col) }, LinearLayout.LayoutParams(0, -1, 1f))
        root.addView(content, FrameLayout.LayoutParams(-1, -1))

        dimOverlay = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK); visibility = View.GONE
            addView(TextView(context).apply { text = "Streaming. Tap to wake."; setTextColor(0xFF333333.toInt()); gravity = Gravity.CENTER },
                FrameLayout.LayoutParams(-1, -1))
            setOnClickListener { dim(false) }
        }
        root.addView(dimOverlay, FrameLayout.LayoutParams(-1, -1))

        root.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            content.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        return root
    }

    private fun dim(on: Boolean) {
        dimOverlay.visibility = if (on) View.VISIBLE else View.GONE
        window.attributes = window.attributes.apply { screenBrightness = if (on) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
    }

    private fun refreshLinks() {
        val now = Links.current()
        if (now != links) { links = now; refresh() }
    }

    /** Which links to show: the mode's own, or all of them if that mode has none yet. */
    private fun shownLinks(): List<Link> {
        val usb = links.filter { it.kind == LinkKind.USB }
        val wireless = links.filter { it.kind != LinkKind.USB }
        return if (settings.mode == Mode.CABLE) usb else wireless
    }

    private fun refresh() {
        if (!::settings.isInitialized) return
        val mode = settings.mode
        modeButton.text = "Mode: ${mode.label}"
        tetherButton.visibility = if (mode == Mode.CABLE) View.VISIBLE else View.GONE

        urlList.removeAllViews()
        val shown = shownLinks()
        for (l in shown) urlList.addView(urlRow("${l.kind.label}  ${l.iface}", l.url))
        if (mode == Mode.CABLE) urlList.addView(urlRow("adb forward (alternative)", Links.ADB_URL, small = true))
        hint.text = when {
            shown.isNotEmpty() -> if (mode == Mode.WIRELESS && shown.any { it.kind == LinkKind.BLUETOOTH })
                "Bluetooth link: the bitrate is lowered automatically." else ""
            mode == Mode.CABLE -> "No USB network yet. ${Guide.connect(mode)}"
            else -> "No Wi-Fi, hotspot or Bluetooth network found. ${Guide.connect(mode)}"
        }

        startButton.text = if (status.running) "Stop" else "Start"
        statusText.text = statusLine()
        statusText.setTextColor(color(if (status.error != null) R.color.warn else R.color.text))
        if (status.running) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        rebuildSettings()
    }

    private fun urlRow(label: String, url: String, small: Boolean = false) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(4), 0, dp(4))
        addView(text(label, 12f, dim = true))
        val line = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        line.addView(text(url, if (small) 14f else 17f, bold = !small).apply { setTextIsSelectable(true) },
            LinearLayout.LayoutParams(0, -2, 1f))
        line.addView(button("Copy") { copy(url) })
        addView(line)
    }

    private fun copy(s: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Cable Cam URL", s))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun statusLine(): String {
        val s = status
        if (s.error != null) return s.error
        if (!s.running) return "Stopped. Tap Start, then add the address in OBS."
        val who = if (s.clients.isEmpty()) "Waiting for OBS to connect"
        else "${s.clients.size} client${if (s.clients.size > 1) "s" else ""} connected"
        val enc = if (s.encoding) " | ${s.width}x${s.height} ${s.fps.toInt()} fps | ${s.kbps} of ${s.targetKbps} kbit/s" else ""
        val hot = if (s.thermal >= 3) " | phone is hot: quality reduced" else ""
        val drop = if (s.dropped > 0) " | dropped ${s.dropped} frames (slow link)" else ""
        return who + enc + hot + drop
    }

    private fun rebuildSettings() {
        settingsButtons.removeAllViews()
        fun add(label: String, onClick: () -> Unit) = settingsButtons.addView(button(label, onClick))
        add("Camera: ${if (settings.frontCamera) "front" else "back"}") {
            settings.frontCamera = !settings.frontCamera; changed()
        }
        add("Size: ${settings.height}p") { pick("Size", Presets.HEIGHTS.map { "${it}p" }, Presets.HEIGHTS.indexOf(settings.height)) { settings.height = Presets.HEIGHTS[it]; changed() } }
        add("Frame rate: ${settings.fps} fps") { pick("Frame rate", Presets.FPS.map { "$it fps" }, Presets.FPS.indexOf(settings.fps)) { settings.fps = Presets.FPS[it]; changed() } }
        add("Bitrate: ${if (settings.bitrateKbps == 0) "automatic" else "${settings.bitrateKbps} kbit/s"}") {
            pick("Bitrate", Presets.BITRATES_KBPS.map { if (it == 0) "Automatic (by link)" else "$it kbit/s" },
                Presets.BITRATES_KBPS.indexOf(settings.bitrateKbps)) { settings.bitrateKbps = Presets.BITRATES_KBPS[it]; changed() }
        }
        add("Stabilisation: ${if (settings.stabilise) "on" else "off"}") { settings.stabilise = !settings.stabilise; changed() }
        add("Torch: ${if (torchOn) "on" else "off"}") { torchOn = !torchOn; service?.setTorch(torchOn); refresh() }
    }

    private fun changed() { service?.applySettings(); refresh() }

    private fun pick(title: String, items: List<String>, current: Int, onPick: (Int) -> Unit) {
        AlertDialog.Builder(this).setTitle(title)
            .setSingleChoiceItems(items.toTypedArray(), current) { d, i -> onPick(i); d.dismiss() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun chooseMode() {
        pick("Mode", Mode.entries.map { it.label }, settings.mode.ordinal) { settings.mode = Mode.entries[it]; refresh() }
    }

    private fun showGuide() {
        val body = Guide.connect(settings.mode) + "\n\n" + (if (settings.mode == Mode.CABLE) Guide.adbAlternative + "\n\n" else "") +
            Guide.obs + "\n\n" + Guide.keepAlive
        AlertDialog.Builder(this).setTitle("Setup guide").setMessage(body).setPositiveButton("Close", null).show()
    }

    private fun openTetherSettings() {
        val tether = Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings")
        try { startActivity(tether) } catch (e: Exception) {
            try { startActivity(Intent(AndroidSettings.ACTION_WIRELESS_SETTINGS)) } catch (e2: Exception) {
                Toast.makeText(this, "Open Settings > Hotspot & tethering yourself", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---- start / stop and permissions -----------------------------------------------------

    private fun toggleStreaming() {
        if (status.running) { StreamService.stop(this); return }
        val need = listOf(Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isEmpty()) startStreaming() else requestPermissions(need.toTypedArray(), REQ_PERMS)
    }

    private fun startStreaming() {
        StreamService.start(this)      // the bound service reports its status through onStatus
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        if (code != REQ_PERMS) return
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startStreaming()
        else Toast.makeText(this, "Camera permission is needed to stream", Toast.LENGTH_LONG).show()
    }

    companion object { private const val REQ_PERMS = 1 }
}
