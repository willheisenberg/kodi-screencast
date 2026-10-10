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
        assertEquals(
            "plugin://plugin.video.screencast/?port=5004&source=192.168.1.5&audio_port=5005&audio_delay=350",
            KodiClient.pluginUrl(5004, audioPort = 5005, audioDelayMs = 350, source = "192.168.1.5"),
        )
    }

    @Test
    fun ownStreamIsRecognisedInBothForms() {
        assertTrue(KodiClient.isOwnStream("udp://@:5004?fifo_size=50000", 5004, null))
        assertTrue(KodiClient.isOwnStream("udp://@:5004/?fifo_size=50000", 5004, null))
        assertTrue(KodiClient.isOwnStream(KodiClient.pluginUrl(5004, audioPort = 5005), 5004, null))
        assertFalse(KodiClient.isOwnStream("udp://@:5005?x=1", 5004, null))
        assertFalse(KodiClient.isOwnStream("/storage/film.mkv", 5004, null))
    }

    @Test
    fun castOfAnotherDeviceIsNotTheOwnStream() {
        val theirs = "udp://@:5004?overrun_nonfatal=1&fifo_size=50000&sources=192.168.1.5&audio_port=5005"
        assertEquals("192.168.1.5", KodiClient.streamSource(theirs))
        assertTrue(KodiClient.isOwnStream(theirs, 5004, "192.168.1.5"))
        assertFalse(KodiClient.isOwnStream(theirs, 5004, "192.168.1.6"))
        assertFalse(
            KodiClient.isOwnStream(KodiClient.pluginUrl(5004, source = "192.168.1.5"), 5004, "192.168.1.6"))
        // Ein Addon ohne Absenderfilter nennt keinen Absender.
        assertTrue(
            KodiClient.isOwnStream("udp://@:5004?overrun_nonfatal=1&fifo_size=50000", 5004, "192.168.1.6"))
    }

    @Test
    fun titleFallsBackToLabelThenFileName() {
        assertEquals("Send Help", KodiClient.title("Send Help", "x", ""))
        assertEquals("Send Help", KodiClient.title("", "Send Help", ""))
        assertEquals("clip.mkv", KodiClient.title("", "", "/media/clip.mkv"))
    }
}
