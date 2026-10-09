package app.ebikecompanion.sync

import app.ebikecompanion.ble.Protocol
import java.io.File
import java.io.FileOutputStream

/** Position in the log: the pair that is a record's identity on the ESP32 and in Home Assistant. */
data class RecordId(val boot: Long, val seq: Long) {
    companion object { val NONE = RecordId(0, 0) }
}

/**
 * The phone's durable queue: records received from the ESP32 that Home Assistant has not acknowledged yet.
 *
 * Records are kept as the raw 56-byte blocks (each carries its own CRC), appended to one file and fsynced before the
 * caller is told they are safe. The queue only ever shrinks from the front, when Home Assistant acknowledges a record
 * (everything before it in log order is then released). Two small files remember where the ESP32 stream resumes
 * ([cursor]) and an acknowledgement for the ESP32 that could not be delivered yet ([pendingAck]).
 *
 * Not thread-safe: the sync engine serialises access.
 */
class RecordStore(private val dir: File) {
    private val file = File(dir, "records.bin")
    private val cursorFile = File(dir, "cursor.txt")
    private val pendingFile = File(dir, "pending_ack.txt")
    private val items = ArrayList<ByteArray>()

    /** Last record received from the ESP32; the next sync resumes after it. (0,0) means "from the beginning". */
    var cursor: RecordId = RecordId.NONE
        private set
    var pendingAck: RecordId? = null
        private set

    val size get() = items.size

    fun load() {
        dir.mkdirs()
        items.clear()
        if (file.exists()) {
            val bytes = file.readBytes()
            var off = 0
            while (off + Protocol.RECORD_SIZE <= bytes.size) {
                val raw = bytes.copyOfRange(off, off + Protocol.RECORD_SIZE)
                if (Protocol.parseRecord(raw) == null) break // a torn tail from a crash: everything before it is intact
                items.add(raw)
                off += Protocol.RECORD_SIZE
            }
            if (off != bytes.size) rewrite()
        }
        cursor = readId(cursorFile) ?: items.lastOrNull()?.let(::idOf) ?: RecordId.NONE
        pendingAck = readId(pendingFile)
    }

    fun get(index: Int): ByteArray = items[index]

    /** Appends records that are after [cursor] in stream order; anything already seen is ignored. Returns how many were added. */
    fun append(records: List<ByteArray>): Int {
        val fresh = ArrayList<ByteArray>()
        var last = cursor
        for (raw in records) {
            val id = idOf(raw)
            if (id.boot == last.boot && id.seq <= last.seq) continue
            fresh.add(raw); last = id
        }
        if (fresh.isEmpty()) return 0
        FileOutputStream(file, true).use { out -> fresh.forEach { out.write(it) }; out.fd.sync() }
        items.addAll(fresh)
        cursor = last
        writeId(cursorFile, last)
        return fresh.size
    }

    /** Releases everything up to and including [id]. Returns how many records were dropped, or -1 if [id] is not queued. */
    fun ackThrough(id: RecordId): Int {
        val idx = items.indexOfFirst { idOf(it) == id }
        if (idx < 0) return -1
        repeat(idx + 1) { items.removeAt(0) }
        rewrite()
        return idx + 1
    }

    fun setPendingAck(id: RecordId?) {
        pendingAck = id
        if (id == null) pendingFile.delete() else writeId(pendingFile, id)
    }

    // ---- persistence helpers ------------------------------------------------

    private fun idOf(raw: ByteArray): RecordId {
        val r = Protocol.parseRecord(raw)!!
        return RecordId(r.boot, r.seq)
    }

    /** Atomic: write the survivors to a temp file, then rename over the old one. */
    private fun rewrite() {
        val tmp = File(dir, "records.tmp")
        FileOutputStream(tmp).use { out -> items.forEach { out.write(it) }; out.fd.sync() }
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    private fun writeId(f: File, id: RecordId) {
        val tmp = File(dir, f.name + ".tmp")
        FileOutputStream(tmp).use { it.write("${id.boot} ${id.seq}".toByteArray()); it.fd.sync() }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    private fun readId(f: File): RecordId? {
        if (!f.exists()) return null
        val p = f.readText().trim().split(" ")
        if (p.size != 2) return null
        return RecordId(p[0].toLongOrNull() ?: return null, p[1].toLongOrNull() ?: return null)
    }
}
