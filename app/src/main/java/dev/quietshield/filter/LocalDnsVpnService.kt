package dev.quietshield.filter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import dev.quietshield.QuietPrefs
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class LocalDnsVpnService : VpnService() {
    private var tunnel: ParcelFileDescriptor? = null
    private val running = AtomicBoolean(false)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopTunnel(); stopSelf(); return START_NOT_STICKY }
        createChannel()
        startForeground(NOTIFICATION_ID, notification())
        if (!running.get()) startTunnel()
        return START_STICKY
    }

    private fun startTunnel() {
        val packages = QuietPrefs.packages(this, "network_apps")
        if (packages.isEmpty()) { QuietPrefs.setBool(this, "network_running", false); stopSelf(); return }
        try {
            val builder = Builder().setSession("QuietShield DNS").setMtu(1500)
                .addAddress("10.31.0.2", 32).addDnsServer("10.31.0.2").addRoute("10.31.0.2", 32)
            packages.forEach { builder.addAllowedApplication(it) }
            tunnel = builder.establish() ?: throw IllegalStateException("系统没有建立 VPN")
            running.set(true)
            QuietPrefs.setBool(this, "network_running", true)
            QuietPrefs.setLong(this, "network_started", System.currentTimeMillis())
            val fd = tunnel!!.fileDescriptor
            thread(name = "quietshield-dns-tun") {
                val input = FileInputStream(fd)
                val output = FileOutputStream(fd)
                val packet = ByteArray(32767)
                while (running.get()) {
                    val size = runCatching { input.read(packet) }.getOrDefault(-1)
                    if (size <= 0) break
                    val query = DnsPackets.parse(packet, size) ?: continue
                    val blocked = QuietPrefs.domains(this).any { rule ->
                        rule.enabled && (query.host == rule.host.lowercase() || query.host.endsWith("." + rule.host.lowercase()))
                    }
                    val bytes = if (blocked) {
                        QuietPrefs.addBlocked(this)
                        DnsPackets.blockedReply(query)
                    } else resolve(query)
                    runCatching { output.write(bytes) }
                }
                stopTunnel()
            }
        } catch (e: Exception) {
            QuietPrefs.setBool(this, "network_running", false)
            stopSelf()
        }
    }

    private fun resolve(q: DnsPackets.Query): ByteArray {
        return try {
            val dnsMessage = q.packet.copyOfRange(q.dnsOffset, q.dnsOffset + q.dnsLength)
            val response = DnsUpstream.query(this, dnsMessage) { socket -> protect(socket) }
            DnsPackets.response(q, response)
        } catch (_: Exception) {
            DnsPackets.errorReply(q)
        }
    }
    private fun stopTunnel() {
        if (!running.getAndSet(false) && tunnel == null) return
        runCatching { tunnel?.close() }
        tunnel = null
        QuietPrefs.setBool(this, "network_running", false)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onRevoke() { stopTunnel(); stopSelf() }
    override fun onDestroy() { stopTunnel(); super.onDestroy() }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "网络过滤", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }
    private fun notification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        return builder.setSmallIcon(android.R.drawable.ic_lock_lock).setContentTitle("QuietShield 正在过滤 DNS")
            .setContentText("仅在勾选的应用中按本地规则过滤 DNS 请求").setOngoing(true).build()
    }

    companion object {
        const val ACTION_STOP = "dev.quietshield.STOP_VPN"
        private const val CHANNEL = "quietshield_filter"
        private const val NOTIFICATION_ID = 21
    }
}









