package io.github.willheisenberg.kodiscreencast

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL
import java.util.Base64

class KodiException(message: String) : Exception(message)

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

    fun play(port: Int, audioPort: Int?, audioDelayMs: Int) {
        val file = pluginUrl(port, audioPort, audioDelayMs)
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
    private fun ownPlayer(port: Int): Int? =
        activePlayers().firstOrNull { isOwnStream(item(it, "file")?.optString("file") ?: "", port) }

    /** Titel dessen, was Kodi gerade außer dem eigenen Stream spielt, sonst null. */
    fun otherPlayback(port: Int): String? {
        for (player in activePlayers()) {
            val item = item(player, "file", "title") ?: continue
            val file = item.optString("file")
            if (!isOwnStream(file, port)) {
                return title(item.optString("title"), item.optString("label"), file)
            }
        }
        return null
    }

    /** Sekunden, die der eigene Stream schon spielt; null, solange er nicht läuft. */
    fun playbackTime(port: Int): Double? {
        val player = ownPlayer(port) ?: return null
        val params = JSONObject().put("playerid", player).put("properties", JSONArray().put("time"))
        val time = (call("Player.GetProperties", params) as? JSONObject)?.optJSONObject("time")
            ?: return null
        return time.optDouble("hours", 0.0) * 3600 + time.optDouble("minutes", 0.0) * 60 +
            time.optDouble("seconds", 0.0) + time.optDouble("milliseconds", 0.0) / 1000
    }

    /** Stoppt die Wiedergabe nur, wenn noch der eigene Stream läuft. */
    fun stop(port: Int) {
        ownPlayer(port)?.let { call("Player.Stop", JSONObject().put("playerid", it)) }
    }

    companion object {
        const val ADDON_ID = "plugin.video.screencast"
        private const val TIMEOUT_MS = 5000

        fun pluginUrl(port: Int, audioPort: Int? = null, audioDelayMs: Int = 0): String {
            var url = "plugin://$ADDON_ID/?port=$port"
            if (audioPort != null) {
                url += "&audio_port=$audioPort&audio_delay=$audioDelayMs"
            }
            return url
        }

        fun isOwnStream(file: String, port: Int) =
            file.startsWith("udp://@:$port?") || file.startsWith(pluginUrl(port))

        /** Titel, sonst Beschriftung, sonst der Dateiname. */
        fun title(title: String, label: String, file: String): String =
            listOf(title, label).firstOrNull { it.isNotEmpty() } ?: file.substringAfterLast('/')
    }
}
