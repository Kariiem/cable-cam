package com.cablecam.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.view.Surface
import com.cablecam.camera.CameraController
import com.cablecam.net.Links
import com.cablecam.settings.Presets
import com.cablecam.settings.Settings
import com.cablecam.stream.Frame
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
        val thermal: Int = 0, val dropped: Long = 0,
    )

    inner class LocalBinder : Binder() { val service get() = this@StreamService }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var settings: Settings
    private lateinit var camera: CameraController
    private var server: StreamServer? = null
    private var encoder: VideoEncoder? = null
    private var wakeLock: PowerManager.WakeLock? = null
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
    private var activePreview: Surface? = null

    /** Called on the main thread whenever the status changes (about once a second while streaming). */
    var onStatus: ((Status) -> Unit)? = null
        set(v) { field = v; v?.invoke(status()) }

    fun status() = Status(
        running, error, clients, encoder != null, encoder?.width ?: 0, encoder?.height ?: 0,
        fpsNow, kbpsNow, encoder?.currentKbps ?: 0, thermalStatus, server?.framesDropped?.get() ?: 0,
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
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        thermalStatus = pm.currentThermalStatus
        pm.addThermalStatusListener(mainExecutor, thermalListener)

        val s = StreamServer(StreamServer.DEFAULT_PORT, object : StreamServer.Listener {
            override fun onClientsChanged(clients: List<StreamServer.ClientInfo>) { main.post { clientsChanged(clients) } }
            override fun onNeedKeyframe() { main.post { encoder?.requestKeyframe() } }
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
        stopEncoder()
        server?.stop(); server = null
        camera.close()
        (getSystemService(POWER_SERVICE) as PowerManager).removeThermalStatusListener(thermalListener)
        wakeLock?.release(); wakeLock = null
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
        if (e != null && (e.height != settings.height || e.fps != settings.fps)) {
            stopEncoder(); server?.dropClients()        // OBS reconnects and gets the new format
        } else updateBitrate()
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
        if (list.isNotEmpty()) {
            stopEncoderAt = 0
            if (encoder == null) startEncoder() else updateBitrate()
        } else if (encoder != null) {
            stopEncoderAt = System.currentTimeMillis() + ENCODER_LINGER_MS     // OBS often reconnects at once
        }
        publish()
    }

    private fun startEncoder() {
        val h = settings.height
        val e = VideoEncoder(
            Presets.width(h), h, settings.fps, currentTargetKbps(),
            onFrame = { f: Frame -> frames++; server?.broadcast(f) },
            onError = { ex -> main.post { encoderFailed(ex) } },
        )
        try {
            e.start()
        } catch (ex: Exception) {
            e.stop()
            error = "Encoder failed: ${ex.message}"
            server?.dropClients()
            return
        }
        encoder = e
        error = null
        camera.setEncoderSurface(e.inputSurface)
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
        camera.setEncoderSurface(null)
        e.stop()
        fpsNow = 0f; kbpsNow = 0
    }

    private fun currentTargetKbps(): Int {
        val links = Links.current()
        val kinds = clients.map { Links.kindOf(it.local, links) }
        return Presets.bitrateKbps(settings.bitrateKbps, kinds, settings.height, settings.fps, Presets.thermalFactor(thermalStatus))
    }

    private fun updateBitrate() { encoder?.setBitrateKbps(currentTargetKbps()) }

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            val sent = server?.bytesSent?.get() ?: 0L
            kbpsNow = ((sent - lastBytes) * 8 / 1000).toInt(); lastBytes = sent
            fpsNow = frames.toFloat(); frames = 0
            if (stopEncoderAt != 0L && System.currentTimeMillis() >= stopEncoderAt && clients.isEmpty()) {
                stopEncoderAt = 0; stopEncoder()
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
        @Volatile var instance: StreamService? = null
            private set

        fun start(c: Context) = c.startForegroundService(Intent(c, StreamService::class.java))
        fun stop(c: Context) { c.startService(Intent(c, StreamService::class.java).setAction(ACTION_STOP)) }
    }
}
