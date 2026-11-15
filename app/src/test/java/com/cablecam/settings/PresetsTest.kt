package com.cablecam.settings

import com.cablecam.net.LinkKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PresetsTest {
    @Test fun widthIs16by9() {
        assertEquals(1280, Presets.width(720)); assertEquals(1920, Presets.width(1080))
    }

    @Test fun automaticBitrateByLink() {
        assertEquals(6000, Presets.autoKbps(LinkKind.USB, 720, 30))
        assertEquals(6000, Presets.autoKbps(LinkKind.WIFI, 720, 30))
        assertEquals(10_000, Presets.autoKbps(LinkKind.HOTSPOT, 1080, 30))
        assertEquals(9000, Presets.autoKbps(null, 720, 60))
        assertEquals(1200, Presets.autoKbps(LinkKind.BLUETOOTH, 1080, 60))
    }

    @Test fun encoderBitrate() {
        assertEquals("no clients yet", 6000, Presets.bitrateKbps(0, emptyList(), 720, 30, 1.0))
        assertEquals("slowest link wins", 1200, Presets.bitrateKbps(0, listOf(LinkKind.WIFI, LinkKind.BLUETOOTH), 720, 30, 1.0))
        assertEquals("manual wins over the link", 4000, Presets.bitrateKbps(4000, listOf(LinkKind.BLUETOOTH), 720, 30, 1.0))
        assertEquals("hot phone", 3600, Presets.bitrateKbps(0, listOf(LinkKind.USB), 720, 30, 0.6))
        assertEquals("floor", 300, Presets.bitrateKbps(800, emptyList(), 720, 30, 0.1))
    }

    @Test fun thermalFactors() {
        assertEquals(listOf(1.0, 1.0, 1.0, 0.6, 0.4, 0.4, 0.4), (0..6).map(Presets::thermalFactor))
    }

    @Test fun fpsRange() {
        val ranges = listOf(5 to 15, 15 to 15, 7 to 30, 15 to 30, 30 to 30, 20 to 60)
        assertEquals(30 to 30, Presets.pickFpsRange(ranges, 30))
        assertEquals(20 to 60, Presets.pickFpsRange(ranges, 60))
        assertEquals("no 60: fastest below", 30 to 30, Presets.pickFpsRange(ranges.dropLast(1), 60))
        assertEquals("variable range reaching the rate", 15 to 30, Presets.pickFpsRange(listOf(7 to 30, 15 to 30), 30))
        assertEquals("only faster ranges", 60 to 60, Presets.pickFpsRange(listOf(60 to 60, 120 to 120), 30))
        assertNull(Presets.pickFpsRange(emptyList(), 30))
    }
}
