package dev.quietshield.filter

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

object DnsUpstream {
    fun query(context: Context, message: ByteArray, protectSocket: ((DatagramSocket) -> Boolean)? = null): ByteArray {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val servers = manager.allNetworks.asSequence()
            .filter { network -> manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != true }
            .flatMap { network -> manager.getLinkProperties(network)?.dnsServers.orEmpty().asSequence() }
            .distinct()
            .toList() + InetAddress.getByName("1.1.1.1")
        var failure: Exception? = null
        for (server in servers.distinct()) {
            try {
                DatagramSocket().use { socket ->
                    if (protectSocket != null && !protectSocket(socket)) throw IllegalStateException("Cannot protect DNS socket")
                    socket.soTimeout = 2_500
                    socket.send(DatagramPacket(message, message.size, server, 53))
                    val bytes = ByteArray(4096)
                    val response = DatagramPacket(bytes, bytes.size)
                    socket.receive(response)
                    return bytes.copyOf(response.length)
                }
            } catch (e: Exception) { failure = e }
        }
        throw failure ?: IllegalStateException("No DNS resolver available")
    }
}
