package io.github.willheisenberg.kodiscreencast

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class TsMuxerTest {
    @Test
    fun crcMatchesTheMpeg2CheckValue() {
        assertEquals(0x0376_E6E7, TsMuxer.crc32("123456789".toByteArray()))
    }

    @Test
    fun outputIsWholeSyncedPackets() {
        val muxer = TsMuxer()
        for (size in listOf(1, 150, 169, 170, 171, 183, 184, 185, 5000)) {
            val stream = muxer.mux(ByteArray(size) { 0xAB.toByte() }, 1.0, keyframe = false)
            assertEquals("Größe $size", 0, stream.size % TsMuxer.PACKET_SIZE)
            for (start in stream.indices step TsMuxer.PACKET_SIZE) {
                assertEquals(0x47, stream[start].toInt())
            }
        }
    }

    @Test
    fun keyframesAreAnnouncedByPatAndPmt() {
        val stream = TsMuxer().mux(byteArrayOf(1, 2, 3), 0.0, keyframe = true)
        assertEquals(3 * TsMuxer.PACKET_SIZE, stream.size)
        assertEquals(0, pid(stream, 0))
        assertEquals(TsMuxer.PMT_PID, pid(stream, 1))
        assertEquals(TsMuxer.VIDEO_PID, pid(stream, 2))
        // Keyframe: Adaptation Field mit PCR und Random-Access-Kennung.
        assertEquals(0x50, stream[2 * 188 + 5].toInt() and 0x50)
    }

    @Test
    fun payloadSurvivesPacketisation() {
        val accessUnit = ByteArray(1000) { (it % 251).toByte() }
        val stream = TsMuxer().mux(accessUnit, 2.5, keyframe = false)
        var payload = ByteArray(0)
        for (start in stream.indices step 188) {
            var offset = start + 4
            if (stream[start + 3].toInt() and 0x20 != 0) {
                offset += 1 + (stream[start + 4].toInt() and 0xFF)
            }
            payload += stream.copyOfRange(offset, start + 188)
        }
        assertArrayEquals(byteArrayOf(0, 0, 1, 0xE0.toByte()), payload.copyOf(4))
        assertArrayEquals(accessUnit, payload.copyOfRange(14, payload.size))
    }

    @Test
    fun timestampsStartOneSecondIn() {
        val stream = TsMuxer().mux(byteArrayOf(1), 0.0, keyframe = false)
        // Paketkopf (4), Adaptation Field (1 + Länge), dann der PES-Kopf mit der PTS ab Byte 9.
        val pes = 4 + 1 + (stream[4].toInt() and 0xFF)
        val b = stream.copyOfRange(pes + 9, pes + 14).map { it.toLong() and 0xFF }
        val pts = (b[0] and 0x0E shl 29) or (b[1] shl 22) or (b[2] and 0xFE shl 14) or
            (b[3] shl 7) or (b[4] shr 1)
        assertEquals(90_000L, pts)
    }

    @Test
    fun continuityCounterCountsPerPid() {
        val stream = TsMuxer().mux(ByteArray(600), 0.0, keyframe = false)
        val counters = (stream.indices step 188).map { stream[it + 3].toInt() and 0x0F }
        assertEquals(listOf(0, 1, 2, 3), counters)
    }

    private fun pid(stream: ByteArray, packet: Int) =
        (stream[packet * 188 + 1].toInt() and 0x1F shl 8) or (stream[packet * 188 + 2].toInt() and 0xFF)
}
