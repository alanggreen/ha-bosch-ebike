package app.ebikecompanion.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.zip.CRC32

/** Wire format of the ESP32 phone link; see docs/app/PHONE_LINK_PROTOCOL.md. All numbers are little-endian. */
object Gatt {
    const val DEVICE_NAME = "HA eBike Bridge"
    val SERVICE: UUID = UUID.fromString("7f3c1a00-5b2e-4f6a-9d1c-3e8b2a4c6d00")
    val LIVE: UUID = UUID.fromString("7f3c1a00-5b2e-4f6a-9d1c-3e8b2a4c6d01")
    val STATUS: UUID = UUID.fromString("7f3c1a00-5b2e-4f6a-9d1c-3e8b2a4c6d02")
    val LOG: UUID = UUID.fromString("7f3c1a00-5b2e-4f6a-9d1c-3e8b2a4c6d03")
    val COMMAND: UUID = UUID.fromString("7f3c1a00-5b2e-4f6a-9d1c-3e8b2a4c6d04")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    const val WANTED_MTU = 247
}

/** The 41-byte status block (characteristic ...6d02). */
data class BridgeStatus(
    val flags: Int,
    val loggerState: Int,
    val lastCommand: Int,
    val lastResult: Int,
    val bootId: Long,
    val nextSeq: Long,
    val unacked: Long,
    val dropped: Long,
    val writeFailures: Long,
    val maxAppendMs: Long,
    val usedBytes: Long,
    val uptimeMs: Long,
    val epoch: Long,
) {
    val sdOk get() = flags and 0x01 != 0
    val clockValid get() = flags and 0x02 != 0
    val bikeConnected get() = flags and 0x04 != 0
    val wifiUp get() = flags and 0x08 != 0
    val mqttUp get() = flags and 0x10 != 0
    val syncing get() = flags and 0x20 != 0
    val recording get() = flags and 0x40 != 0
}

/** One 56-byte log/live record. [values] slots follow the ESP's `ride_data_logger` sensor order. */
data class Record(
    val boot: Long,
    val seq: Long,
    val epoch: Long,
    val uptimeMs: Long,
    val values: FloatArray,
    val binaryState: Int,
    val binaryKnown: Int,
) {
    /** Slot order of the home configuration: speed, cadence, rider power, ambient brightness, battery, odometer. */
    private fun slot(i: Int): Float? = values.getOrNull(i)?.takeUnless { it.isNaN() }
    val speedKmh get() = slot(0)
    val cadenceRpm get() = slot(1)
    val riderPowerW get() = slot(2)
    val batteryPercent get() = slot(4)
    val odometerKm get() = slot(5)

    override fun equals(other: Any?) = other is Record && boot == other.boot && seq == other.seq &&
        epoch == other.epoch && uptimeMs == other.uptimeMs && values.contentEquals(other.values) &&
        binaryState == other.binaryState && binaryKnown == other.binaryKnown

    override fun hashCode() = (boot * 31 + seq).hashCode()
}

object Protocol {
    const val STATUS_SIZE = 41
    const val RECORD_SIZE = 56
    const val SENSOR_SLOTS = 8

    private fun le(b: ByteArray): ByteBuffer = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
    private fun ByteBuffer.u32(at: Int): Long = getInt(at).toLong() and 0xFFFFFFFFL

    fun parseStatus(b: ByteArray): BridgeStatus? {
        if (b.size < STATUS_SIZE || b[0].toInt() != 1) return null
        val x = le(b)
        return BridgeStatus(
            flags = b[1].toInt() and 0xFF,
            loggerState = b[2].toInt() and 0xFF,
            lastCommand = b[3].toInt() and 0xFF,
            lastResult = b[4].toInt() and 0xFF,
            bootId = x.u32(5), nextSeq = x.u32(9), unacked = x.u32(13), dropped = x.u32(17),
            writeFailures = x.u32(21), maxAppendMs = x.u32(25), usedBytes = x.u32(29),
            uptimeMs = x.u32(33), epoch = x.u32(37),
        )
    }

    /** Returns null when the size or the CRC32 over bytes 0..51 is wrong, so a corrupt record is never shown. */
    fun parseRecord(b: ByteArray, offset: Int = 0): Record? {
        if (b.size < offset + RECORD_SIZE) return null
        val rec = b.copyOfRange(offset, offset + RECORD_SIZE)
        val x = le(rec)
        val crc = CRC32().also { it.update(rec, 0, 52) }.value
        if (crc != x.u32(52)) return null
        return Record(
            boot = x.u32(0), seq = x.u32(4), epoch = x.u32(8), uptimeMs = x.u32(12),
            values = FloatArray(SENSOR_SLOTS) { x.getFloat(16 + it * 4) },
            binaryState = rec[48].toInt() and 0xFF, binaryKnown = rec[49].toInt() and 0xFF,
        )
    }

    /**
     * The validated raw 56-byte blocks of a log packet, for storing as they are. Empty is the end marker; null means the
     * packet is damaged (wrong size or a bad CRC), in which case none of it may be used.
     */
    fun splitLogPacket(b: ByteArray): List<ByteArray>? {
        if (b.isEmpty()) return null
        val n = b[0].toInt() and 0xFF
        if (b.size < 1 + n * RECORD_SIZE) return null
        return (0 until n).map {
            val raw = b.copyOfRange(1 + it * RECORD_SIZE, 1 + (it + 1) * RECORD_SIZE)
            if (parseRecord(raw) == null) return null
            raw
        }
    }

    /** A log packet is [count u8][count x record]; count 0 means the card is caught up. */
    fun parseLogPacket(b: ByteArray): List<Record>? {
        if (b.isEmpty()) return null
        val n = b[0].toInt() and 0xFF
        if (b.size < 1 + n * RECORD_SIZE) return null
        return (0 until n).map { parseRecord(b, 1 + it * RECORD_SIZE) ?: return null }
    }
}

object Commands {
    private fun u32(v: Long) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
    fun setTime(epochSeconds: Long) = byteArrayOf(1) + u32(epochSeconds)
    fun syncFrom(boot: Long, seq: Long) = byteArrayOf(2) + u32(boot) + u32(seq)
    fun ackUpTo(boot: Long, seq: Long) = byteArrayOf(3) + u32(boot) + u32(seq)
    fun stopSync() = byteArrayOf(4)
    fun getStatus() = byteArrayOf(5)
}
