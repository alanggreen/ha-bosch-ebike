package app.ebikecompanion.sync

import app.ebikecompanion.ble.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/** Builds a valid 56-byte record the way the ESP32 does (CRC32 over the first 52 bytes). */
fun rawRecord(boot: Long, seq: Long, power: Float = Float.NaN, epoch: Long = 1_791_540_000L): ByteArray {
    val b = ByteBuffer.allocate(56).order(ByteOrder.LITTLE_ENDIAN)
    b.putInt(boot.toInt()).putInt(seq.toInt()).putInt(epoch.toInt()).putInt((seq * 2000).toInt())
    repeat(8) { b.putFloat(if (it == 2) power else Float.NaN) }
    b.put(0).put(0).putShort(0)
    val crc = CRC32().also { it.update(b.array(), 0, 52) }.value
    b.putInt(crc.toInt())
    return b.array()
}

class RecordStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun store() = RecordStore(tmp.root).also { it.load() }
    private fun recs(boot: Long, from: Long, to: Long) = (from..to).map { rawRecord(boot, it) }

    @Test fun appendsInOrderAndRemembersTheCursor() {
        val s = store()
        assertEquals(RecordId.NONE, s.cursor)
        assertEquals(5, s.append(recs(7, 0, 4)))
        assertEquals(RecordId(7, 4), s.cursor)
        assertEquals(5, s.size)
    }

    @Test fun repeatedRecordsAreNotStoredTwice() {
        val s = store()
        s.append(recs(7, 0, 4))
        assertEquals(0, s.append(recs(7, 2, 4)))      // a re-sent stretch
        assertEquals(2, s.append(recs(7, 3, 6)))      // overlap: only 5 and 6 are new
        assertEquals(7, s.size)
    }

    @Test fun aNewBootRestartsTheSequence() {
        val s = store()
        s.append(recs(7, 0, 3))
        assertEquals(3, s.append(recs(8, 0, 2)))
        assertEquals(RecordId(8, 2), s.cursor)
    }

    @Test fun ackReleasesEverythingUpToAndIncludingThatRecord() {
        val s = store()
        s.append(recs(7, 0, 9))
        assertEquals(4, s.ackThrough(RecordId(7, 3)))
        assertEquals(6, s.size)
        assertEquals(4L, Protocol.parseRecord(s.get(0))!!.seq)
    }

    @Test fun ackAcrossABootBoundaryFollowsLogOrderNotNumbers() {
        val s = store()
        s.append(recs(7, 0, 3) + recs(8, 0, 3))
        assertEquals(6, s.ackThrough(RecordId(8, 1)))  // all of boot 7 and the first two of boot 8
        assertEquals(2, s.size)
    }

    @Test fun ackForARecordNotQueuedChangesNothing() {
        val s = store()
        s.append(recs(7, 5, 9))
        assertEquals(-1, s.ackThrough(RecordId(7, 2)))  // already released earlier
        assertEquals(-1, s.ackThrough(RecordId(9, 0)))  // unknown
        assertEquals(5, s.size)
    }

    @Test fun surviveARestartWithQueueCursorAndPendingAck() {
        val a = store()
        a.append(recs(7, 0, 9))
        a.ackThrough(RecordId(7, 3))
        a.setPendingAck(RecordId(7, 3))
        val b = store()
        assertEquals(6, b.size)
        assertEquals(RecordId(7, 9), b.cursor)
        assertEquals(RecordId(7, 3), b.pendingAck)
        b.setPendingAck(null)
        assertNull(store().pendingAck)
    }

    @Test fun cursorSurvivesWhenEverythingWasAcknowledged() {
        val a = store()
        a.append(recs(7, 0, 4))
        a.ackThrough(RecordId(7, 4))
        val b = store()
        assertEquals(0, b.size)
        assertEquals(RecordId(7, 4), b.cursor)               // the next sync must not start from scratch
        assertEquals(0, b.append(recs(7, 0, 4)))             // and an old stretch is not stored again
    }

    @Test fun aTornTailFromACrashIsDroppedAndTheRestKept() {
        val a = store()
        a.append(recs(7, 0, 3))
        java.io.File(tmp.root, "records.bin").appendBytes(rawRecord(7, 4).copyOf(30)) // half-written record
        val b = store()
        assertEquals(4, b.size)
        assertTrue(java.io.File(tmp.root, "records.bin").length() == 4L * 56)
    }
}
