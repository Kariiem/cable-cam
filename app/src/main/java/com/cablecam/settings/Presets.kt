package com.cablecam.settings

import com.cablecam.net.LinkKind

/** Pure numbers behind the settings: sizes, bitrates per link, thermal back-off. */
object Presets {
    val HEIGHTS = listOf(720, 1080)
    val FPS = listOf(30, 60)
    /** 60 fps is only offered at 720p: 1080p60 is too heavy for the link and for software decoding on the PC. */
    fun fpsFor(size: Int, fps: Int) = if (size > 720) 30 else fps
    /** Manual bitrate choices in kbit/s; 0 means automatic (by link). */
    val BITRATES_KBPS = listOf(0, 800, 1200, 2000, 4000, 6000, 8000, 12000)
    const val BLUETOOTH_KBPS = 1200

    /** [size] is the short side of the (portrait) stream: 720 gives 720x1280. */
    fun longSide(size: Int) = size * 16 / 9

    /** Automatic bitrate for one client. Bluetooth PAN carries roughly 1-2 Mbit/s, whatever the video size. */
    fun autoKbps(kind: LinkKind?, height: Int, fps: Int): Int {
        if (kind == LinkKind.BLUETOOTH) return BLUETOOTH_KBPS
        val base = if (height >= 1080) 8_000 else 4_000
        return if (fps > 30) base * 3 / 2 else base
    }

    /**
     * The encoder bitrate: the manual setting, or the automatic value for the slowest connected
     * link, scaled down while the phone is hot.
     */
    fun bitrateKbps(manualKbps: Int, clients: List<LinkKind?>, height: Int, fps: Int, thermalFactor: Double): Int {
        val wanted = if (manualKbps > 0) manualKbps
        else clients.ifEmpty { listOf(null) }.minOf { autoKbps(it, height, fps) }
        return maxOf(300, (wanted * thermalFactor).toInt())
    }

    /** [status] is a PowerManager.THERMAL_STATUS_* value (0 none ... 3 severe, 4 critical, 5 emergency, 6 shutdown). */
    fun thermalFactor(status: Int): Double = when {
        status >= 4 -> 0.4
        status == 3 -> 0.6
        else -> 1.0
    }

    /**
     * Picks the camera's auto-exposure fps range for a wanted frame rate: a fixed range if there
     * is one, else the one reaching the rate with the highest lower bound, else the fastest.
     */
    fun pickFpsRange(ranges: List<Pair<Int, Int>>, fps: Int): Pair<Int, Int>? =
        ranges.filter { it.second == fps }.maxByOrNull { it.first }
            ?: ranges.filter { it.second < fps }.maxWithOrNull(compareBy({ it.second }, { it.first }))
            ?: ranges.minByOrNull { it.second }
}
