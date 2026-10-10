package io.github.willheisenberg.kodiscreencast

import java.io.ByteArrayOutputStream

/**
 * Verpackt HEVC-Bilder in einen MPEG-TS-Strom mit genau einem Videoprogramm.
 *
 * Vor jedem Keyframe stehen PAT und PMT, damit Kodi an jeder Stelle
 * einsteigen kann, auch nach einer Sendepause.
 */
class TsMuxer {
    private val counters = HashMap<Int, Int>()

    fun mux(accessUnit: ByteArray, ptsSeconds: Double, keyframe: Boolean): ByteArray {
        val out = ByteArrayOutputStream(accessUnit.size + accessUnit.size / 20 + 600)
        if (keyframe) {
            psiPacket(0, patSection(), out)
            psiPacket(PMT_PID, pmtSection(), out)
        }

        // Zeitstempel haben 33 Bit und laufen nach gut 26 Stunden über.
        val ticks = (maxOf(0.0, ptsSeconds) * 90_000).toLong() + PTS_OFFSET
        val pts = ticks and 0x1_FFFF_FFFFL
        val pcr = (ticks - PCR_LEAD) and 0x1_FFFF_FFFFL
        val payload = pesHeader(pts) + accessUnit

        var offset = 0
        var first = true
        while (offset < payload.size) {
            var adaptation: ByteArray? = if (first) pcrField(pcr, keyframe) else null
            val remaining = payload.size - offset
            var space = 184 - (adaptation?.let { it.size + 1 } ?: 0)
            if (remaining < space) {
                // Das letzte Paket wird über das Adaptation Field aufgefüllt.
                val padding = space - remaining
                adaptation = when {
                    adaptation != null -> adaptation + stuffing(padding)
                    padding == 1 -> ByteArray(0)
                    else -> byteArrayOf(0x00) + stuffing(padding - 2)
                }
                space = remaining
            }

            out.write(0x47)
            out.write((if (first) 0x40 else 0x00) or (VIDEO_PID shr 8 and 0x1F))
            out.write(VIDEO_PID and 0xFF)
            out.write((if (adaptation == null) 0x10 else 0x30) or nextCounter(VIDEO_PID))
            if (adaptation != null) {
                out.write(adaptation.size)
                out.write(adaptation, 0, adaptation.size)
            }
            out.write(payload, offset, space)
            offset += space
            first = false
        }
        return out.toByteArray()
    }

    private fun nextCounter(pid: Int): Int {
        val value = counters[pid] ?: 0
        counters[pid] = (value + 1) and 0x0F
        return value
    }

    private fun psiPacket(pid: Int, section: ByteArray, out: ByteArrayOutputStream) {
        out.write(0x47)
        out.write(0x40 or (pid shr 8 and 0x1F))
        out.write(pid and 0xFF)
        out.write(0x10 or nextCounter(pid))
        out.write(0x00)  // pointer_field
        out.write(section, 0, section.size)
        out.write(stuffing(183 - section.size), 0, 183 - section.size)
    }

    companion object {
        const val PACKET_SIZE = 188

        /** Sieben TS-Pakete (1316 Byte) passen in ein UDP-Datagramm. */
        const val DATAGRAM_SIZE = 7 * PACKET_SIZE

        const val PMT_PID = 0x1000
        const val VIDEO_PID = 0x0100
        private const val HEVC_STREAM_TYPE = 0x24

        /** Zeitstempel beginnen bei 1 s, damit die PCR davor nie negativ wird. */
        private const val PTS_OFFSET = 90_000L
        private const val PCR_LEAD = 9_000L

        private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

        private fun stuffing(count: Int) = ByteArray(count) { 0xFF.toByte() }

        // Tabellen und Kopfdaten

        fun patSection() = section(
            0x00,
            bytes(
                0x00, 0x01,  // program_number
                0xE0 or (PMT_PID shr 8), PMT_PID and 0xFF,
            ),
        )

        fun pmtSection() = section(
            0x02,
            bytes(
                0xE0 or (VIDEO_PID shr 8), VIDEO_PID and 0xFF,  // PCR_PID
                0xF0, 0x00,  // program_info_length
                HEVC_STREAM_TYPE,
                0xE0 or (VIDEO_PID shr 8), VIDEO_PID and 0xFF,
                0xF0, 0x00,  // ES_info_length
            ),
        )

        /** Gemeinsamer Rahmen von PAT und PMT: Kopf, Programmnummer 1, CRC. */
        private fun section(tableId: Int, body: ByteArray): ByteArray {
            val length = 5 + body.size + 4
            val head = bytes(
                tableId,
                0xB0 or (length shr 8), length and 0xFF,
                0x00, 0x01,  // transport_stream_id bzw. program_number
                0xC1,  // version 0, current_next
                0x00, 0x00,  // section_number, last_section_number
            ) + body
            val crc = crc32(head)
            return head + bytes(crc ushr 24, crc shr 16 and 0xFF, crc shr 8 and 0xFF, crc and 0xFF)
        }

        private fun pesHeader(pts: Long) = bytes(
            0x00, 0x00, 0x01, 0xE0,
            0x00, 0x00,  // Länge 0: bei Video unbegrenzt
            0x84,  // data_alignment_indicator
            0x80,  // nur PTS
            0x05,
            0x21 or (pts shr 29 and 0x0E).toInt(),
            (pts shr 22 and 0xFF).toInt(),
            0x01 or (pts shr 14 and 0xFE).toInt(),
            (pts shr 7 and 0xFF).toInt(),
            0x01 or (pts shl 1 and 0xFE).toInt(),
        )

        private fun pcrField(pcr: Long, randomAccess: Boolean) = bytes(
            0x10 or (if (randomAccess) 0x40 else 0x00),
            (pcr shr 25 and 0xFF).toInt(),
            (pcr shr 17 and 0xFF).toInt(),
            (pcr shr 9 and 0xFF).toInt(),
            (pcr shr 1 and 0xFF).toInt(),
            (pcr shl 7 and 0x80).toInt() or 0x7E,
            0x00,
        )

        /** CRC-32/MPEG-2, wie sie PAT und PMT abschließt. */
        fun crc32(data: ByteArray): Int {
            var crc = -1
            for (byte in data) {
                crc = crc xor (byte.toInt() and 0xFF shl 24)
                repeat(8) {
                    crc = if (crc < 0) (crc shl 1) xor 0x04C1_1DB7 else crc shl 1
                }
            }
            return crc
        }
    }
}
