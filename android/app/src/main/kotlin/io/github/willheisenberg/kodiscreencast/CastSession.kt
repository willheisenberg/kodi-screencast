package io.github.willheisenberg.kodiscreencast

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.SystemClock
import kotlin.concurrent.thread

/**
 * Eine laufende Übertragung: Aufnahme, Kodierung, Versand und Kodi-Steuerung.
 *
 * start() und stop() blockieren (Netzwerk) und laufen im Arbeitsthread des Dienstes.
 */
class CastSession(
    private val context: Context,
    private val settings: Settings,
    private val projection: MediaProjection,
    private val onFailure: (String) -> Unit,
    private val onDisplaced: () -> Unit,
) {
    private var kodi: KodiClient? = null
    private var sender: UdpSender? = null
    private var encoder: VideoEncoder? = null
    private var relay: FrameRelay? = null
    private var display: VirtualDisplay? = null
    private var audio: AudioCapture? = null
    private var companion: Thread? = null
    private var source: String? = null

    // Gehören dem Thread des Encoders.
    private val muxer = TsMuxer()
    private var firstPts: Double? = null

    @Volatile
    private var gateClosed = false

    fun start() {
        val kodi = KodiClient(settings.host, settings.user, settings.password)
        this.kodi = kodi
        val source = kodi.localAddress()
        this.source = source

        val sender = UdpSender(settings.host, VIDEO_PORT)
        this.sender = sender
        val encoder = VideoEncoder(
            WIDTH, HEIGHT, FPS, BITRATE_KBPS,
            onFrame = { accessUnit, pts, keyframe ->
                // Die Systemuhr zählt seit dem Einschalten; der Strom beginnt bei null.
                val start = firstPts ?: pts.also { firstPts = it }
                val packets = muxer.mux(accessUnit, pts - start, keyframe)
                if (!gateClosed) {
                    sender.send(packets, TsMuxer.DATAGRAM_SIZE)
                }
            },
            onFailure = onFailure,
        )
        this.encoder = encoder
        val relay = FrameRelay(encoder.surface, WIDTH, HEIGHT, FPS)
        this.relay = relay
        // Das Bild hat immer das Format des Fernsehers. Android passt den
        // Bildschirm darin ein, im Hochformat mit Balken links und rechts;
        // so bleibt der Strom beim Drehen des Geräts derselbe.
        display = projection.createVirtualDisplay(
            "kodi-screencast", WIDTH, HEIGHT, context.resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, relay.surface, null, null)

        val withAudio = settings.audio &&
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (withAudio) {
            audio = AudioCapture(projection, UdpSender(settings.host, AUDIO_PORT))
        }

        kodi.play(VIDEO_PORT, if (withAudio) AUDIO_PORT else null, settings.audioDelayMs, source)

        companion = thread(name = "screencast.companion") {
            try {
                catchUp(kodi, encoder, source)
                watch(kodi, source)
            } catch (e: InterruptedException) {
                // Die Übertragung wurde inzwischen beendet.
            }
        }
    }

    private fun catchUp(kodi: KodiClient, encoder: VideoEncoder, source: String?) {
        val deadline = SystemClock.elapsedRealtime() + CATCH_UP_GIVE_UP_MS
        while (true) {
            val played = try {
                kodi.playbackTime(VIDEO_PORT, source)
            } catch (e: KodiException) {
                null
            }
            if (played != null && played >= CATCH_UP_AFTER) break
            if (SystemClock.elapsedRealtime() > deadline) return
            Thread.sleep(250)
        }
        gateClosed = true
        try {
            Thread.sleep(CATCH_UP_PAUSE_MS)
        } finally {
            // Nach dem Öffnen setzt der Strom mit einem Keyframe wieder ein.
            gateClosed = false
            encoder.requestKeyframe()
        }
    }

    /**
     * Meldet, sobald Kodi die eigene Übertragung nicht mehr spielt: Jemand hat
     * an Kodi gestoppt, oder ein anderes Gerät hat übernommen. Weiterzusenden
     * hätte dann keinen Empfänger mehr. Läuft nach catchUp: Bis dahin hatte
     * Kodi Zeit, die Übertragung zu starten. Fehlt sie von Anfang an, hat ein
     * anderes Gerät gleich zu Beginn übernommen.
     */
    private fun watch(kodi: KodiClient, source: String?) {
        var misses = 0
        while (true) {
            Thread.sleep(WATCH_INTERVAL_MS)
            val playing = try {
                kodi.playbackTime(VIDEO_PORT, source) != null
            } catch (e: KodiException) {
                continue
            }
            if (playing) {
                misses = 0
            } else if (++misses >= WATCH_MISSES) {
                onDisplaced()
                return
            }
        }
    }

    fun stop() {
        companion?.interrupt()
        companion?.join(1000)
        companion = null
        audio?.stop()
        audio = null
        display?.release()
        display = null
        relay?.release()
        relay = null
        encoder?.release()
        encoder = null
        sender?.close()
        sender = null
        projection.stop()
        try {
            kodi?.stop(VIDEO_PORT, source)
        } catch (e: KodiException) {
            // Kodi ist nicht mehr erreichbar; dann läuft dort auch nichts mehr.
        }
        kodi = null
    }

    companion object {
        const val VIDEO_PORT = 5004
        private const val AUDIO_PORT = 5005
        private const val FPS = 30
        private const val WIDTH = 1920
        private const val HEIGHT = 1080
        private const val BITRATE_KBPS = 8000

        // Kodi spielt alles ab, was während seines Starts ankommt, und läuft
        // deshalb dauerhaft um diese Startzeit (gut 1 s) hinterher. Eine
        // Sendepause, sobald die Wiedergabe läuft, lässt Kodis Puffer leerlaufen;
        // den Zeitsprung danach rechnet Kodi heraus.
        private const val CATCH_UP_PAUSE_MS = 2000L
        private const val CATCH_UP_AFTER = 1.0
        private const val CATCH_UP_GIVE_UP_MS = 20_000L

        // Erst nach zwei Blicken ohne den eigenen Strom aufhören: Das Addon
        // erkennt einen Stopp an Kodi daran, dass der Sender kurz danach noch sendet.
        private const val WATCH_INTERVAL_MS = 2000L
        private const val WATCH_MISSES = 2
    }
}
