package com.cablecam.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.view.Surface
import com.cablecam.camera.CameraController
import com.cablecam.camera.FrameRotator
import com.cablecam.net.Links
import com.cablecam.settings.Presets
import com.cablecam.settings.Settings
import com.cablecam.stream.Frame
import com.cablecam.stream.MjpegEncoder
import com.cablecam.stream.StreamServer
import com.cablecam.stream.VideoEncoder
import com.cablecam.ui.MainActivity

/**
 * Foreground service (type camera) that owns the camera, the encoder and the HTTP server.
 * The camera runs while the service runs (preview); the encoder only while a client is connected.
 * All state changes happen on the main thread.
 */
class StreamService : Service() {
    data class Status(
        val running: Boolean = false,
        val error: String? = null,
        val clients: List<StreamServer.ClientInfo> = emptyList(),
        val encoding: Boolean = false,
        val width: Int = 0, val height: Int = 0,
        val fps: Float = 0f, val kbps: Int = 0, val targetKbps: Int = 0,
        val thermal: Int = 0, val dropped: Long = 0, val latencyMs: Long = 0,
    )

    inner class LocalBinder : Binder() { val service get() = this@StreamService }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var settings: Settings
    private lateinit var camera: CameraController
    private var server: StreamServer? = null
    private var encoder: VideoEncoder? = null
    private var rotator: FrameRotator? = null
    private var jpeg: MjpegEncoder? = null
    private var jpegRotator: FrameRotator? = null
    private var jpegFront = false
    private var stopJpegAt = 0L
    private var encoderFront = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var thermalStatus = 0
    private val thermalListener = PowerManager.OnThermalStatusChangedListener { s ->
        main.post { thermalStatus = s; updateBitrate(); publish() }
    }

    private var clients: List<StreamServer.ClientInfo> = emptyList()
    private var error: String? = null
    private var running = false
    private var frames = 0
    private var lastBytes = 0L
    private var fpsNow = 0f
    private var kbpsNow = 0
    private var stopEncoderAt = 0L
    private val dropHistory = ArrayDeque<Long>()      // frames dropped per second, last 10 s
    private var lastDropped = 0L
    private var activePreview: Surface? = null

    /** Called on the main thread whenever the status changes (about once a second while streaming). */
    var onStatus: ((Status) -> Unit)? = null
        set(v) { field = v; v?.invoke(status()) }

    fun status() = Status(
        running, error, clients, encoder != null || jpeg != null,
        encoder?.width ?: jpeg?.width ?: 0, encoder?.height ?: jpeg?.height ?: 0,
        fpsNow, kbpsNow, encoder?.currentKbps ?: 0, thermalStatus, dropHistory.sum(), encoder?.latencyMs ?: jpeg?.latencyMs ?: 0,
    )

