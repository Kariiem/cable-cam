package com.cablecam.stream

/**
 * Minimal MPEG-TS muxer for one H.264 video stream. Pure Kotlin (no Android classes), so it is
 * unit tested on the JVM.
 *
 * Layout: program 1, PMT on PID 0x1000, video (stream type 0x1B) on PID 0x100, which also carries
 * the PCR in the first packet of every access unit. Not thread safe: use one muxer per client, so
 * continuity counters stay correct when a slow client drops frames.
 */
class TsMuxer {
    private var patCc = 0
    private var pmtCc = 0
    private var videoCc = 0

    /** PAT + PMT (two packets). Send at the start of a stream and before every keyframe. */
    fun psi(): ByteArray {
        val out = ByteArray(2 * PACKET)
        psiPacket(out, 0, PAT_PID, patCc, PAT_SECTION); patCc = (patCc + 1) and 0x0F
        psiPacket(out, PACKET, PMT_PID, pmtCc, PMT_SECTION); pmtCc = (pmtCc + 1) and 0x0F
        return out
    }

    /**
     * One access unit (Annex-B, start codes included; SPS/PPS already in front of keyframes) as a
     * PES packet split into TS packets. An access unit delimiter is added in front, which lets
     * the receiver find frame boundaries without waiting for more data.
     */
    fun video(au: ByteArray, ptsUs: Long, keyframe: Boolean): ByteArray {
        val payloadLen = AUD.size + au.size
        val pes = ByteArray(PES_HEADER + payloadLen)
        pes[2] = 0x01
        pes[3] = VIDEO_STREAM_ID.toByte()
        val pesLen = 3 + 5 + payloadLen                  // bytes after the length field
        if (pesLen <= 0xFFFF) {                          // 0 = unbounded, allowed for video only
            pes[4] = (pesLen shr 8).toByte(); pes[5] = pesLen.toByte()
        }
        pes[6] = 0x84.toByte()                           // '10', data_alignment_indicator
        pes[7] = 0x80.toByte()                           // PTS only (no B-frames, so DTS = PTS)
        pes[8] = 5
        val pts = (ptsUs + PTS_LEAD_US) * 9 / 100 and MASK33
        pes[9] = (0x21L or (pts shr 29 and 0x0E)).toByte()
        pes[10] = (pts shr 22).toByte()
        pes[11] = (0x01L or (pts shr 14 and 0xFE)).toByte()
        pes[12] = (pts shr 7).toByte()
        pes[13] = (0x01L or (pts shl 1 and 0xFE)).toByte()
        AUD.copyInto(pes, PES_HEADER)
        au.copyInto(pes, PES_HEADER + AUD.size)

        // First packet: 8 bytes of adaptation field (PCR). The rest: 184 payload bytes each.
        val first = PACKET - 4 - 8
        val packets = if (pes.size <= first) 1 else 1 + (pes.size - first + 183) / 184
        val out = ByteArray(packets * PACKET)
        var src = 0
        var dst = 0
        while (src < pes.size) {
            val start = src == 0
            val afFixed = if (start) 8 else 0            // adaptation field bytes that are not stuffing
            val n = minOf(pes.size - src, 184 - afFixed)
            val stuffing = 184 - afFixed - n
            val af = afFixed + stuffing                  // total adaptation field size, length byte included
            out[dst] = SYNC
            out[dst + 1] = ((if (start) 0x40 else 0) or (VIDEO_PID shr 8)).toByte()
            out[dst + 2] = VIDEO_PID.toByte()
            out[dst + 3] = ((if (af > 0) 0x30 else 0x10) or videoCc).toByte()
            videoCc = (videoCc + 1) and 0x0F
            var p = dst + 4
            if (af > 0) {
                out[p] = (af - 1).toByte()
                if (af > 1) {
                    out[p + 1] = if (start) (0x10 or (if (keyframe) 0x40 else 0)).toByte() else 0
                    var q = p + 2
                    if (start) {
                        val base = ptsUs * 9 / 100 and MASK33      // 90 kHz; the 27 MHz extension stays 0
                        out[q] = (base shr 25).toByte()
                        out[q + 1] = (base shr 17).toByte()
                        out[q + 2] = (base shr 9).toByte()
                        out[q + 3] = (base shr 1).toByte()
                        out[q + 4] = (base and 1 shl 7 or 0x7E).toByte()
                        out[q + 5] = 0
                        q += 6
                    }
                    out.fill(0xFF.toByte(), q, p + af)
                }
                p += af
            }
            pes.copyInto(out, p, src, src + n)
            src += n
            dst += PACKET
        }
        return out
    }

