package dev.quietshield.filter

import java.net.InetAddress

object DnsPackets {
    data class Query(val packet: ByteArray, val ipHeaderLength: Int, val udpOffset: Int, val dnsOffset: Int, val dnsLength: Int, val host: String)

    fun parse(packet: ByteArray, size: Int): Query? {
        if (size < 28 || ((packet[0].toInt() and 0xf0) ushr 4) != 4) return null
        val ihl = (packet[0].toInt() and 0x0f) * 4
        if (ihl != 20 || size < ihl + 8 || packet[9].toInt() != 17) return null
        val udp = ihl
        val srcPort = u16(packet, udp)
        val dstPort = u16(packet, udp + 2)
        if (srcPort == 53 || dstPort != 53) return null
        val udpLen = u16(packet, udp + 4)
        val dns = udp + 8
        val dnsLen = udpLen - 8
        if (dnsLen < 17 || size < dns + dnsLen) return null
        val labels = StringBuilder()
        var p = dns + 12
        val limit = dns + dnsLen
        while (p < limit) {
            val len = packet[p++].toInt() and 0xff
            if (len == 0) break
            if (len > 63 || p + len > limit) return null
            if (labels.isNotEmpty()) labels.append('.')
            repeat(len) { labels.append((packet[p++].toInt() and 0xff).toChar()) }
        }
        if (labels.isEmpty()) return null
        return Query(packet.copyOf(size), ihl, udp, dns, dnsLen, labels.toString().trimEnd('.').lowercase())
    }

    fun response(query: Query, dnsData: ByteArray): ByteArray {
        val dnsLength = dnsData.size
        val out = ByteArray(query.ipHeaderLength + 8 + dnsLength)
        val original = query.packet
        System.arraycopy(original, 12, out, 16, 4)
        System.arraycopy(original, 16, out, 12, 4)
        out[0] = 0x45
        out[1] = 0
        put16(out, 2, out.size)
        put16(out, 4, u16(original, 4))
        put16(out, 6, 0x4000)
        out[8] = 64
        out[9] = 17
        out[10] = 0
        out[11] = 0
        val src = InetAddress.getByAddress(out.copyOfRange(12, 16)).address
        val dst = InetAddress.getByAddress(out.copyOfRange(16, 20)).address
        put16(out, 10, checksum(out, 0, query.ipHeaderLength))
        val udp = query.ipHeaderLength
        put16(out, udp, 53)
        put16(out, udp + 2, u16(original, query.udpOffset))
        put16(out, udp + 4, 8 + dnsLength)
        put16(out, udp + 6, 0)
        System.arraycopy(dnsData, 0, out, udp + 8, dnsLength)
        val sum = pseudoChecksum(src, dst, out, udp, 8 + dnsLength)
        put16(out, udp + 6, if (sum == 0) 0xffff else sum)
        return out
    }

    fun errorReply(q: Query): ByteArray {
        val dns = q.packet.copyOfRange(q.dnsOffset, q.dnsOffset + q.dnsLength)
        dns[2] = 0x81.toByte()
        dns[3] = 0x82.toByte()
        for (i in 6..11) dns[i] = 0
        return response(q, dns)
    }

    fun blockedReply(q: Query): ByteArray {
        val dns = q.packet.copyOfRange(q.dnsOffset, q.dnsOffset + q.dnsLength)
        dns[2] = 0x81.toByte()
        dns[3] = 0x83.toByte() // response, recursion available, NXDOMAIN
        for (i in 6..11) dns[i] = 0
        return response(q, dns)
    }

    private fun u16(a: ByteArray, i: Int) = ((a[i].toInt() and 255) shl 8) or (a[i + 1].toInt() and 255)
    private fun put16(a: ByteArray, i: Int, n: Int) { a[i] = (n ushr 8).toByte(); a[i + 1] = n.toByte() }
    private fun checksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L; var i = offset
        while (i + 1 < offset + length) { sum += u16(data, i); i += 2 }
        if (i < offset + length) sum += (data[i].toInt() and 255) shl 8
        while (sum ushr 16 != 0L) sum = (sum and 0xffff) + (sum ushr 16)
        return sum.inv().toInt() and 0xffff
    }
    private fun pseudoChecksum(src: ByteArray, dst: ByteArray, data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        for (b in src) sum += b.toInt() and 255
        for (b in dst) sum += b.toInt() and 255
        sum += 17; sum += length
        var i = offset
        while (i + 1 < offset + length) { sum += u16(data, i); i += 2 }
        if (i < offset + length) sum += (data[i].toInt() and 255) shl 8
        while (sum ushr 16 != 0L) sum = (sum and 0xffff) + (sum ushr 16)
        return sum.inv().toInt() and 0xffff
    }
}


