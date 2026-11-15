package com.cablecam.stream

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.io.ByteArrayOutputStream

/**
 * Motion JPEG: the GPU pass draws each upright frame into an ImageReader and every picture is compressed to a
 * stand-alone JPEG. No keyframes and no inter-frame state. If compressing is slower than the camera, the reader
 * hands over only the newest picture, so nothing queues up.
 */
class MjpegEncoder(
    val width: Int,
    val height: Int,
    val fps: Int,
    private val quality: Int,
    private val onFrame: (Frame) -> Unit,
    private val onError: (Exception) -> Unit,
) {
    private val thread = HandlerThread("cam-jpeg").apply { start() }
    private val handler = Handler(thread.looper)
    private val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
    private var bitmap: Bitmap? = null
    private val out = ByteArrayOutputStream(128 * 1024)
    private var baseNs = -1L
    private var latencyFrames = 0
    private var latencySumMs = 0L

    /** The GPU pass draws into this. */
    val inputSurface: Surface get() = reader.surface

    /** Average delay between the camera timestamp and the finished JPEG, last second, ms. */
    @Volatile var latencyMs = 0L
        private set

    fun start() {
        reader.setOnImageAvailableListener({ r ->
            val image = try { r.acquireLatestImage() } catch (e: Exception) { null } ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                val rowPixels = plane.rowStride / 4
                var bmp = bitmap
                if (bmp == null || bmp.width != rowPixels) {
                    bmp?.recycle()
                    bmp = Bitmap.createBitmap(rowPixels, height, Bitmap.Config.ARGB_8888).also { bitmap = it }
                }
                plane.buffer.rewind()
                bmp.copyPixelsFromBuffer(plane.buffer)
                out.reset()
                // Padding columns on the right (if any) are cut off by compressing only the visible part.
                val visible = if (rowPixels == width) bmp else Bitmap.createBitmap(bmp, 0, 0, width, height)
                visible.compress(Bitmap.CompressFormat.JPEG, quality, out)
                if (visible !== bmp) visible.recycle()
                val ts = image.timestamp
                if (baseNs < 0) baseNs = ts
                measureLatency(ts)
                onFrame(Frame(out.toByteArray(), (ts - baseNs) / 1000, true))
            } catch (e: Exception) {
                onError(e)
            } finally {
                image.close()
            }
        }, handler)
    }

    private fun measureLatency(tsNs: Long) {
        latencySumMs += (System.nanoTime() - tsNs) / 1_000_000
        if (++latencyFrames >= fps) { latencyMs = latencySumMs / latencyFrames; latencyFrames = 0; latencySumMs = 0 }
    }

    fun stop() {
        runCatching { reader.setOnImageAvailableListener(null, null) }
        thread.quitSafely()
        runCatching { thread.join(500) }
        runCatching { reader.close() }
        runCatching { bitmap?.recycle() }
        bitmap = null
    }
}
