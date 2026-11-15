package com.cablecam.stream

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Feeds real H.264 through the muxer and has ffmpeg decode it. Skipped where ffmpeg is not installed. */
class FfmpegCompatTest {
    private fun run(vararg cmd: String, out: File? = null): Pair<Int, String> {
        val p = ProcessBuilder(*cmd).redirectErrorStream(out == null).apply { if (out != null) redirectOutput(out) }.start()
        val text = if (out == null) p.inputStream.readBytes().decodeToString() else ""
        if (!p.waitFor(60, TimeUnit.SECONDS)) { p.destroyForcibly(); error("timeout: ${cmd.toList()}") }
        return p.exitValue() to text
    }

    private fun have(tool: String) = runCatching { run(tool, "-version").first == 0 }.getOrDefault(false)

    /** Splits an Annex-B stream with access unit delimiters into access units (without the delimiter). */
    private fun accessUnits(d: ByteArray): List<ByteArray> {
        val starts = ArrayList<Int>()
        var i = 0
        while (i + 5 < d.size) {
            if (d[i] == 0.toByte() && d[i + 1] == 0.toByte() && d[i + 2] == 0.toByte() && d[i + 3] == 1.toByte() &&
                (d[i + 4].toInt() and 0x1F) == 9) starts += i
            i++
        }
        return starts.mapIndexed { n, s -> d.copyOfRange(s + TsMuxer.AUD.size, starts.getOrNull(n + 1) ?: d.size) }
    }

    @Test fun ffmpegDecodesOurStream() {
        assumeTrue(have("ffmpeg") && have("ffprobe"))
        val dir = createTempDir("cablecam")
        try {
            val h264 = File(dir, "in.h264")
            val (code, log) = run(
                "ffmpeg", "-v", "error", "-f", "lavfi", "-i", "testsrc=size=640x360:rate=30:duration=2",
                "-c:v", "libx264", "-bf", "0", "-g", "30", "-bsf:v", "h264_metadata=aud=insert",
                "-f", "h264", h264.path, "-y"
            )
            assumeTrue("no libx264: $log", code == 0)
            val aus = accessUnits(h264.readBytes())
            assertEquals(60, aus.size)
            val m = TsMuxer()
            val ts = File(dir, "out.ts")
            ts.outputStream().use { o ->
                aus.forEachIndexed { i, au ->
                    val key = containsIdr(au)
                    if (key) o.write(m.psi())
                    o.write(m.video(au, i * 33_333L, key))
                }
            }
            val (pc, probe) = run(
                "ffprobe", "-v", "error", "-count_frames", "-select_streams", "v:0",
                "-show_entries", "stream=codec_name,width,height,nb_read_frames", "-of", "default=nw=1", ts.path
            )
            assertEquals(probe, 0, pc)
            assertTrue(probe, "codec_name=h264" in probe)
            assertTrue(probe, "width=640" in probe && "height=360" in probe)
            assertTrue(probe, "nb_read_frames=60" in probe)
            // A strict decode must not report continuity or sync errors.
            val (dc, dlog) = run("ffmpeg", "-v", "warning", "-i", ts.path, "-f", "null", "-")
            assertEquals(dlog, 0, dc)
            assertTrue(dlog, dlog.isBlank())
        } finally { dir.deleteRecursively() }
    }

    private fun containsIdr(au: ByteArray): Boolean {
        for (i in 0 until au.size - 4) if (au[i] == 0.toByte() && au[i + 1] == 0.toByte() &&
            (au[i + 2] == 1.toByte() || (au[i + 2] == 0.toByte() && au[i + 3] == 1.toByte()))) {
            val h = if (au[i + 2] == 1.toByte()) au[i + 3] else au[i + 4]
            if ((h.toInt() and 0x1F) == 5) return true
        }
        return false
    }
}
