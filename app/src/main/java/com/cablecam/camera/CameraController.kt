package com.cablecam.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodec
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.view.Surface
import com.cablecam.settings.Presets

/**
 * Camera2: one capture session with up to two outputs, the preview surface and the encoder's input
 * surface. The encoder surface comes and goes (we only encode while someone watches), which
 * recreates the session. All calls are cheap and may come from the main thread.
 */
class CameraController(context: Context, private val onError: (String) -> Unit) {
    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("cam-camera").apply { start() }
    private val handler = Handler(thread.looper)

    // Everything below is touched on [handler] only.
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var cameraId: String? = null
    private var preview: Surface? = null
    private var encoder: Surface? = null
    private var jpeg: Surface? = null
    private var fps = 30
    private var stabilise = false
    private var torch = false
    private var opening = false
    private var generation = 0     // invalidates callbacks of older sessions

    data class Info(val id: String, val front: Boolean, val heights: List<Int>, val maxFps: Int, val torch: Boolean, val sensorOrientation: Int)

    /** What a camera offers (16:9 sizes the encoder can take), for the settings screen. */
    fun info(front: Boolean): Info? = runCatching {
        val id = findId(front) ?: return null
        val c = manager.getCameraCharacteristics(id)
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val heights = map.getOutputSizes(MediaCodec::class.java).orEmpty()
            .filter { it.width == Presets.longSide(it.height) }.map { it.height }.filter { it in Presets.HEIGHTS }
            .distinct().sorted()
        val maxFps = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty().maxOfOrNull { it.upper } ?: 30
        Info(id, front, heights, maxFps, c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true,
            c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90)
    }.getOrNull()

    private fun findId(front: Boolean): String? {
        val want = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        return manager.cameraIdList.firstOrNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == want
        }
    }

    /** Opens the camera (or switches to another one). [previewSurface] may be null while the UI is hidden. */
    fun open(front: Boolean, fps: Int, stabilise: Boolean) = handler.post {
        val id = findId(front)
        if (id == null) { onError("No ${if (front) "front" else "back"} camera"); return@post }
        this.fps = fps; this.stabilise = stabilise
        if (id == cameraId && device != null) { rebuild(); return@post }
        closeDevice()
        cameraId = id
        openDevice(id)
    }

    @SuppressLint("MissingPermission")   // the caller checks the CAMERA permission
    private fun openDevice(id: String) {
        if (opening) return
        opening = true
        try {
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    opening = false
                    if (cameraId != id) { d.close(); return }
                    device = d; rebuild()
                }
                override fun onDisconnected(d: CameraDevice) {
                    opening = false; d.close(); if (device === d) { device = null; session = null }
                    onError("Camera disconnected (another app took it?)")
                }
                override fun onError(d: CameraDevice, error: Int) {
                    opening = false; d.close(); if (device === d) { device = null; session = null }
                    this@CameraController.onError("Camera error $error")
                }
            }, handler)
        } catch (e: Exception) {
            opening = false
            onError("Cannot open camera: ${e.message}")
        }
    }

    fun setPreview(s: Surface?) = handler.post { if (preview !== s) { preview = s; rebuild() } }

    /** With [wait], returns after the capture session no longer uses the old surface (so it can be released). */
    fun setEncoderSurface(s: Surface?, wait: Boolean = false) = swap(wait) { if (encoder !== s) { encoder = s; true } else false }

    /** The MJPEG pipeline's surface; same rules as [setEncoderSurface]. */
    fun setJpegSurface(s: Surface?, wait: Boolean = false) = swap(wait) { if (jpeg !== s) { jpeg = s; true } else false }

    private fun swap(wait: Boolean, change: () -> Boolean) {
        val done = java.util.concurrent.CountDownLatch(1)
        handler.post { if (change()) rebuild(); done.countDown() }
        if (wait) done.await(1, java.util.concurrent.TimeUnit.SECONDS)
    }

    fun setTorch(on: Boolean) = handler.post { torch = on; applyRepeating() }

    private fun rebuild() {
        val d = device ?: return
        val outputs = listOfNotNull(preview?.takeIf { it.isValid }, encoder?.takeIf { it.isValid }, jpeg?.takeIf { it.isValid })
        val gen = ++generation
        runCatching { session?.close() }
        session = null
        if (outputs.isEmpty()) return
        try {
            d.createCaptureSession(outputs, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (gen != generation) { s.close(); return }
                    session = s; applyRepeating()
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    if (gen == generation) onError("Camera session failed")
                }
            }, handler)
        } catch (e: Exception) {
            Log.w(TAG, "createCaptureSession: $e")
            onError("Camera session failed: ${e.message}")
        }
    }

    private fun applyRepeating() {
        val d = device ?: return
        val s = session ?: return
        val id = cameraId ?: return
        try {
            val c = manager.getCameraCharacteristics(id)
            val b = d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            preview?.takeIf { it.isValid }?.let(b::addTarget)
            encoder?.takeIf { it.isValid }?.let(b::addTarget)
            jpeg?.takeIf { it.isValid }?.let(b::addTarget)
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            val ranges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
                .map { it.lower to it.upper }
            Presets.pickFpsRange(ranges, fps)?.let { b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(it.first, it.second)) }
            if (stabilise) {
                val modes = c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
                if (modes?.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true)
                    b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
            }
            if (c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true)
                b.set(CaptureRequest.FLASH_MODE, if (torch) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
            s.setRepeatingRequest(b.build(), null, handler)
        } catch (e: Exception) {
            Log.w(TAG, "setRepeatingRequest: $e")
        }
    }

    private fun closeDevice() {
        generation++
        runCatching { session?.close() }
        runCatching { device?.close() }
        session = null; device = null
    }

    /** Closes the camera; the controller can be opened again. */
    fun close() = handler.post { closeDevice(); cameraId = null }

    fun release() {
        handler.post { closeDevice(); thread.quitSafely() }
    }

    companion object { private const val TAG = "CableCam" }
}
