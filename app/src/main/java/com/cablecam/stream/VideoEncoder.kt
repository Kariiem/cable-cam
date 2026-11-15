package com.cablecam.stream

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
import android.view.Surface

/**
 * H.264 hardware encoder fed through an input [Surface] (the camera draws into it). Output is
 * Annex-B access units; SPS/PPS are put in front of every keyframe, so a client can join at any one.
 */
class VideoEncoder(
    val width: Int,
    val height: Int,
    val fps: Int,
    bitrateKbps: Int,
    private val onFrame: (Frame) -> Unit,
    private val onError: (Exception) -> Unit,
) {
    private var codec: MediaCodec? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    private var config: ByteArray? = null          // SPS+PPS
    private var baseUs = -1L
    @Volatile var currentKbps = bitrateKbps
        private set

    var inputSurface: Surface? = null
        private set

    /** Creates the encoder and starts the output thread. Throws if no encoder takes this format. */
    fun start() {
        val c = createCodec()
        codec = c
        inputSurface = c.createInputSurface()
        c.start()
        running = true
        thread = Thread({ drain(c) }, "cam-encoder").apply { start() }
    }

    private fun format(withProfile: Boolean, vbr: Boolean) = MediaFormat.createVideoFormat(MIME, width, height).apply {
        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        setInteger(MediaFormat.KEY_BIT_RATE, currentKbps * 1000)
        setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        setInteger(MediaFormat.KEY_BITRATE_MODE,
            if (vbr) MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR else MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
        if (withProfile) {
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel41)
        }
        setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)   // the TS muxer assumes decode order = display order
        setInteger(MediaFormat.KEY_LATENCY, 1)
        setInteger(MediaFormat.KEY_PRIORITY, 0)       // real time
    }

    /**
     * Tries the preferred format, then simpler ones: some MediaTek encoders reject a profile/level
     * or a bitrate mode that the capability query claims to support.
     */
    private fun createCodec(): MediaCodec {
        val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && MIME in it.supportedTypes }
            .sortedBy { if (it.isHardwareAccelerated) 0 else 1 }
            .firstOrNull()?.name ?: error("no H.264 encoder on this device")
        var last: Exception? = null
        for ((profile, vbr) in listOf(true to false, false to false, false to true)) {
            val c = MediaCodec.createByCodecName(name)
            try {
                c.configure(format(profile, vbr), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                Log.i(TAG, "encoder $name ${width}x$height@$fps ${currentKbps}kbps profile=$profile vbr=$vbr")
                return c
            } catch (e: Exception) {
                last = e
                Log.w(TAG, "encoder format rejected (profile=$profile vbr=$vbr): $e")
                c.release()
            }
        }
        throw last!!
    }

    /** Live bitrate change (no restart). */
    fun setBitrateKbps(kbps: Int) {
        if (kbps == currentKbps) return
        currentKbps = kbps
        runCatching {
            codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, kbps * 1000) })
        }
    }

    /** Asks for a keyframe as soon as possible (a client just joined, or dropped frames). */
    fun requestKeyframe() {
        runCatching {
            codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        }
    }

    private fun drain(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val i = c.dequeueOutputBuffer(info, 100_000)
                if (i < 0) continue
                val buf = c.getOutputBuffer(i)
                if (buf != null && info.size > 0) {
                    val data = ByteArray(info.size)
                    buf.position(info.offset); buf.get(data)
                    handle(data, info)
                }
                c.releaseOutputBuffer(i, false)
            }
        } catch (e: Exception) {
            if (running) onError(e)
        }
    }

    private fun handle(data: ByteArray, info: MediaCodec.BufferInfo) {
        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) { config = data; return }
        if (baseUs < 0) baseUs = info.presentationTimeUs
        val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        val cfg = config
        val out = if (key && cfg != null && !startsWithParameterSets(data)) cfg + data else data
        onFrame(Frame(out, info.presentationTimeUs - baseUs, key))
    }

    private fun startsWithParameterSets(d: ByteArray): Boolean {
        val i = if (d.size > 4 && d[2] == 1.toByte()) 3 else 4
        return d.size > i && (d[i].toInt() and 0x1F) == 7
    }

    fun stop() {
        running = false
        runCatching { thread?.join(1000) }
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { inputSurface?.release() }
        codec = null; inputSurface = null
    }

    companion object {
        private const val TAG = "CableCam"
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
    }
}
