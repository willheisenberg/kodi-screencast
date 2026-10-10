package io.github.willheisenberg.kodiscreencast

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.MalformedURLException
import java.net.URL
import java.util.Base64

class KodiException(message: String) : Exception(message)

/** Was ein Start der Übertragung auf Kodi unterbräche. */
sealed interface Interruption {
    data class Playback(val title: String) : Interruption

    /** Die Übertragung eines anderen Geräts. */
    data object OtherCast : Interruption
}

/**
 * JSON-RPC-Client für Kodis Fernsteuer-Schnittstelle (HTTP).
 *
 * Die Aufrufe blockieren und gehören deshalb nicht in den Hauptthread.
 */
class KodiClient(private val host: String, user: String = "", password: String = "", port: Int = 8080) {
    private val url: URL = try {
        URL("http://$host:$port/jsonrpc")
    } catch (e: MalformedURLException) {
        throw KodiException("$host ist keine gültige Adresse.")
    }
    private val authorization =
        if (user.isEmpty()) null
        else "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray())

    fun call(method: String, params: JSONObject? = null): Any? {
        val body = JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", method)
        if (params != null) {
            body.put("params", params)
        }
        val (status, text) = try {
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                authorization?.let { connection.setRequestProperty("Authorization", it) }
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
                val status = connection.responseCode
                val text =
                    if (status == 200) connection.inputStream.bufferedReader().use { it.readText() }
                    else ""
                status to text
            } finally {
                connection.disconnect()
            }
        } catch (e: IOException) {
            throw KodiException(
                "Kodi ist unter $host nicht erreichbar. In Kodi muss unter " +
                    "Einstellungen > Dienste > Steuerung die Fernsteuerung über HTTP erlaubt sein."
            )
        }
        if (status == 401) {
            throw KodiException("Kodi verlangt Benutzername und Passwort (siehe Einstellungen).")
        }
        if (status != 200) {
            throw KodiException("Kodi antwortet mit HTTP $status.")
        }
        val answer = try {
            JSONObject(text)
        } catch (e: JSONException) {
            throw KodiException("Kodi hat unverständlich geantwortet.")
        }
        answer.optJSONObject("error")?.let {
            throw KodiException("$method: ${it.optString("message", "Fehler")}")
        }
        return answer.opt("result")
    }

    /** Prüft Verbindung, Anmeldung und das Addon; wirft mit einer Meldung für den Nutzer. */
    fun requireAddon() {
        // Erst ein echter Aufruf, damit Verbindungs- und Anmeldefehler sichtbar werden.
        call("JSONRPC.Ping")
        if (!hasAddon()) {
            throw KodiException("Das Addon $ADDON_ID ist in Kodi nicht installiert oder deaktiviert.")
        }
    }

    private fun hasAddon(): Boolean {
        val params = JSONObject().put("addonid", ADDON_ID).put("properties", JSONArray().put("enabled"))
        val details = try {
            call("Addons.GetAddonDetails", params) as? JSONObject
        } catch (e: KodiException) {
            null
        }
        return details?.optJSONObject("addon")?.optBoolean("enabled", false) ?: false
    }

    /** Die eigene IP-Adresse, wie Kodi sie als Absender der Pakete sieht. */
    fun localAddress(): String? = try {
        DatagramSocket().use {
            it.connect(InetAddress.getByName(host), 9)  // legt nur den Weg fest, sendet nichts
            it.localAddress.hostAddress
        }
    } catch (e: IOException) {
        null
    }

    fun play(port: Int, audioPort: Int?, audioDelayMs: Int, source: String?) {
        val file = pluginUrl(port, audioPort, audioDelayMs, source)
        call("Player.Open", JSONObject().put("item", JSONObject().put("file", file)))
    }

    private fun activePlayers(): List<Int> {
        val players = call("Player.GetActivePlayers") as? JSONArray ?: return emptyList()
        return (0 until players.length()).mapNotNull { index ->
            players.optJSONObject(index)?.takeIf { it.has("playerid") }?.optInt("playerid")
        }
    }

    private fun item(player: Int, vararg properties: String): JSONObject? {
        val params = JSONObject().put("playerid", player).put("properties", JSONArray(properties))
        return (call("Player.GetItem", params) as? JSONObject)?.optJSONObject("item")
    }

    /** Kennung des Players, der gerade den eigenen Stream spielt. */
    private fun ownPlayer(port: Int, source: String?): Int? = activePlayers().firstOrNull {
        isOwnStream(item(it, "file")?.optString("file") ?: "", port, source)
    }

    /** Was ein Start unterbräche; null, wenn auf Kodi nichts Fremdes läuft. */
    fun otherPlayback(port: Int, source: String?): Interruption? {
        for (player in activePlayers()) {
            val item = item(player, "file", "title") ?: continue
            val file = item.optString("file")
            if (!isScreencast(file, port)) {
                return Interruption.Playback(
                    title(item.optString("title"), item.optString("label"), file))
            }
            // Vor dem Start ist nur ein Strom mit der eigenen Adresse der eigene
            // (ein Rest eines früheren Laufs); einer ohne Absender stammt von
            // einem älteren Sender.
            if (source == null || streamSource(file) != source) {
                return Interruption.OtherCast
            }
        }
        return null
    }

    /** Sekunden, die der eigene Stream schon spielt; null, solange er nicht läuft. */
    fun playbackTime(port: Int, source: String?): Double? {
        val player = ownPlayer(port, source) ?: return null
        val params = JSONObject().put("playerid", player).put("properties", JSONArray().put("time"))
        val time = (call("Player.GetProperties", params) as? JSONObject)?.optJSONObject("time")
            ?: return null
        return time.optDouble("hours", 0.0) * 3600 + time.optDouble("minutes", 0.0) * 60 +
            time.optDouble("seconds", 0.0) + time.optDouble("milliseconds", 0.0) / 1000
    }

    /** Stoppt die Wiedergabe nur, wenn noch der eigene Stream läuft. */
    fun stop(port: Int, source: String?) {
        ownPlayer(port, source)?.let { call("Player.Stop", JSONObject().put("playerid", it)) }
    }

    companion object {
        const val ADDON_ID = "plugin.video.screencast"
        private const val TIMEOUT_MS = 5000

        fun pluginUrl(
            port: Int, audioPort: Int? = null, audioDelayMs: Int = 0, source: String? = null,
        ): String {
            var url = "plugin://$ADDON_ID/?port=$port"
            if (source != null) {
                // Mit der Adresse nimmt das Addon nur die Pakete dieses Geräts an.
                url += "&source=$source"
            }
            if (audioPort != null) {
                url += "&audio_port=$audioPort&audio_delay=$audioDelayMs"
            }
            return url
        }

        /** Kodi spielt einen Screencast auf diesem Port, von welchem Gerät auch immer. */
        fun isScreencast(file: String, port: Int) =
            file.startsWith("udp://@:$port/?") || file.startsWith("udp://@:$port?") ||
                file.startsWith(pluginUrl(port))

        /** Absenderadresse, auf die das Addon den Strom beschränkt hat, sonst null. */
        fun streamSource(file: String): String? {
            val params = file.substringAfter('?', "").split('&')
                .associate { it.substringBefore('=') to it.substringAfter('=', "") }
            // "sources" heißt die Angabe in der Stream-Adresse, "source" in der Plugin-Adresse.
            return (params["sources"] ?: params["source"])?.takeIf { it.isNotEmpty() }
        }

        /**
         * Der Strom einer laufenden eigenen Übertragung. Ein Addon ohne
         * Absenderfilter nennt keinen Absender; dann gilt jeder Screencast
         * auf dem Port als der eigene.
         */
        fun isOwnStream(file: String, port: Int, source: String?): Boolean {
            val named = streamSource(file)
            return isScreencast(file, port) && (named == null || named == source)
        }

        /** Titel, sonst Beschriftung, sonst der Dateiname. */
        fun title(title: String, label: String, file: String): String =
            listOf(title, label).firstOrNull { it.isNotEmpty() } ?: file.substringAfterLast('/')
    }
}
