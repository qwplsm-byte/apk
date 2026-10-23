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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class LocalDnsVpnService : VpnService() {
    private var tunnel: ParcelFileDescriptor? = null
    private val running = AtomicBoolean(false)
    private val starting = AtomicBoolean(false)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTunnel()
            stopSelf()
            return START_NOT_STICKY
        }
        createChannel()
        startForeground(NOTIFICATION_ID, notification())
        if (starting.compareAndSet(false, true)) {
            thread(name = "quietshield-dns-tun", isDaemon = true) {
                try {
                    startTunnel()
                } finally {
                    starting.set(false)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startTunnel() {
        try {
            val packages = QuietPrefs.packages(this, "network_apps")
            require(packages.isNotEmpty()) { "未选择网络保护应用" }

            val builder = Builder()
                .setSession("QuietShield DNS")
                .setMtu(1500)
                .addAddress("10.31.0.2", 32)
                .addDnsServer("10.31.0.2")
                .addRoute("10.31.0.2", 32)

            packages.forEach(builder::addAllowedApplication)
            tunnel = builder.establish() ?: error("系统拒绝建立 VPN")
            running.set(true)
            QuietPrefs.setBool(this, "network_running", true)
            QuietPrefs.setString(this, "network_error", "")
            QuietPrefs.setLong(this, "network_started", System.currentTimeMillis())

            val descriptor = requireNotNull(tunnel).fileDescriptor
            FileInputStream(descriptor).use { input ->
                FileOutputStream(descriptor).use { output ->
                    val packet = ByteArray(MAX_PACKET_SIZE)
                    while (running.get()) {
                        val size = input.read(packet)
                        if (size <= 0) break
                        val query = DnsPackets.parse(packet, size) ?: continue
                        val blocked = QuietPrefs.domains(this).any { rule ->
                            rule.enabled && (query.host == rule.host.lowercase() ||
                                query.host.endsWith("." + rule.host.lowercase()))
                        }
                        val reply = if (blocked) {
                            QuietPrefs.addBlocked(this)
                            DnsPackets.blockedReply(query)
                        } else {
                            resolve(query)
                        }
                        output.write(reply)
                    }
                }
            }
        } catch (error: Exception) {
            QuietPrefs.setString(this, "network_error", error.message ?: "VPN 服务已停止")
        } finally {
            stopTunnel()
            stopSelf()
        }
    }

    private fun resolve(query: DnsPackets.Query): ByteArray = try {
        val dns = query.packet.copyOfRange(query.dnsOffset, query.dnsOffset + query.dnsLength)
        DnsPackets.response(query, DnsUpstream.query(this, dns) { socket -> protect(socket) })
    } catch (_: Exception) {
        DnsPackets.errorReply(query)
    }

    private fun stopTunnel() {
        running.set(false)
        runCatching { tunnel?.close() }
        tunnel = null
        QuietPrefs.setBool(this, "network_running", false)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onRevoke() {
        QuietPrefs.setString(this, "network_error", "VPN 被系统撤销")
        stopTunnel()
        stopSelf()
    }

    override fun onDestroy() {
        stopTunnel()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "网络过滤", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun notification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("QuietShield 正在过滤 DNS")
            .setContentText("仅在勾选应用中执行本地 DNS 过滤")
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_STOP = "dev.quietshield.STOP_VPN"
        private const val CHANNEL = "quietshield_filter"
        private const val NOTIFICATION_ID = 21
        private const val MAX_PACKET_SIZE = 32_767
    }
}
