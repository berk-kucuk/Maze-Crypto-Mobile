package com.mazecrypto.app.monero

import com.mazecrypto.app.core.Keccak
import java.io.ByteArrayOutputStream

/** Malformed data from a node: the scan stops rather than guess. */
class WireError(message: String) : Exception(message)

/**
 * Monero daemon's binary RPC format ("epee portable storage"), just the parts
 * /get_blocks.bin needs: a writer for the request and a reader that turns the
 * response into maps, lists, numbers, byte strings and booleans.
 */
object Epee {
    private val SIGNATURE = byteArrayOf(0x01, 0x11, 0x01, 0x01, 0x01, 0x01, 0x02, 0x01, 0x01)

    private const val INT64 = 1; private const val INT32 = 2; private const val INT16 = 3; private const val INT8 = 4
    private const val UINT64 = 5; private const val UINT32 = 6; private const val UINT16 = 7; private const val UINT8 = 8
    private const val DOUBLE = 9; private const val STRING = 10; private const val BOOL = 11; private const val OBJECT = 12
    private const val ARRAY = 0x80

    class Writer {
        private val out = ByteArrayOutputStream()
        private val entries = mutableListOf<Pair<String, ByteArray>>()

        private fun varint(n: Long, o: ByteArrayOutputStream) {
            when {
                n < 1L shl 6 -> o.write((n shl 2).toInt())
                n < 1L shl 14 -> (n shl 2 or 1).let { o.write(it.toInt() and 0xff); o.write((it shr 8).toInt() and 0xff) }
                n < 1L shl 30 -> (n shl 2 or 2).let { v -> for (i in 0 until 4) o.write((v shr (8 * i)).toInt() and 0xff) }
                else -> (n shl 2 or 3).let { v -> for (i in 0 until 8) o.write((v shr (8 * i)).toInt() and 0xff) }
            }
        }

        fun u64(name: String, v: Long) = apply {
            entries += name to ByteArray(9).also { it[0] = UINT64.toByte(); for (i in 0 until 8) it[1 + i] = (v shr (8 * i)).toByte() }
        }
        fun u8(name: String, v: Int) = apply { entries += name to byteArrayOf(UINT8.toByte(), v.toByte()) }
        fun bool(name: String, v: Boolean) = apply { entries += name to byteArrayOf(BOOL.toByte(), if (v) 1 else 0) }
        fun blob(name: String, v: ByteArray) = apply {
            val o = ByteArrayOutputStream()
            o.write(STRING); varint(v.size.toLong(), o); o.write(v)
            entries += name to o.toByteArray()
        }

        fun bytes(): ByteArray {
            out.write(SIGNATURE)
            varint(entries.size.toLong(), out)
            for ((name, value) in entries) {
                val n = name.toByteArray(Charsets.US_ASCII)
                out.write(n.size); out.write(n); out.write(value)
            }
            return out.toByteArray()
        }
    }

    fun read(b: ByteArray): Map<String, Any?> {
        if (b.size < SIGNATURE.size || !b.copyOf(SIGNATURE.size).contentEquals(SIGNATURE)) throw WireError("not an epee response")
        val r = Reader(b, SIGNATURE.size)
        return r.section(0)
    }

    private class Reader(val b: ByteArray, var i: Int) {
        fun byte(): Int { if (i >= b.size) throw WireError("truncated"); return b[i++].toInt() and 0xff }
        fun le(n: Int): Long { var v = 0L; for (k in 0 until n) v = v or (byte().toLong() shl (8 * k)); return v }
        fun varint(): Long {
            val first = b.getOrElse(i) { throw WireError("truncated") }.toInt() and 0xff
            return when (first and 3) { 0 -> le(1); 1 -> le(2); 2 -> le(4); else -> le(8) } ushr 2
        }
        fun bytes(n: Int): ByteArray {
            if (n < 0 || i + n > b.size) throw WireError("truncated")
            return b.copyOfRange(i, i + n).also { i += n }
        }

        fun section(depth: Int): Map<String, Any?> {
            if (depth > 16) throw WireError("too deep")
            val n = varint()
            if (n > 10_000 || n > b.size - i) throw WireError("section too large")
            val m = HashMap<String, Any?>()
            repeat(n.toInt()) {
                val name = String(bytes(byte()), Charsets.US_ASCII)
                val type = byte()
                m[name] = if (type and ARRAY != 0) {
                    val count = varint()
                    // Every element takes at least one byte: a count larger than what is left is a lie
                    // (and would make us allocate a huge list before failing).
                    if (count > 5_000_000 || count > b.size - i) throw WireError("array too large")
                    List(count.toInt()) { value(type and ARRAY.inv(), depth) }
                } else value(type, depth)
            }
            return m
        }

        fun value(type: Int, depth: Int): Any? = when (type) {
            INT64, UINT64 -> le(8)
            INT32, UINT32 -> le(4)
            INT16, UINT16 -> le(2)
            INT8, UINT8 -> le(1)
            DOUBLE -> java.lang.Double.longBitsToDouble(le(8))
            STRING -> { val n = varint(); if (n > Int.MAX_VALUE) throw WireError("string"); bytes(n.toInt()) }
            BOOL -> byte() != 0
            OBJECT -> section(depth + 1)
            else -> throw WireError("type $type")
        }
    }
}

