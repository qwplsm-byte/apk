package dev.quietshield

import java.nio.charset.StandardCharsets

object DnsWire {
    fun hostFromDns(data: ByteArray): String? {
        if (data.size < 17) return null
        val labels = StringBuilder()
        var i = 12
        while (i < data.size) {
            val n = data[i++].toInt() and 255
            if (n == 0) break
            if (n > 63 || i + n > data.size) return null
            if (labels.isNotEmpty()) labels.append('.')
            labels.append(String(data, i, n, StandardCharsets.US_ASCII))
            i += n
        }
        return labels.toString().lowercase().takeIf { it.isNotBlank() }
    }

    fun blockedDnsReply(query: ByteArray, servfail: Boolean = false): ByteArray {
        val reply = query.copyOf()
        if (reply.size < 12) return reply
        reply[2] = 0x81.toByte()
        reply[3] = if (servfail) 0x82.toByte() else 0x83.toByte()
        for (i in 6..11) reply[i] = 0
        return reply
    }
}


