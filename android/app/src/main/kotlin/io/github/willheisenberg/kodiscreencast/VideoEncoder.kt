package io.github.willheisenberg.kodiscreencast

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface

/**
 * HEVC-Encoder über MediaCodec, eingestellt auf geringe Verzögerung:
 * keine B-Frames und ein Keyframe pro Sekunde, damit Kodi nach
 * Paketverlust schnell wieder ein vollständiges Bild findet.
 *
 * @param onFrame fertiges Bild im Annex-B-Format, Zeitstempel in Sekunden, Keyframe ja/nein
 */
class VideoEncoder(
    width: Int, height: Int, fps: Int, bitrateKbps: Int,
    private val onFrame: (ByteArray, Double, Boolean) -> Unit,
    private val onFailure: (String) -> Unit,
) {
    private val thread = HandlerThread("screencast.encoder").apply { start() }
    private val codec: MediaCodec

    /** Was auf diese Fläche gezeichnet wird, kodiert der Encoder. */
    val surface: Surface

    // VPS, SPS und PPS; der Encoder liefert sie einmal zu Beginn.
    private var parameterSets = ByteArray(0)

    init {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateKbps * 1000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        }
        try {
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC)
            codec.setCallback(Callback(), Handler(thread.looper))
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = codec.createInputSurface()
            codec.start()
        } catch (e: Exception) {
            thread.quitSafely()
            throw KodiException("HEVC-Encoder lässt sich nicht öffnen (${e.message}).")
        }
    }

    /** Das nächste Bild wird ein Keyframe. */
    fun requestKeyframe() {
        try {
            codec.setParameters(
                Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        } catch (e: IllegalStateException) {
            // Der Encoder ist schon beendet.
        }
    }

    fun release() {
        try {
            codec.stop()
        } catch (e: IllegalStateException) {
            // Nach einem Encoder-Fehler lässt er sich nur noch freigeben.
        }
        codec.release()
        surface.release()
        thread.quitSafely()
    }

    private inner class Callback : MediaCodec.Callback() {
        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            val data = ByteArray(info.size)
            try {
                val buffer = codec.getOutputBuffer(index) ?: return
                buffer.position(info.offset)
                buffer.get(data)
                codec.releaseOutputBuffer(index, false)
            } catch (e: IllegalStateException) {
                return
            }
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                parameterSets = data
                return
            }
            if (data.isEmpty()) return
            val keyframe = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
            // MediaCodec liefert schon Annex B; es fehlen der Delimiter und
            // vor Keyframes die Parametersätze, mit denen Kodi einsteigen kann.
            val accessUnit =
                if (keyframe) HEVC_DELIMITER + parameterSets + data else HEVC_DELIMITER + data
            onFrame(accessUnit, info.presentationTimeUs / 1e6, keyframe)
        }

        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {}

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            onFailure("Encoder-Fehler: ${e.diagnosticInfo}")
        }
    }

    private companion object {
        /** Access Unit Delimiter für HEVC; markiert den Beginn jedes Bildes. */
        val HEVC_DELIMITER = byteArrayOf(0, 0, 0, 1, 0x46, 0x01, 0x50)
    }
}
