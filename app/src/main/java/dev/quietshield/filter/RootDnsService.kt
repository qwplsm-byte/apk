package dev.quietshield.filter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.net.VpnService
import android.content.Intent
import android.os.Build
import dev.quietshield.QuietPrefs
import dev.quietshield.DnsWire
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

/** Root-only UDP/IPv4 DNS redirect. Unsupported devices fall back to the VPN path. */
class RootDnsService : Service() {
    @Volatile private var active = false
    private var server: DatagramSocket? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopRoot(); stopSelf(); return START_NOT_STICKY }
        makeChannel()
        startForeground(22, notification())
        if (!active) thread(name = "quietshield-root-dns") { setupRoot() }
        return START_STICKY
    }

    private fun setupRoot() {
        try {
            val packages = QuietPrefs.packages(this, "network_apps")
            if (packages.isEmpty()) error("请先选择受保护应用")
            val uids = packages.mapNotNull { runCatching { packageManager.getApplicationInfo(it, 0).uid }.getOrNull() }.distinct()
            if (uids.isEmpty()) error("无法读取所选应用 UID")
            val rules = buildString {
                append("set -e; ")
                append("iptables -t nat -D OUTPUT -j QS_DNS 2>/dev/null || true; ")
                append("iptables -t nat -F QS_DNS 2>/dev/null || true; ")
                append("iptables -t nat -N QS_DNS 2>/dev/null || true; ")
                append("iptables -t nat -I OUTPUT 1 -j QS_DNS; ")
                uids.forEach { uid -> append("iptables -t nat -A QS_DNS -m owner --uid-owner $uid -p udp --dport 53 -j REDIRECT --to-ports 53535; ") }
                append("iptables -t nat -L QS_DNS >/dev/null")
            }
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", rules))
            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()
            if (process.waitFor() != 0) error("Root DNS 规则设置失败: ${error.ifBlank { output }}")
            server = DatagramSocket(53535, InetAddress.getByName("0.0.0.0")).apply { soTimeout = 1_000 }
            active = true
            QuietPrefs.setBool(this, "network_running", true)
            QuietPrefs.setLong(this, "network_started", System.currentTimeMillis())
            sendBroadcast(Intent(ACTION_STARTED).setPackage(packageName))
            dnsLoop()
        } catch (e: Exception) {
            cleanupRules()
            QuietPrefs.setBool(this, "network_running", false)
            QuietPrefs.setNetworkMode(this, "vpn")
            sendBroadcast(Intent(ACTION_FAILED).setPackage(packageName).putExtra("reason", e.message ?: "Root 不可用"))
            if (VpnService.prepare(this) == null && QuietPrefs.packages(this, "network_apps").isNotEmpty()) {
                runCatching { startForegroundService(Intent(this, LocalDnsVpnService::class.java)) }
            }
            stopSelf()
        }
    }

    private fun dnsLoop() {
        val buffer = ByteArray(4096)
        while (active) {
            try {
                val socket = server ?: break
                val incoming = DatagramPacket(buffer, buffer.size)
                socket.receive(incoming)
                val query = incoming.data.copyOfRange(incoming.offset, incoming.offset + incoming.length)
                val host = DnsWire.hostFromDns(query) ?: continue
                val blocked = QuietPrefs.domains(this).any { it.enabled && (host == it.host.lowercase() || host.endsWith("." + it.host.lowercase())) }
                val answer = if (blocked) {
                    QuietPrefs.addBlocked(this)
                    DnsWire.blockedDnsReply(query)
                } else upstream(query)
                socket.send(DatagramPacket(answer, answer.size, incoming.address, incoming.port))
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: Exception) {
                if (active) continue
            }
        }
    }

    private fun upstream(query: ByteArray): ByteArray = runCatching { DnsUpstream.query(this, query) }
        .getOrElse { DnsWire.blockedDnsReply(query, servfail = true) }

    private fun cleanupRules() {
        runCatching {
            val cmd = "iptables -t nat -D OUTPUT -j QS_DNS 2>/dev/null || true; iptables -t nat -F QS_DNS 2>/dev/null || true; iptables -t nat -X QS_DNS 2>/dev/null || true"
            Runtime.getRuntime().exec(arrayOf("su", "-c", cmd)).waitFor()
        }
    }
    private fun stopRoot() {
        active = false
        runCatching { server?.close() }
        server = null
        cleanupRules()
        QuietPrefs.setBool(this, "network_running", false)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }
    override fun onBind(intent: Intent?) = null
    override fun onDestroy() { stopRoot(); super.onDestroy() }

    private fun makeChannel() {
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("quietshield_root", "Root DNS 过滤", NotificationManager.IMPORTANCE_LOW))
    }
    private fun notification(): Notification {
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "quietshield_root") else Notification.Builder(this)
        return b.setSmallIcon(android.R.drawable.ic_lock_lock).setContentTitle("QuietShield Root DNS 过滤")
            .setContentText("仅处理勾选应用的 IPv4 UDP DNS 请求").setOngoing(true).build()
    }
    companion object {
        const val ACTION_STOP = "dev.quietshield.STOP_ROOT"
        const val ACTION_STARTED = "dev.quietshield.ROOT_STARTED"
        const val ACTION_FAILED = "dev.quietshield.ROOT_FAILED"
    }
}