    private fun psiPacket(out: ByteArray, at: Int, pid: Int, cc: Int, section: ByteArray) {
        out[at] = SYNC
        out[at + 1] = (0x40 or (pid shr 8)).toByte()      // payload_unit_start
        out[at + 2] = pid.toByte()
        out[at + 3] = (0x10 or cc).toByte()               // payload only
        out[at + 4] = 0                                   // pointer_field
        section.copyInto(out, at + 5)
        out.fill(0xFF.toByte(), at + 5 + section.size, at + PACKET)
    }

    companion object {
        const val PACKET = 188
        const val PAT_PID = 0
        const val PMT_PID = 0x1000
        const val VIDEO_PID = 0x100
        const val VIDEO_STREAM_ID = 0xE0
        const val STREAM_TYPE_H264 = 0x1B
        const val PES_HEADER = 14
        /** The PTS is this far ahead of the PCR, so a receiver that honours the PCR has time to decode. */
        const val PTS_LEAD_US = 100_000L
        private const val SYNC: Byte = 0x47
        private const val MASK33 = (1L shl 33) - 1

        /** Null packet (PID 0x1FFF): ignored by receivers; used as a keep-alive when no video flows. */
        val NULL_PACKET = ByteArray(PACKET) { 0xFF.toByte() }.also {
            it[0] = SYNC; it[1] = 0x1F; it[3] = 0x10
        }

        /** Access unit delimiter NAL (type 9, any slice type). */
        val AUD = byteArrayOf(0, 0, 0, 1, 0x09, 0xF0.toByte())

        private val PAT_SECTION = section(
            0x00, 0x00, 0x01,                                  // table_id, then transport_stream_id = 1
            byteArrayOf(0x00, 0x01, (0xE0 or (PMT_PID shr 8)).toByte(), PMT_PID.toByte())
        )
        private val PMT_SECTION = section(
            0x02, 0x00, 0x01,                                  // table_id, then program_number = 1
            byteArrayOf(
                (0xE0 or (VIDEO_PID shr 8)).toByte(), VIDEO_PID.toByte(),   // PCR PID
                0xF0.toByte(), 0x00,                                         // program_info_length = 0
                STREAM_TYPE_H264.toByte(),
                (0xE0 or (VIDEO_PID shr 8)).toByte(), VIDEO_PID.toByte(),
                0xF0.toByte(), 0x00                                          // ES_info_length = 0
            )
        )

        private fun section(tableId: Int, idHi: Int, idLo: Int, body: ByteArray): ByteArray {
            val len = 5 + body.size + 4                        // bytes after section_length, CRC included
            val s = ByteArray(3 + len)
            s[0] = tableId.toByte()
            s[1] = (0xB0 or (len shr 8)).toByte()              // section_syntax_indicator, reserved bits
            s[2] = len.toByte()
            s[3] = idHi.toByte(); s[4] = idLo.toByte()
            s[5] = 0xC1.toByte()                               // version 0, current_next = 1
            s[6] = 0; s[7] = 0                                 // section_number, last_section_number
            body.copyInto(s, 8)
            val crc = crc32(s, 0, s.size - 4)
            s[s.size - 4] = (crc ushr 24).toByte()
            s[s.size - 3] = (crc ushr 16).toByte()
            s[s.size - 2] = (crc ushr 8).toByte()
            s[s.size - 1] = crc.toByte()
            return s
        }

        /** CRC-32/MPEG-2: polynomial 0x04C11DB7, initial value all ones, not reflected, no final xor. */
        fun crc32(data: ByteArray, off: Int, len: Int): Int {
            var crc = -1
            for (i in off until off + len) {
                crc = crc xor (data[i].toInt() shl 24)
                repeat(8) { crc = if (crc < 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1 }
            }
            return crc
        }
    }
}
