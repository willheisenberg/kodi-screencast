package io.github.willheisenberg.kodiscreencast

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import kotlin.concurrent.thread

/**
 * Schneidet den Systemton mit und schickt ihn so, wie ihn der Ton-Empfänger
 * auf dem Pi erwartet: 48 kHz, Stereo, 16 Bit Little Endian.
 *
 * Android gibt nur den Ton von Apps heraus, die das Mitschneiden erlauben.
 * Der Aufrufer muss die Berechtigung RECORD_AUDIO schon haben.
 */
@SuppressLint("MissingPermission")
class AudioCapture(projection: MediaProjection, private val sender: UdpSender) {
    private val record: AudioRecord

    @Volatile
    private var running = true
    private val reader: Thread

    init {
        val capture = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        try {
            record = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(8 * CHUNK_SIZE)
                .setAudioPlaybackCaptureConfig(capture)
                .build()
            record.startRecording()
        } catch (e: Exception) {
            throw KodiException("Der Systemton lässt sich nicht mitschneiden (${e.message}).")
        }
        reader = thread(name = "screencast.audio") {
            val chunk = ByteArray(CHUNK_SIZE)
            while (running) {
                val read = record.read(chunk, 0, chunk.size)
                if (read <= 0) break
                sender.send(chunk, DATAGRAM_SIZE, read)
            }
        }
    }

    fun stop() {
        running = false
        try {
            record.stop()
        } catch (e: IllegalStateException) {
            // Lief nicht mehr.
        }
        reader.join(1000)
        record.release()
        sender.close()
    }

    private companion object {
        const val SAMPLE_RATE = 48_000

        /** Größte Nutzlast pro Datagramm; ein Vielfaches von vier Byte (ein Stereo-Frame). */
        const val DATAGRAM_SIZE = 1200

        /** 10 ms Ton pro Lesevorgang. */
        const val CHUNK_SIZE = SAMPLE_RATE / 100 * 4
    }
}
