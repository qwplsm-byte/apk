package dev.quietshield

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RuleAndDnsTests {
    @Test fun json5ConvertsCommentsSingleQuotesAndUnquotedKeys() {
        val json = Json5Compat.toJson("""{apps: [{id: 'com.example',}], // note
            groups: [],}""")
        assertNotNull(com.google.gson.JsonParser.parseString(json).asJsonObject.get("apps"))
    }

    @Test fun dnsWireReadsQuestionNameAndBuildsNxDomain() {
        val query = byteArrayOf(
            0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0,
            0x03, 'a'.code.toByte(), 'd'.code.toByte(), 's'.code.toByte(),
            0x03, 't'.code.toByte(), 'e'.code.toByte(), 's'.code.toByte(),
            0x02, 't'.code.toByte(), 'v'.code.toByte(), 0,
            0, 1, 0, 1
        )
        assertEquals("ads.test.tv", DnsWire.hostFromDns(query))
        val blocked = DnsWire.blockedDnsReply(query)
        assertEquals(0x83, blocked[3].toInt() and 0xff)
        assertEquals(0, blocked[6].toInt())
    }

    @Test fun invalidDnsNameIsRejected() {
        assertNull(DnsWire.hostFromDns(byteArrayOf(0, 1, 0, 0)))
    }
}

