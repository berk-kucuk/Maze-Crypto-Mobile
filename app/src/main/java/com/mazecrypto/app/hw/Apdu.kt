package com.mazecrypto.app.hw

import java.io.ByteArrayOutputStream
import java.io.Closeable

/**
 * A channel to a Ledger: send one APDU, get the response (data + 2-byte status word).
 * Implemented over USB HID ([LedgerUsb]) and Bluetooth LE ([LedgerBle]).
 */
interface Transport : Closeable {
    /** "usb" or "ble". */
    val kind: String
    suspend fun exchange(apdu: ByteArray, timeoutMs: Long = 60_000): ByteArray
}

/** A Ledger said no, or could not be reached. [code] is a key under m.ledger.err_*. */
class LedgerError(val code: String, val sw: Int = 0, message: String = code) : Exception(message) {
    companion object {
        fun of(sw: Int): LedgerError = LedgerError(
            when (sw) {
                0x5515, 0x6982 -> "locked"
                0x6985, 0x5501 -> "denied"
                0x6e00, 0x6e01, 0x6d00, 0x6d02, 0x6511, 0x6a83 -> "wrong_app"
                0x6807, 0x6a82, 0x6984 -> "not_installed"
                0x6b00, 0x6a80, 0x6a86, 0x6a87 -> "bad_request"
                else -> "status"
            },
            sw, "Ledger status 0x%04x".format(sw),
        )
    }
}

/**
 * Ledger's framing: an APDU is cut into packets tagged 0x05 with a sequence
 * number; the first packet carries the total length. Over USB every packet is
 * 64 bytes and starts with the channel id; over BLE packets have no channel and
 * are as long as the negotiated MTU allows.
 */
object Framing {
    private const val TAG = 0x05

    fun packets(apdu: ByteArray, size: Int, channel: Int?): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        var offset = 0
        var seq = 0
        do {
            val p = ByteArrayOutputStream()
            if (channel != null) { p.write(channel shr 8 and 0xff); p.write(channel and 0xff) }
            p.write(TAG); p.write(seq shr 8 and 0xff); p.write(seq and 0xff)
            if (seq == 0) { p.write(apdu.size shr 8 and 0xff); p.write(apdu.size and 0xff) }
            val room = size - p.size()
            val n = minOf(room, apdu.size - offset)
            p.write(apdu, offset, n)
            offset += n
            var b = p.toByteArray()
            if (channel != null && b.size < size) b = b.copyOf(size)       // HID reports are fixed-size
            out += b
            seq++
        } while (offset < apdu.size)
        return out
    }

    /** Collects response packets until the whole APDU response is in. */
    class Reader(private val channel: Int?) {
        private val buf = ByteArrayOutputStream()
        private var total = -1
        private var seq = 0

        /** The full response once complete, else null. Packets for another channel or out of order are an error. */
        fun feed(packet: ByteArray): ByteArray? {
            var i = 0
            if (channel != null) {
                if (packet.size < 5) throw LedgerError("protocol")
                val ch = (packet[0].toInt() and 0xff shl 8) or (packet[1].toInt() and 0xff)
                if (ch != channel) return null
                i = 2
            }
            if (packet.size < i + 3 || packet[i].toInt() != TAG) throw LedgerError("protocol")
            val s = (packet[i + 1].toInt() and 0xff shl 8) or (packet[i + 2].toInt() and 0xff)
            if (s != seq) throw LedgerError("protocol")
            i += 3
            if (seq == 0) {
                if (packet.size < i + 2) throw LedgerError("protocol")
                total = (packet[i].toInt() and 0xff shl 8) or (packet[i + 1].toInt() and 0xff)
                i += 2
            }
            seq++
            val n = minOf(packet.size - i, total - buf.size())
            if (n > 0) buf.write(packet, i, n)
            return if (buf.size() >= total) buf.toByteArray() else null
        }
    }

    fun apdu(cla: Int, ins: Int, p1: Int, p2: Int, data: ByteArray = ByteArray(0)): ByteArray {
        require(data.size <= 255)
        return byteArrayOf(cla.toByte(), ins.toByte(), p1.toByte(), p2.toByte(), data.size.toByte()) + data
    }
}
