package io.github.willheisenberg.kodiscreencast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KodiClientTest {
    @Test
    fun pluginUrlCarriesTheAudioSettings() {
        assertEquals(
            "plugin://plugin.video.screencast/?port=5004&audio_port=5005&audio_delay=350",
            KodiClient.pluginUrl(5004, audioPort = 5005, audioDelayMs = 350),
        )
        assertEquals("plugin://plugin.video.screencast/?port=5004", KodiClient.pluginUrl(5004))
    }

    @Test
    fun ownStreamIsRecognisedInBothForms() {
        assertTrue(KodiClient.isOwnStream("udp://@:5004?fifo_size=50000", 5004))
        assertTrue(KodiClient.isOwnStream(KodiClient.pluginUrl(5004, audioPort = 5005), 5004))
        assertFalse(KodiClient.isOwnStream("udp://@:5005?x=1", 5004))
        assertFalse(KodiClient.isOwnStream("/storage/film.mkv", 5004))
    }

    @Test
    fun titleFallsBackToLabelThenFileName() {
        assertEquals("Send Help", KodiClient.title("Send Help", "x", ""))
        assertEquals("Send Help", KodiClient.title("", "Send Help", ""))
        assertEquals("clip.mkv", KodiClient.title("", "", "/media/clip.mkv"))
    }
}
