package app.ebikecompanion.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class MqttLogTest {
    private val s = Settings(host = "100.64.0.5", homeIp = "192.168.0.10", user = "ebike_bridge", password = "hunter2", ackSecret = "topsecret")

    @Test fun pathKind() {
        assertEquals("remote", MqttLog.pathKind("100.64.0.5", "192.168.0.10"))
        assertEquals("home", MqttLog.pathKind(" 192.168.0.10", "192.168.0.10 "))
        assertEquals("remote", MqttLog.pathKind("h", ""))
    }

    @Test fun startLineNamesHostPathAndUserButNoSecrets() {
        val line = MqttLog.relayStart(s, "settings applied", NetPath(cellular = true, vpn = true))
        assertTrue(line.contains("100.64.0.5:1883"))
        assertTrue(line.contains("path=remote"))
        assertTrue(line.contains("user=ebike_bridge"))
        assertTrue(line.contains("settings applied"))
        assertTrue(line.contains("cellular+vpn"))
        assertFalse(line.contains("hunter2"))
        assertFalse(line.contains("topsecret"))
    }

    @Test fun failureShowsMessageAndCause() {
        val t = IOException("Unable to connect", IllegalStateException("ENETUNREACH"))
        val m = MqttLog.failure("connect failed", t)
        assertTrue(m.contains("IOException: Unable to connect"))
        assertTrue(m.contains("cause: IllegalStateException: ENETUNREACH"))
        assertTrue(MqttLog.failure("x", null).contains("unknown"))
    }

    @Test fun subscribeClassification() {
        assertEquals(AckSub.OK, SubscribeResult.classify(intArrayOf(1), null))
        assertEquals(AckSub.OK, SubscribeResult.classify(intArrayOf(0), null))
        assertEquals(AckSub.DENIED, SubscribeResult.classify(intArrayOf(0x80), null))
        assertEquals(AckSub.FAILED, SubscribeResult.classify(null, RuntimeException("boom")))
        assertEquals(AckSub.OK, SubscribeResult.classify(null, null))
    }

    @Test fun subscribeReasonsAreReadable() {
        assertEquals("The broker would not let this phone listen for acknowledgements", SubscribeResult.reason(AckSub.DENIED))
        assertTrue(SubscribeResult.reason(AckSub.FAILED, RuntimeException("timeout")).endsWith(": timeout"))
        assertEquals("", SubscribeResult.reason(AckSub.OK))
    }
}
