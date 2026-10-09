package app.ebikecompanion.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixtures are real notifications captured from the ESP32 with nRF Connect on 2026-10-09. */
class ProtocolTest {
    private fun hex(s: String) = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private val status = hex("01 1B 00 00 00 1A 97 CD 0E 01 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 12 00 00 00 38 00 00 00 1B B9 06 00 71 EA C8 6A")
    private val record = hex("1A 97 CD 0E 01 00 00 00 BF F2 C8 6A AA EE 26 00" + " 00 00 C0 7F".repeat(8) + " 00 00 00 00 DC B0 0D A6")

    @Test fun statusDecodesCapturedBlock() {
        val s = Protocol.parseStatus(status)!!
        assertEquals(0x0ECD971AL, s.bootId)
        assertEquals(1L, s.nextSeq)
        assertEquals(0L, s.unacked)
        assertEquals(18L, s.maxAppendMs)
        assertEquals(56L, s.usedBytes)
        assertEquals(440603L, s.uptimeMs)
        assertEquals(0x6AC8EA71L, s.epoch)
        assertTrue(s.sdOk && s.clockValid && s.wifiUp && s.mqttUp)
        assertFalse(s.bikeConnected || s.syncing || s.recording)
    }

    @Test fun statusRejectsShortOrUnknownVersion() {
        assertNull(Protocol.parseStatus(status.copyOf(40)))
        assertNull(Protocol.parseStatus(status.also { it[0] = 2 }))
    }

    @Test fun recordDecodesAndVerifiesCrc() {
        val r = Protocol.parseRecord(record)!!
        assertEquals(0x0ECD971AL, r.boot)
        assertEquals(1L, r.seq)
        assertEquals(0x6AC8F2BFL, r.epoch)
        assertNull(r.riderPowerW) // NaN on the wire means "no value"
    }

    @Test fun corruptRecordIsRejected() {
        assertNull(Protocol.parseRecord(record.also { it[20] = (it[20] + 1).toByte() }))
    }

    @Test fun recordValuesMapToSlots() {
        val b = record.copyOf()
        // slot 2 (rider power) = 149.0f = 0x43150000 (little-endian 00 00 15 43); recompute the CRC
        b[16 + 8] = 0; b[16 + 9] = 0; b[16 + 10] = 0x15; b[16 + 11] = 0x43
        val crc = java.util.zip.CRC32().also { it.update(b, 0, 52) }.value
        for (i in 0..3) b[52 + i] = (crc shr (8 * i)).toByte()
        assertEquals(149f, Protocol.parseRecord(b)!!.riderPowerW!!, 0.001f)
    }

    @Test fun logPacketEndMarkerAndShortPacket() {
        assertEquals(emptyList<Record>(), Protocol.parseLogPacket(byteArrayOf(0)))
        assertEquals(1, Protocol.parseLogPacket(byteArrayOf(1) + record)!!.size)
        assertNull(Protocol.parseLogPacket(byteArrayOf(2) + record))
    }

    @Test fun commandsMatchTheDocumentedBytes() {
        assertEquals("01 40 D7 C8 6A", Commands.setTime(0x6AC8D740L).joinToString(" ") { "%02X".format(it) })
        assertEquals(9, Commands.syncFrom(0, 0).size)
        assertEquals(3, Commands.ackUpTo(1, 2)[0].toInt())
        assertNotNull(Commands.getStatus())
    }
}
