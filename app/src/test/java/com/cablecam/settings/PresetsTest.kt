package com.cablecam.settings

import com.cablecam.net.LinkKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetsTest {
    @Test fun portraitLongSide() {
        assertEquals(1280, Presets.longSide(720)); assertEquals(1920, Presets.longSide(1080))
    }

    @Test fun adviceDescribesTheCombination() {
        assertEquals("Recommended: smooth and light, about 4 Mbit/s.", Presets.advice(720, 30, 0))
        assertTrue(Presets.advice(1080, 60, 0).startsWith("Heavy: about 12 Mbit/s"))
        assertTrue(Presets.advice(1080, 30, 0).startsWith("Sharper but about 8 Mbit/s"))
        assertTrue(Presets.advice(720, 60, 0).startsWith("Smoother motion at about 6 Mbit/s"))
        assertTrue("manual bitrate", Presets.advice(720, 30, 2000).startsWith("About 2 Mbit/s"))
    }

    @Test fun automaticBitrateByLink() {
        assertEquals(4000, Presets.autoKbps(LinkKind.USB, 720, 30))
        assertEquals(4000, Presets.autoKbps(LinkKind.WIFI, 720, 30))
        assertEquals(8000, Presets.autoKbps(LinkKind.HOTSPOT, 1080, 30))
        assertEquals(6000, Presets.autoKbps(null, 720, 60))
        assertEquals(1200, Presets.autoKbps(LinkKind.BLUETOOTH, 1080, 60))
    }

    @Test fun encoderBitrate() {
        assertEquals("no clients yet", 4000, Presets.bitrateKbps(0, emptyList(), 720, 30, 1.0))
        assertEquals("slowest link wins", 1200, Presets.bitrateKbps(0, listOf(LinkKind.WIFI, LinkKind.BLUETOOTH), 720, 30, 1.0))
        assertEquals("manual wins over the link", 4000, Presets.bitrateKbps(4000, listOf(LinkKind.BLUETOOTH), 720, 30, 1.0))
        assertEquals("hot phone", 2400, Presets.bitrateKbps(0, listOf(LinkKind.USB), 720, 30, 0.6))
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
