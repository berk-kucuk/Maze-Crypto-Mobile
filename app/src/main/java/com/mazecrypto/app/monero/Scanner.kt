package com.mazecrypto.app.monero

import com.mazecrypto.app.core.Base58
import com.mazecrypto.app.core.Keccak
import org.json.JSONArray
import org.json.JSONObject

/**
 * A view-only Monero wallet: the primary address and the private view key.
 * That is enough to recognise every payment to the wallet (and its
 * subaddresses) but never to spend — the spend key is not in the app.
 */
class ViewWallet(val address: String, val viewKey: ByteArray) {
    val spendPub: ByteArray
    val viewPub: ByteArray
    /** Spend public key (hex) → (account, index): the main address and the first subaddresses. */
    val subs = HashMap<String, Pair<Int, Int>>()

    init {
        val d = Base58.decodeMonero(address) ?: throw IllegalArgumentException("address")
        spendPub = d.copyOfRange(1, 33)
        viewPub = d.copyOfRange(33, 65)
        subs[Ed.hex(spendPub)] = 0 to 0
        val b = Ed.decode(spendPub) ?: throw IllegalArgumentException("address")
        for (major in 0 until SUB_ACCOUNTS) {
            for (minor in 0 until (if (major == 0) SUB_INDICES_MAIN else SUB_INDICES)) {
                if (major == 0 && minor == 0) continue
                val m = Ed.hs("SubAddr".toByteArray() + byteArrayOf(0) + viewKey + u32(major) + u32(minor))
                subs[Ed.hex(Ed.encode(Ed.add(b, Ed.mulG(m))))] = major to minor
            }
        }
    }

    companion object {
        const val SUB_ACCOUNTS = 3
        const val SUB_INDICES_MAIN = 100
        const val SUB_INDICES = 20

        private fun u32(n: Int) = byteArrayOf(n.toByte(), (n shr 8).toByte(), (n shr 16).toByte(), (n shr 24).toByte())

        /**
         * Check a pasted view key against the address: null when it is the address's
         * private view key, else a reason — "primary" (a subaddress or integrated
         * address was given), "format", "spend_key" (it is the *spend* key: refuse
         * and forget it) or "mismatch".
         */
        fun check(address: String, keyHex: String): String? {
            val d = Base58.decodeMonero(address) ?: return "format"
            if (d.size != 69 || d[0].toInt() != 18) return "primary"
            val k = keyHex.trim().lowercase()
            if (k.length != 64 || k.any { it !in "0123456789abcdef" }) return "format"
            val key = Ed.hex(k)
            if (!Ed.isScalar(key)) return "format"
            val pub = Ed.encode(Ed.mulG(key))
            if (pub.contentEquals(d.copyOfRange(1, 33))) return "spend_key"
            if (!pub.contentEquals(d.copyOfRange(33, 65))) return "mismatch"
            return null
        }
    }
}

/** An output that belongs to the wallet; [spentTx] is set once a spend of it has been seen. */
class Owned(
    val txid: String, val height: Long, val ts: Long, val index: Int, val global: Long, val amount: Long,
    val major: Int, val minor: Int, val coinbase: Boolean,
    var spentTx: String = "", var spentHeight: Long = 0, var spentTs: Long = 0,
) {
    fun toJson(): JSONObject = JSONObject().put("tx", txid).put("h", height).put("ts", ts).put("i", index).put("g", global)
        .put("a", amount.toString()).put("ma", major).put("mi", minor).put("cb", coinbase)
        .put("st", spentTx).put("sh", spentHeight).put("sts", spentTs)

    companion object {
        fun fromJson(o: JSONObject) = Owned(
            o.getString("tx"), o.getLong("h"), o.getLong("ts"), o.getInt("i"), o.getLong("g"), o.getString("a").toLong(),
            o.optInt("ma"), o.optInt("mi"), o.optBoolean("cb"), o.optString("st"), o.optLong("sh"), o.optLong("sts"),
        )
    }
}