/** What the scanner needs from one transaction. */
class Tx(
    val hash: ByteArray,
    val coinbase: Boolean,
    /** Ring members of each input as absolute global output indices. */
    val rings: List<LongArray>,
    val outputs: List<Out>,
    /** Tx public keys from extra (tag 0x01) and per-output additional keys (tag 0x04). */
    val pubKeys: List<ByteArray>,
    val additional: List<ByteArray>,
    val rctType: Int,
    val fee: Long,
    /** RingCT: per output, 8-byte encrypted amount (types 4+) and the amount commitment. */
    val ecdh: List<ByteArray>,
    val commitments: List<ByteArray>,
) {
    class Out(val amount: Long, val key: ByteArray, val viewTag: Int?)
}

/** Parser for block and (pruned) transaction blobs. */
class Blob(private val b: ByteArray) {
    var i = 0
        private set

    fun byte(): Int { if (i >= b.size) throw WireError("truncated"); return b[i++].toInt() and 0xff }
    fun varint(): Long {
        var v = 0L
        var shift = 0
        while (true) {
            val c = byte()
            v = v or ((c and 0x7f).toLong() shl shift)
            if (c and 0x80 == 0) return v
            shift += 7
            if (shift > 63) throw WireError("varint")
        }
    }
    fun bytes(n: Int): ByteArray {
        if (n < 0 || i + n > b.size) throw WireError("truncated")
        return b.copyOfRange(i, i + n).also { i += n }
    }
    private fun count(max: Long = 100_000): Int = varint().also { if (it > max || it > b.size - i) throw WireError("count") }.toInt()

    class Header(val timestamp: Long)

    fun header(): Header {
        varint(); varint()                      // major, minor version
        val ts = varint()
        bytes(32); bytes(4)                     // prev id, nonce
        return Header(ts)
    }

    /** One transaction: prefix and RingCT base. [hash] is given for regular txs, computed for the miner tx. */
    fun tx(hash: ByteArray?): Tx {
        val start = i
        val version = varint()
        varint()                                // unlock time
        val nIn = count()
        var coinbase = false
        val rings = ArrayList<LongArray>(nIn)
        repeat(nIn) {
            when (byte()) {
                0xff -> { varint(); coinbase = true }
                0x02 -> {
                    varint()                    // amount (0 for RingCT)
                    val n = count(1024)
                    val ring = LongArray(n)
                    var acc = 0L
                    for (k in 0 until n) { acc += varint(); ring[k] = acc }
                    bytes(32)                   // key image
                    rings += ring
                }
                else -> throw WireError("input type")
            }
        }
        val nOut = count()
        val outs = ArrayList<Tx.Out>(nOut)
        repeat(nOut) {
            val amount = varint()
            outs += when (byte()) {
                0x02 -> Tx.Out(amount, bytes(32), null)
                0x03 -> Tx.Out(amount, bytes(32), byte())
                else -> throw WireError("output type")
            }
        }
        val extra = bytes(count(1_000_000))
        val prefixEnd = i
        val pubKeys = mutableListOf<ByteArray>()
        val additional = mutableListOf<ByteArray>()
        parseExtra(extra, pubKeys, additional)
        var type = 0
        var fee = 0L
        val ecdh = mutableListOf<ByteArray>()
        val commitments = mutableListOf<ByteArray>()
        var baseEnd = i
        if (version >= 2) {
            type = byte()
            if (type != 0) {
                fee = varint()
                if (type == 2) repeat(rings.size) { bytes(32) }   // RCTTypeSimple: pseudo-outputs in the base
                repeat(nOut) { ecdh += if (type >= 4) bytes(8) else bytes(64).copyOfRange(32, 40) }
                repeat(nOut) { commitments += bytes(32) }
            }
            baseEnd = i
        }
        val h = hash ?: run {
            // Miner tx (RingCT type 0): H(H(prefix) ‖ H(base) ‖ 0³²) for v2, H(blob) for v1.
            if (version >= 2) Keccak.k256(Keccak.k256(b.copyOfRange(start, prefixEnd)) + Keccak.k256(b.copyOfRange(prefixEnd, baseEnd)) + ByteArray(32))
            else Keccak.k256(b.copyOfRange(start, baseEnd))
        }
        return Tx(h, coinbase, rings, outs, pubKeys, additional, type, fee, ecdh, commitments)
    }

    private fun parseExtra(e: ByteArray, keys: MutableList<ByteArray>, additional: MutableList<ByteArray>) {
        val p = Blob(e)
        try {
            while (p.i < e.size) {
                when (p.byte()) {
                    0x00 -> return                                  // padding to the end
                    0x01 -> keys += p.bytes(32)
                    0x02 -> p.bytes(p.byte())                       // nonce (payment id)
                    0x03 -> p.bytes(p.varint().toInt())             // merge-mining tag
                    0x04 -> repeat(p.varint().toInt().coerceAtMost(256)) { additional += p.bytes(32) }
                    0xde -> p.bytes(p.varint().toInt())             // minergate
                    else -> return
                }
            }
        } catch (_: WireError) {
            // A malformed tail: keep what was read; wallets do the same.
        }
    }

    /** The tx hashes listed after the miner tx in a block blob. */
    fun hashes(): List<ByteArray> = List(count()) { bytes(32) }
}