    private fun publish() { onStatus?.invoke(status()) }

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        camera = CameraController(this) { msg -> main.post { cameraFailed(msg) } }
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopStreaming(); return START_NOT_STICKY }
        if (!running) startStreaming()
        return START_NOT_STICKY
    }

    private fun startStreaming() {
        running = true
        error = null
        startForeground(NOTIF_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cablecam:stream").apply { setReferenceCounted(false); acquire() }
        // Low-latency Wi-Fi: without it the radio's power saving pauses traffic for hundreds of ms, which shows up as lag.
        wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "cablecam:wifi").apply { setReferenceCounted(false); acquire() }
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        thermalStatus = pm.currentThermalStatus
        pm.addThermalStatusListener(mainExecutor, thermalListener)

        val s = StreamServer(StreamServer.DEFAULT_PORT, object : StreamServer.Listener {
            override fun onClientsChanged(clients: List<StreamServer.ClientInfo>) { main.post { clientsChanged(clients) } }
            override fun onNeedKeyframe() { main.post { requestKeyframe() } }
        }, status = { "${settings.height}p${settings.fps}, ${fpsNow.toInt()} fps, $kbpsNow kbit/s" })
        try {
            s.start()
            server = s
        } catch (e: Exception) {
            error = "Cannot listen on port ${StreamServer.DEFAULT_PORT}: ${e.message}"
        }
        camera.open(settings.frontCamera, settings.fps, settings.stabilise)
        camera.setPreview(activePreview)
        main.postDelayed(ticker, 1000)
        publish()
    }

    private fun stopStreaming() {
        if (!running) { stopSelf(); return }
        running = false
        main.removeCallbacks(ticker)
        stopEncoder(); stopJpeg()
        server?.stop(); server = null
        camera.close()
        (getSystemService(POWER_SERVICE) as PowerManager).removeThermalStatusListener(thermalListener)
        wakeLock?.release(); wakeLock = null
        wifiLock?.release(); wifiLock = null
        clients = emptyList(); fpsNow = 0f; kbpsNow = 0
        stopForeground(STOP_FOREGROUND_REMOVE)
        publish()
        stopSelf()
    }

    override fun onDestroy() {
        if (running) stopStreaming()
        camera.release()
        instance = null
        super.onDestroy()
    }

    /** The preview surface of the visible activity, or null when it is gone. */
    fun setPreview(s: Surface?) { activePreview = s; camera.setPreview(s) }

    fun setTorch(on: Boolean) = camera.setTorch(on)

    fun cameraInfo(front: Boolean) = camera.info(front)

    /** Settings changed: reopen the camera, restart the encoder if the video format changed. */
    fun applySettings() {
        if (!running) return
        error = null
        camera.open(settings.frontCamera, settings.fps, settings.stabilise)
        val e = encoder
        if (e != null && (e.width != settings.height || e.fps != settings.fps || encoderFront != settings.frontCamera)) {
            stopEncoder(); server?.dropClients()        // OBS reconnects and gets the new format
        } else updateBitrate()
        val j = jpeg
        if (j != null && (j.width != settings.height || j.fps != settings.fps || jpegFront != settings.frontCamera)) {
            stopJpeg(); server?.dropClients()
        }
        publish()
    }

    private fun cameraFailed(msg: String) {
        if (!running) return
        error = msg
        publish()
        main.postDelayed({
            if (running && error == msg) { error = null; camera.open(settings.frontCamera, settings.fps, settings.stabilise); publish() }
        }, 3000)
    }

    private fun clientsChanged(list: List<StreamServer.ClientInfo>) {
        if (!running) return
        clients = list
        val now = System.currentTimeMillis()
        if (list.any { !it.mjpeg }) {
            stopEncoderAt = 0
            if (encoder == null) startEncoder() else updateBitrate()
        } else if (encoder != null) stopEncoderAt = now + ENCODER_LINGER_MS     // OBS often reconnects at once
        if (list.any { it.mjpeg }) {
            stopJpegAt = 0
            if (jpeg == null) startJpeg()
        } else if (jpeg != null) stopJpegAt = now + ENCODER_LINGER_MS
        publish()
    }

    /** Motion JPEG pipeline: camera -> GPU pass (upright, portrait) -> ImageReader -> JPEG per picture. */
    private fun startJpeg() {
        val size = settings.height
        val fps = settings.fps
        val j = MjpegEncoder(
            size, Presets.longSide(size), fps, JPEG_QUALITY,
            onFrame = { f: Frame -> frames++; server?.broadcastJpeg(f) },
            onError = { ex -> main.post { error = "JPEG encoder stopped: ${ex.message}"; stopJpeg(); server?.dropClients(); publish() } },
        )
        var r: FrameRotator? = null
        try {
            j.start()
            r = FrameRotator(j.inputSurface, j.width, j.height, j.height, j.width)
            r.start()
        } catch (ex: Exception) {
            r?.stop(); j.stop()
            error = "JPEG encoder failed: ${ex.message}"
            server?.dropClients()
            return
        }
        jpeg = j; jpegRotator = r; jpegFront = settings.frontCamera
        error = null
        camera.setJpegSurface(r.inputSurface)
    }

    private fun stopJpeg() {
        val j = jpeg ?: return
        jpeg = null
        camera.setJpegSurface(null, wait = true)
        jpegRotator?.stop(); jpegRotator = null
        j.stop()
    }

    private var lastKeyframeRequest = 0L

    /** A keyframe is a burst of about 100 KB, so requests are spaced out: a stall must not trigger a second burst. */
    private fun requestKeyframe() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastKeyframeRequest < KEYFRAME_REQUEST_GAP_MS) return
        lastKeyframeRequest = now
        encoder?.requestKeyframe()
    }

    private fun startEncoder() {
        val size = settings.height
        val fps = settings.fps
        // Portrait stream: the short side is the width. The camera delivers landscape pixels.
        val e = VideoEncoder(
            size, Presets.longSide(size), fps, currentTargetKbps(),
            onFrame = { f: Frame -> frames++; server?.broadcast(f) },
            onError = { ex -> main.post { encoderFailed(ex) } },
        )
        var r: FrameRotator? = null
        try {
            e.start()
            r = FrameRotator(e.inputSurface!!, e.width, e.height, e.height, e.width)
            r.start()
        } catch (ex: Exception) {
            r?.stop()
            e.stop()
            error = "Encoder failed: ${ex.message}"
            server?.dropClients()
            return
        }
        encoder = e
        rotator = r
        encoderFront = settings.frontCamera
        error = null
        camera.setEncoderSurface(r.inputSurface)
    }

    private fun encoderFailed(ex: Exception) {
        stopEncoder()
        error = "Encoder stopped: ${ex.message}"
        server?.dropClients()
        publish()
    }

    private fun stopEncoder() {
        val e = encoder ?: return
        encoder = null
        camera.setEncoderSurface(null, wait = true)
        rotator?.stop(); rotator = null
        e.stop()
        fpsNow = 0f; kbpsNow = 0
    }

    private fun currentTargetKbps(): Int {
        val links = Links.current()
        val kinds = clients.filter { !it.mjpeg }.map { Links.kindOf(it.local, links) }
        return Presets.bitrateKbps(settings.bitrateKbps, kinds, settings.height, settings.fps, Presets.thermalFactor(thermalStatus))
    }

    private fun updateBitrate() { encoder?.setBitrateKbps(currentTargetKbps()) }

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            val sent = server?.bytesSent?.get() ?: 0L
            kbpsNow = ((sent - lastBytes) * 8 / 1000).toInt(); lastBytes = sent
            fpsNow = frames.toFloat(); frames = 0
            val dropped = server?.framesDropped?.get() ?: 0L
            dropHistory.addLast(dropped - lastDropped); lastDropped = dropped
            while (dropHistory.size > 10) dropHistory.removeFirst()
            if (stopEncoderAt != 0L && System.currentTimeMillis() >= stopEncoderAt && clients.isEmpty()) {
                stopEncoderAt = 0; stopEncoder()
            }
            if (stopJpegAt != 0L && System.currentTimeMillis() >= stopJpegAt && clients.none { it.mjpeg }) {
                stopJpegAt = 0; stopJpeg()
            }
            publish()
            main.postDelayed(this, 1000)
        }
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Streaming", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, StreamService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("Cable Cam is streaming the camera")
            .setContentText("Open OBS and add the URL shown in the app")
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    companion object {
        private const val CHANNEL = "stream"
        private const val NOTIF_ID = 1
        private const val ACTION_STOP = "com.cablecam.STOP"
        private const val ENCODER_LINGER_MS = 5000L
        private const val KEYFRAME_REQUEST_GAP_MS = 1000L
        /** Experiment: JPEG quality for the MJPEG endpoint (about 60 KB per 720p picture at 75). */
        private const val JPEG_QUALITY = 75
        @Volatile var instance: StreamService? = null
            private set

        fun start(c: Context) = c.startForegroundService(Intent(c, StreamService::class.java))
        fun stop(c: Context) { c.startService(Intent(c, StreamService::class.java).setAction(ACTION_STOP)) }
    }
}
