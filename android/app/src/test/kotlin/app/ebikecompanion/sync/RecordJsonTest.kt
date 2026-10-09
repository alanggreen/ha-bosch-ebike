package app.ebikecompanion.sync

import app.ebikecompanion.ble.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordJsonTest {
    @Test fun jsonHasTheFieldsHomeAssistantExpects() {
        val r = Protocol.parseRecord(rawRecord(0x0ECD971AL, 12, power = 149f, epoch = 1_791_540_000L))!!
        val json = RecordJson.toJson(r)
        assertTrue(json, json.startsWith("{\"boot\":${0x0ECD971AL},\"seq\":12,\"epoch\":1791540000,\"uptime_ms\":24000"))
        assertTrue(json, json.contains(",\"rider_power\":149"))
        assertFalse("unset slots (NaN) must be left out", json.contains("speed"))
        assertTrue(json.endsWith("}"))
    }

    @Test fun anUndatedRecordHasNoEpochField() {
        val r = Protocol.parseRecord(rawRecord(1, 0, epoch = 0))!!
        assertFalse(RecordJson.toJson(r).contains("epoch"))
    }

    @Test fun fractionalValuesKeepTheirDecimals() {
        val r = Protocol.parseRecord(rawRecord(1, 0, power = 17.8f))!!
        assertTrue(RecordJson.toJson(r).contains("\"rider_power\":17.8"))
    }

    @Test fun parsesHomeAssistantsAck() {
        val ack = RecordJson.parseAck("{\"boot\":123,\"seq\":7}")!!
        assertEquals(RecordId(123, 7), ack.id)
        assertNull(ack.mac)
        assertNull(RecordJson.parseAck("{\"boot\":123}"))
        assertNull(RecordJson.parseAck("not json"))
    }

    @Test fun macMatchesHomeAssistantsAckMac() {
        // value produced by ack_mac("s3cret", 123, 7) in custom_components/ha_bosch_ebike/backfill_core.py
        assertEquals("cc17836b3e1c28bdd80628c3ddeea3f00cb2719a71401e83b3ed78a39fe815f5", RecordJson.ackMac("s3cret", RecordId(123, 7)))
    }

    @Test fun signedAcksAreCheckedWhenASecretIsSet() {
        val good = RecordJson.parseAck("{\"boot\":123,\"seq\":7,\"mac\":\"cc17836b3e1c28bdd80628c3ddeea3f00cb2719a71401e83b3ed78a39fe815f5\"}")!!
        assertTrue(RecordJson.ackTrusted(good, "s3cret"))
        assertFalse("wrong secret", RecordJson.ackTrusted(good, "other"))
        assertFalse("different record id", RecordJson.ackTrusted(good.copy(id = RecordId(123, 8)), "s3cret"))
        assertFalse("unsigned ack while a secret is set", RecordJson.ackTrusted(RecordJson.parseAck("{\"boot\":123,\"seq\":7}")!!, "s3cret"))
        assertTrue("no secret configured: unsigned acks are accepted", RecordJson.ackTrusted(RecordJson.parseAck("{\"boot\":123,\"seq\":7}")!!, ""))
    }

    @Test fun logPacketsAreSplitIntoValidatedRawRecords() {
        val a = rawRecord(7, 0); val b = rawRecord(7, 1)
        assertEquals(2, Protocol.splitLogPacket(byteArrayOf(2) + a + b)!!.size)
        assertEquals(0, Protocol.splitLogPacket(byteArrayOf(0))!!.size)
        assertNull("one bad record voids the packet", Protocol.splitLogPacket(byteArrayOf(2) + a + b.also { it[10] = (it[10] + 1).toByte() }))
        assertNull(Protocol.splitLogPacket(byteArrayOf(3) + a + b))
    }
}
