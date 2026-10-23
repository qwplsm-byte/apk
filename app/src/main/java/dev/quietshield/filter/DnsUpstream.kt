package dev.quietshield.filter

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

object DnsUpstream {
    private const val TIMEOUT_MS = 1_000
    private const val MAX_SYSTEM_RESOLVERS = 2

    fun query(
        context: Context,
        message: ByteArray,
        protectSocket: ((DatagramSocket) -> Boolean)? = null
    ): ByteArray {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val systemResolvers = manager.allNetworks.asSequence()
            .filter { network ->
                val capabilities = manager.getNetworkCapabilities(network)
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != true
            }
            .flatMap { network -> manager.getLinkProperties(network)?.dnsServers.orEmpty().asSequence() }
            .filterNot(InetAddress::isLoopbackAddress)
            .distinct()
            .take(MAX_SYSTEM_RESOLVERS)
            .toList()

        val resolvers = if (systemResolvers.isNotEmpty()) {
            systemResolvers
        } else {
            listOf(InetAddress.getByName("223.5.5.5"), InetAddress.getByName("1.1.1.1"))
        }

        var lastFailure: Exception? = null
        for (resolver in resolvers) {
            try {
                DatagramSocket().use { socket ->
                    if (protectSocket != null && !protectSocket(socket)) {
                        throw IllegalStateException("无法保护上游 DNS 套接字")
                    }
                    socket.soTimeout = TIMEOUT_MS
                    socket.connect(resolver, DNS_PORT)
                    socket.send(DatagramPacket(message, message.size))
                    val response = DatagramPacket(ByteArray(MAX_RESPONSE_SIZE), MAX_RESPONSE_SIZE)
                    socket.receive(response)
                    return response.data.copyOf(response.length)
                }
            } catch (failure: Exception) {
                lastFailure = failure
            }
        }
        throw lastFailure ?: IllegalStateException("没有可用的 DNS 服务器")
    }

    private const val DNS_PORT = 53
    private const val MAX_RESPONSE_SIZE = 4_096
}