/**
 * A transaction that paid the wallet, with its rings (absolute output indices).
 * Kept so spends can be worked out in any scan order: a payment found later
 * (scanning backwards) can still be matched to a spend seen earlier.
 */
class Pay(val txid: String, val height: Long, val ts: Long, val fee: Long, val rings: List<LongArray>) {
    fun toJson(): JSONObject = JSONObject().put("tx", txid).put("h", height).put("ts", ts).put("f", fee)
        .put("r", JSONArray().apply { rings.forEach { r -> put(JSONArray().apply { r.forEach { put(it) } }) } })

    companion object {
        fun fromJson(o: JSONObject): Pay {
            val r = o.getJSONArray("r")
            return Pay(o.getString("tx"), o.getLong("h"), o.getLong("ts"), o.optLong("f"),
                (0 until r.length()).map { i -> r.getJSONArray(i).let { a -> LongArray(a.length()) { a.getLong(it) } } })
        }
    }
}

/**
 * Where a wallet's scan stands. Forward: [next] is the next block to read up to
 * the tip. Backward (no start date given): [low] is the oldest block read so
 * far and [floor] where to stop; [lowTs] is that block's time.
 */
class ScanState(
    var next: Long = 0, val outputs: MutableList<Owned> = mutableListOf(), val fees: MutableMap<String, Long> = HashMap(),
    var tip: Long = 0, var low: Long = 0, var floor: Long = 0, var lowTs: Long = 0, val pays: MutableList<Pay> = mutableListOf(),
) {
    val backDone: Boolean get() = low <= floor

    fun toJson(): String = JSONObject().put("v", VERSION).put("next", next).put("tip", tip)
        .put("low", low).put("floor", floor).put("low_ts", lowTs)
        .put("outputs", JSONArray().apply { outputs.forEach { put(it.toJson()) } })
        .put("pays", JSONArray().apply { pays.forEach { put(it.toJson()) } })
        .put("fees", JSONObject().apply { fees.forEach { (k, v) -> put(k, v) } }).toString()

    /** Forget everything from [height] on, before reading those blocks again (a reorg may have changed them). */
    fun rewind(height: Long) {
        outputs.removeAll { it.height >= height }
        pays.removeAll { it.height >= height }
        next = minOf(next, height)
        resolve()
    }

    /**
     * Work out the spends from scratch. A view key cannot compute key images,
     * so a spend is recognised the way it almost always looks: a transaction
     * that sends change back to the wallet and has one of the wallet's earlier
     * outputs in a ring spent that output (one per ring).
     */
    fun resolve() {
        outputs.forEach { it.spentTx = ""; it.spentHeight = 0; it.spentTs = 0 }
        fees.clear()
        val byGlobal = outputs.filter { it.global >= 0 }.groupBy { it.global }
        for (p in pays.sortedBy { it.height }) {
            var any = false
            for (ring in p.rings) {
                val o = ring.asList().flatMap { byGlobal[it].orEmpty() }
                    .filter { it.spentTx.isEmpty() && it.height < p.height && it.txid != p.txid }
                    .minByOrNull { it.global } ?: continue
                o.spentTx = p.txid; o.spentHeight = p.height; o.spentTs = p.ts
                any = true
            }
            if (any) fees[p.txid] = p.fee
        }
    }

    val unspent: List<Owned> get() = outputs.filter { it.spentTx.isEmpty() }

    companion object {
        /** 2: spends kept as [Pay]s; older states are scanned again. */
        const val VERSION = 2

        fun fromJson(s: String): ScanState? {
            val o = JSONObject(s)
            if (o.optInt("v") != VERSION) return null
            val outs = o.optJSONArray("outputs")?.let { a -> (0 until a.length()).map { Owned.fromJson(a.getJSONObject(it)) } } ?: emptyList()
            val pays = o.optJSONArray("pays")?.let { a -> (0 until a.length()).map { Pay.fromJson(a.getJSONObject(it)) } } ?: emptyList()
            val st = ScanState(o.optLong("next"), outs.toMutableList(), HashMap(), o.optLong("tip"),
                o.optLong("low"), o.optLong("floor"), o.optLong("low_ts"), pays.toMutableList())
            st.resolve()
            return st
        }
    }
}

