package io.github.willheisenberg.kodiscreencast

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/** Schickt Datagramme an eine feste Adresse. */
class UdpSender(host: String, private val port: Int) {
    private val address = InetAddress.getByName(host)
    private val socket = DatagramSocket()

    /** Teilt die Daten in Datagramme der angegebenen Größe und verschickt sie. */
    fun send(data: ByteArray, datagramSize: Int, length: Int = data.size) {
        var offset = 0
        while (offset < length) {
            val size = minOf(datagramSize, length - offset)
            try {
                socket.send(DatagramPacket(data, offset, size, address, port))
            } catch (e: IOException) {
                // Wie bei jedem UDP-Verlust: Das nächste Keyframe fängt es auf.
            }
            offset += size
        }
    }

    fun close() {
        socket.close()
    }
}
