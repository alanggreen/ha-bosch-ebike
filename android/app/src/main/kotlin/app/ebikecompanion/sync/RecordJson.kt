package app.ebikecompanion.sync

import app.ebikecompanion.ble.Record
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The JSON Home Assistant's `offline_backfill` expects: the same fields the ESP32 publishes itself
 * (esphome/components/ride_data_logger). Keys follow the sensor order of the home configuration; change them here if
 * the ESP32's `ride_data_logger` sensor list changes.
 */
object RecordJson {
    val SENSOR_KEYS = listOf("speed", "cadence", "rider_power", "ambient_brightness", "battery_soc", "odometer")
    val BINARY_KEYS = listOf("connected", "light", "system_locked", "charger_connected", "light_reserve", "diagnosis_active", "in_motion")

    fun toJson(r: Record): String {
        val sb = StringBuilder(256)
        sb.append("{\"boot\":").append(r.boot).append(",\"seq\":").append(r.seq)
        if (r.epoch != 0L) sb.append(",\"epoch\":").append(r.epoch)
        sb.append(",\"uptime_ms\":").append(r.uptimeMs)
        SENSOR_KEYS.forEachIndexed { i, key ->
            val v = r.values.getOrNull(i)
            if (v != null && !v.isNaN() && !v.isInfinite()) sb.append(",\"").append(key).append("\":").append(number(v))
        }
        BINARY_KEYS.forEachIndexed { i, key ->
            if (r.binaryKnown and (1 shl i) != 0) sb.append(",\"").append(key).append("\":").append(r.binaryState and (1 shl i) != 0)
        }
        return sb.append('}').toString()
    }

    private fun number(v: Float): String = if (v == Math.rint(v.toDouble()).toFloat() && Math.abs(v) < 1e9f) v.toLong().toString() else String.format(Locale.US, "%.4f", v).trimEnd('0').trimEnd('.')

    /** An acknowledgement from Home Assistant: everything up to and including (boot, seq) is stored. */
    data class Ack(val id: RecordId, val mac: String?)

    private val bootRe = Regex("\"boot\"\\s*:\\s*(\\d+)")
    private val seqRe = Regex("\"seq\"\\s*:\\s*(\\d+)")
    private val macRe = Regex("\"mac\"\\s*:\\s*\"([0-9a-fA-F]{64})\"")

    fun parseAck(text: String): Ack? {
        val boot = bootRe.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        val seq = seqRe.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        return Ack(RecordId(boot, seq), macRe.find(text)?.groupValues?.get(1))
    }

    /** HMAC-SHA256 over "<boot>:<seq>" as lower-case hex: the same as `ack_mac()` in backfill_core.py. */
    fun ackMac(secret: String, id: RecordId): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return mac.doFinal("${id.boot}:${id.seq}".toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /** True when no secret is configured, or the ack carries a valid signature (constant-time compare). */
    fun ackTrusted(ack: Ack, secret: String): Boolean {
        if (secret.isEmpty()) return true
        val given = ack.mac?.lowercase() ?: return false
        val want = ackMac(secret, ack.id)
        var diff = 0
        for (i in want.indices) diff = diff or (want[i].code xor given[i].code)
        return diff == 0
    }
}