object Scanner {
    class Found(val index: Int, val amount: Long, val major: Int, val minor: Int)

    private val VIEW_TAG = "view_tag".toByteArray()
    private val AMOUNT = "amount".toByteArray()
    private val MASK = "commitment_mask".toByteArray()

    /** The outputs of [tx] that pay [w]. Pure and independent per transaction, so it runs in parallel. */
    fun scan(w: ViewWallet, tx: Tx): List<Found> {
        if (tx.outputs.isEmpty()) return emptyList()
        val derivations = tx.pubKeys.distinctBy { Ed.hex(it) }.mapNotNull { r -> Ed.decode(r)?.let { Ed.encode(Ed.mul8(Ed.mul(w.viewKey, it))) } }
        val out = mutableListOf<Found>()
        tx.outputs.forEachIndexed { i, o ->
            val cands = ArrayList(derivations)
            tx.additional.getOrNull(i)?.let { r -> Ed.decode(r)?.let { cands += Ed.encode(Ed.mul8(Ed.mul(w.viewKey, it))) } }
            val vi = Ed.varint(i.toLong())
            for (d in cands) {
                if (o.viewTag != null && (Keccak.k256(VIEW_TAG + d + vi)[0].toInt() and 0xff) != o.viewTag) continue
                val s = Ed.hs(d + vi)
                val p = Ed.decode(o.key) ?: break
                val sub = w.subs[Ed.hex(Ed.encode(Ed.add(p, Ed.neg(Ed.mulG(s)))))] ?: continue
                val amount = when {
                    tx.coinbase || tx.rctType == 0 -> o.amount
                    tx.rctType >= 4 -> decodeAmount(tx, i, s) ?: break
                    else -> break                     // pre-2019 RingCT amount encoding: not supported
                }
                out += Found(i, amount, sub.first, sub.second)
                break
            }
        }
        return out
    }

    /** The 8-byte encrypted amount, checked against the output's commitment so a garbled one is never counted. */
    private fun decodeAmount(tx: Tx, i: Int, s: ByteArray): Long? {
        val enc = tx.ecdh.getOrNull(i) ?: return null
        val c = tx.commitments.getOrNull(i) ?: return null
        val pad = Keccak.k256(AMOUNT + s)
        var amount = 0L
        for (k in 0 until 8) amount = amount or (((enc[k].toInt() xor pad[k].toInt()) and 0xff).toLong() shl (8 * k))
        val mask = Ed.hs(MASK + s)
        val commit = Ed.encode(Ed.add(Ed.mulG(mask), Ed.mulH(Ed.u64le(amount))))
        return if (commit.contentEquals(c)) amount else null
    }

    /** Record what [found] says about [tx] at [height]; call [ScanState.resolve] after a batch. */
    fun apply(state: ScanState, tx: Tx, found: List<Found>, globals: List<Long>, height: Long, ts: Long) {
        if (found.isEmpty()) return
        val txid = Ed.hex(tx.hash)
        for (f in found) {
            if (state.outputs.any { it.txid == txid && it.index == f.index }) continue
            state.outputs += Owned(txid, height, ts, f.index, globals.getOrElse(f.index) { -1L }, f.amount, f.major, f.minor, tx.coinbase)
        }
        if (!tx.coinbase && tx.rings.isNotEmpty() && state.pays.none { it.txid == txid }) {
            state.pays += Pay(txid, height, ts, tx.fee, tx.rings)
        }
    }
}
