package com.cablecam.stream

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TsMuxerTest {
    private class Packet(val b: ByteArray, val off: Int) {
        val sync get() = b[off].toInt() and 0xFF
        val pusi get() = b[off + 1].toInt() and 0x40 != 0
        val pid get() = (b[off + 1].toInt() and 0x1F shl 8) or (b[off + 2].toInt() and 0xFF)
        val afc get() = b[off + 3].toInt() shr 4 and 0x3
        val cc get() = b[off + 3].toInt() and 0x0F
        val afLen get() = if (afc and 2 != 0) (b[off + 4].toInt() and 0xFF) + 1 else 0   // length byte included
        val afFlags get() = b[off + 5].toInt() and 0xFF
        val payload get() = b.copyOfRange(off + 4 + afLen, off + 188)
    }

    private fun packets(ts: ByteArray): List<Packet> {
        assertEquals(0, ts.size % 188)
        return (ts.indices step 188).map { Packet(ts, it) }.onEach { assertEquals(0x47, it.sync) }
    }

    private fun au(n: Int) = ByteArray(n) { (it * 31 + 7).toByte() }

    /** Reassembles the PES packet from the video packets and checks the packet structure on the way. */
    private fun pes(ts: ByteArray): ByteArray {
        val ps = packets(ts)
        ps.forEachIndexed { i, p ->
            assertEquals(TsMuxer.VIDEO_PID, p.pid)
            assertEquals(i == 0, p.pusi)
            assertTrue("payload flag", p.afc and 1 != 0)
            if (i in 1 until ps.size - 1) assertEquals("middle packets are full", 1, p.afc)
        }
        return ps.fold(ByteArray(0)) { acc, p -> acc + p.payload }
    }

    @Test fun crcMatchesKnownValue() {
        // CRC-32/MPEG-2 check value for "123456789".
        assertEquals(0x0376E6E7, TsMuxer.crc32("123456789".toByteArray(), 0, 9))
    }

    @Test fun psiIsPatThenPmtWithValidCrc() {
        val ps = packets(TsMuxer().psi())
        assertEquals(2, ps.size)
        assertEquals(listOf(TsMuxer.PAT_PID, TsMuxer.PMT_PID), ps.map { it.pid })
        for (p in ps) {
            assertTrue(p.pusi); assertEquals(1, p.afc)
            val pl = p.payload
            assertEquals("pointer_field", 0, pl[0].toInt())
            val len = (pl[2].toInt() and 0x0F shl 8) or (pl[3].toInt() and 0xFF)
            assertEquals("CRC over the whole section is 0", 0, TsMuxer.crc32(pl, 1, 3 + len))
            assertTrue("rest is stuffing", pl.drop(1 + 3 + len).all { it == 0xFF.toByte() })
        }
        val pat = ps[0].payload
        assertEquals(0x00, pat[1].toInt())
        assertEquals("program 1", 1, (pat[9].toInt() and 0xFF shl 8) or (pat[10].toInt() and 0xFF))
        assertEquals(TsMuxer.PMT_PID, (pat[11].toInt() and 0x1F shl 8) or (pat[12].toInt() and 0xFF))
        val pmt = ps[1].payload
        assertEquals(0x02, pmt[1].toInt())
        assertEquals("PCR PID", TsMuxer.VIDEO_PID, (pmt[9].toInt() and 0x1F shl 8) or (pmt[10].toInt() and 0xFF))
        assertEquals(TsMuxer.STREAM_TYPE_H264, pmt[13].toInt())
        assertEquals(TsMuxer.VIDEO_PID, (pmt[14].toInt() and 0x1F shl 8) or (pmt[15].toInt() and 0xFF))
    }

    @Test fun pesHeaderAndPayload() {
        val data = au(1000)
        val pes = pes(TsMuxer().video(data, ptsUs = 1_000_000, keyframe = true))
        assertArrayEquals(byteArrayOf(0, 0, 1, 0xE0.toByte()), pes.copyOfRange(0, 4))
        val len = (pes[4].toInt() and 0xFF shl 8) or (pes[5].toInt() and 0xFF)
        assertEquals(pes.size - 6, len)
        assertEquals(0x80, pes[7].toInt() and 0xFF)        // PTS only
        assertEquals(5, pes[8].toInt())
        assertArrayEquals(TsMuxer.AUD, pes.copyOfRange(14, 20))
        assertArrayEquals(data, pes.copyOfRange(20, pes.size))
    }

    private fun pts(pes: ByteArray): Long {
        fun b(i: Int) = pes[i].toLong() and 0xFF
        assertEquals("'0010' prefix and marker", 0x21L, b(9) and 0xF1)
        assertEquals(1L, b(11) and 1); assertEquals(1L, b(13) and 1)
        return (b(9) shr 1 and 7 shl 30) or (b(10) shl 22) or (b(11) shr 1 shl 15) or (b(12) shl 7) or (b(13) shr 1)
    }

    @Test fun ptsEncoding() {
        for (us in longArrayOf(0, 1, 33_333, 1_000_000, 3_600_000_000, 90_000_000_000)) {
            val expect = (us + TsMuxer.PTS_LEAD_US) * 9 / 100 and (1L shl 33) - 1
            assertEquals("pts for $us us", expect, pts(pes(TsMuxer().video(au(10), us, false))))
        }
    }

    @Test fun pcrInFirstPacketAndRandomAccessFlag() {
        for (key in listOf(true, false)) {
            val us = 12_345_678L
            val first = packets(TsMuxer().video(au(5000), us, key))[0]
            assertEquals(3, first.afc)
            assertEquals(8, first.afLen)
            assertTrue("PCR flag", first.afFlags and 0x10 != 0)
            assertEquals("random access indicator", key, first.afFlags and 0x40 != 0)
            fun b(i: Int) = first.b[first.off + 6 + i].toLong() and 0xFF
            val base = (b(0) shl 25) or (b(1) shl 17) or (b(2) shl 9) or (b(3) shl 1) or (b(4) shr 7)
            val ext = (b(4) and 1 shl 8) or b(5)
            assertEquals(us * 9 / 100, base)
            assertEquals(0L, ext)
        }
    }

    @Test fun continuityCountersWrapPerPid() {
        val m = TsMuxer()
        val video = ArrayList<Int>(); val pat = ArrayList<Int>(); val pmt = ArrayList<Int>()
        repeat(20) { i ->
            val ps = packets(m.psi()) + packets(m.video(au(700), i * 33_333L, i % 5 == 0))
            for (p in ps) when (p.pid) {
                TsMuxer.PAT_PID -> pat += p.cc
                TsMuxer.PMT_PID -> pmt += p.cc
                else -> video += p.cc
            }
        }
        for (list in listOf(video, pat, pmt)) {
            assertTrue(list.size >= 20)
            list.forEachIndexed { i, cc -> assertEquals(i and 0x0F, cc) }
        }
    }

    @Test fun stuffingKeepsEveryPayloadSizeIntact() {
        // Sizes around the packet boundaries: first packet holds 176 PES bytes, later ones 184.
        for (n in (0..600) + listOf(5000, 5001, 65_000)) {
            val data = au(n)
            val ts = TsMuxer().video(data, 0, false)
            val pes = pes(ts)
            assertEquals("size $n", TsMuxer.PES_HEADER + TsMuxer.AUD.size + n, pes.size)
            assertArrayEquals(data, pes.copyOfRange(20, pes.size))
            for (p in packets(ts)) if (p.afLen > 1) {
                val from = p.off + 4 + if (p.pusi) 8 else 2
                assertTrue("stuffing is 0xFF", (from until p.off + 4 + p.afLen).all { ts[it] == 0xFF.toByte() })
                if (!p.pusi) assertEquals(0, p.afFlags)
            }
        }
    }

    @Test fun largeAccessUnitHasUnboundedPesLength() {
        val pes = pes(TsMuxer().video(au(70_000), 0, true))
        assertEquals(0, pes[4].toInt()); assertEquals(0, pes[5].toInt())
        assertFalse(pes.size <= 0xFFFF)
    }
}
