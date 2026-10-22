package com.mazecrypto.app.data

import com.mazecrypto.app.core.Asset
import com.mazecrypto.app.core.Leg
import com.mazecrypto.app.core.Transfer
import com.mazecrypto.app.core.Wallet
import com.mazecrypto.app.core.WalletBalance
import com.mazecrypto.app.monero.Blob
import com.mazecrypto.app.monero.Ed
import com.mazecrypto.app.monero.Epee
import com.mazecrypto.app.monero.ScanState
import com.mazecrypto.app.monero.Scanner
import com.mazecrypto.app.monero.Tx
import com.mazecrypto.app.monero.ViewWallet
import com.mazecrypto.app.monero.WireError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URI
import java.net.URL

/**
 * Monero view-only wallets, scanned on the phone.
 *
 * Every byte goes through the Tor SOCKS port the user configured (Orbot):
 * nothing is sent when that port is closed, onion nodes are reached as onion
 * services (plain HTTP inside the Tor circuit), a clearnet node only over HTTPS
 * and only after check.torproject.org confirms the connection is Tor. The node
 * never learns the view key: it serves the same pruned blocks to everyone and
 * the phone finds its own outputs in them.
 */
object MoneroSync {
    /** Onion nodes run by Monero community members (the list Feather Wallet ships with). */
    val ONION_NODES = listOf(
        "loveanvthlsepingenr7opdqy6nlhn5jidwvzzxfmwsw2rry5mn2gfqd.onion:18089",
        "plowsof3t5hogddwabaeiyrno25efmzfxyro2vligremt7sxpsclfaid.onion:18089",
        "plowsoffjexmxalw73tkjmf422gq6575fc7vicuu4javzn2ynnte6tyd.onion:18089",
        "ravfxmrzi3eet62s4nkuu5sgpq6otgtffluk3ylb2kuvimtb34sonryd.onion:18089",
        "rucknium757bokwv3ss35ftgc3gzb7hgbvvglbg3hisp7tsj2fkd2nyd.onion:18081",
        "a6orjo6aiotog3njppja5jwnd3rexzfjiejxnojvw74p3kma45fundid.onion:18089",
        "moneroxmrxw44lku6qniyarpwgznpcwml4drq7vb24ppatlcg4kmxpqd.onion:18089",
        "ip4zpbps7unk6xhlanqtw24f75akfbl3upeckfjqjks7ftfnk4i73oid.onion:18081",
    )
    private val GENESIS = Ed.hex("418015bb9ae982a1975da7d79277c2705727a56894ba0fb246adaabb1f4632e3")
    /** The v15 hard fork (view tags), for turning a date into an approximate height. */
    private const val REF_HEIGHT = 2_688_888L
    private const val REF_TIME = 1_660_420_800L        // 2022-08-13 20:00 UTC
    private const val RESCAN_TAIL = 10L
    private const val MIN_INTERVAL_MS = 120_000L

    private const val MAX_BLOCKS = 200L
    private const val BACK_STEP = 480L      // about what a node sends per request at today's block sizes

    /**
     * Sync progress per wallet id. [phase] "scan" = catching up to the tip;
     * "back" = reading history backwards, now at [height] (block time [ts]).
     */
    data class Progress(val height: Long, val tip: Long, val start: Long, val node: String, val phase: String = "scan", val ts: Long = 0) {
        val fraction: Float get() = if (tip <= start) 1f else ((height - start).toFloat() / (tip - start)).coerceIn(0f, 1f)
    }

    private val _progress = MutableStateFlow<Map<String, Progress>>(emptyMap())
    val progress: StateFlow<Map<String, Progress>> = _progress.asStateFlow()

    private val lock = Mutex()

    /**
     * Scanning is pure CPU: it gets its own few background-priority threads so the
     * screen (and the rest of the phone) always comes first, however long the scan.
     */
    private val threads = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(1, 3)
    private val scanDispatcher = java.util.concurrent.Executors.newFixedThreadPool(threads) { r ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, "xmr-scan").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    @Volatile private var lastRun = 0L
    @Volatile private var goodNode: String? = null

    lateinit var secrets: SecretBox
        private set
    private lateinit var dir: File

    fun init(filesDir: File) {
        secrets = SecretBox(File(filesDir, "secrets.json"))
        dir = File(filesDir, "monero").also { it.mkdirs() }
    }

    private fun keyName(id: String) = "xmr-view:$id"
    fun saveViewKey(id: String, key: ByteArray) = secrets.put(keyName(id), key)
    fun forget(id: String) {
        secrets.remove(keyName(id))
        File(dir, "$id.json").delete()
    }

    fun state(id: String): ScanState? = runCatching { ScanState.fromJson(File(dir, "$id.json").readText()) }.getOrNull()
    private fun saveState(id: String, s: ScanState) {
        val f = File(dir, "$id.json")
        val tmp = File(dir, "$id.json.tmp")
        tmp.writeText(s.toJson())
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    /** Approximate height at [epochSeconds] (two minutes a block), a little early to be safe. */
    fun heightAt(epochSeconds: Long): Long = maxOf(0L, REF_HEIGHT + (epochSeconds - REF_TIME) / 120 - 720)

    /** Stop reading history at what has been read so far ([resume] = go on further back). */
    fun setHistory(id: String, resume: Boolean) {
        historyRequest[id] = resume
        if (_progress.value.containsKey(id)) return          // the running scan picks it up after this batch
        val st = state(id) ?: return
        historyRequest.remove(id)
        st.floor = if (resume) 1L else st.low
        saveState(id, st)
    }

    /** "Stop here" (false) / "go on" (true) asked for while a scan runs. */
    private val historyRequest = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    fun balance(w: Wallet): WalletBalance? {
        val s = state(w.id) ?: return null
        if (s.next == 0L) return null
        val unspent = s.unspent
        val total = unspent.sumOf { it.amount }
        val locked = unspent.filter { s.tip - it.height < if (it.coinbase) 60 else 10 }.sumOf { it.amount }
        return WalletBalance(
            listOf(Asset("monero", "XMR", "Monero", total / 1e12, pending = locked / 1e12)),
            System.currentTimeMillis(), s.outputs.map { it.txid }.toSet().size,
        )
    }

    fun activity(w: Wallet): List<Transfer> {
        val s = state(w.id) ?: return emptyList()
        val byTx = LinkedHashMap<String, Pair<Long, Long>>()        // txid -> (received, spent)
        val time = HashMap<String, Long>()
        for (o in s.outputs) {
            byTx[o.txid] = (byTx[o.txid]?.first ?: 0L) + o.amount to (byTx[o.txid]?.second ?: 0L)
            time[o.txid] = o.ts
            if (o.spentTx.isNotEmpty()) {
                byTx[o.spentTx] = (byTx[o.spentTx]?.first ?: 0L) to (byTx[o.spentTx]?.second ?: 0L) + o.amount
                time[o.spentTx] = o.spentTs
            }
        }
        return byTx.map { (txid, rs) ->
            val fee = s.fees[txid]
            Transfer("monero", txid, time[txid] ?: 0L, "ok", listOf(Leg("monero", "XMR", (rs.first - rs.second) / 1e12)),
                fee = if (rs.second > 0 && fee != null) fee / 1e12 else null, feeSymbol = "XMR", feeIncluded = true,
                walletIds = setOf(w.id))
        }.sortedByDescending { it.ts }
    }

    // ── network ─────────────────────────────────────────────────────────────

    class TorError(val code: String, message: String = code) : Exception(message)

    private class Node(val base: String, val host: String, val onion: Boolean)

    private fun node(spec: String): Node {
        val s = spec.trim()
        val url = if ("://" in s) s else "http://$s"
        val u = URI(url)
        val host = u.host?.lowercase() ?: throw TorError("node", "bad node address")
        val onion = host.endsWith(".onion")
        if (u.scheme == "http" && !onion) throw TorError("node_http", "clearnet nodes need https://")
        if (u.scheme != "http" && u.scheme != "https") throw TorError("node", "bad node address")
        val port = if (u.port > 0) u.port else if (u.scheme == "https") 443 else 18081
        return Node("${u.scheme}://$host:$port", host, onion)
    }

    private fun proxy(s: Settings) = Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved(s.torHost, s.torPort))

    /** The Tor SOCKS port is open (Orbot running). Nothing else is tried when it is not. */
    private fun torPortOpen(s: Settings): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(s.torHost, s.torPort), 3_000) }
        true
    }.getOrDefault(false)

    private fun call(s: Settings, url: String, body: ByteArray?, maxBytes: Int, timeoutMs: Int = 60_000): ByteArray {
        val conn = URL(url).openConnection(proxy(s)) as HttpURLConnection
        try {
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 30_000
            conn.readTimeout = timeoutMs
            conn.useCaches = false
            conn.setRequestProperty("User-Agent", "monero-wallet")      // what every wallet sends: blends in
            if (body != null) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/octet-stream")
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            if (conn.responseCode != 200) throw NetError("node: HTTP ${conn.responseCode}")
            return conn.inputStream.use { input ->
                val buf = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    buf.write(chunk, 0, n)
                    if (buf.size() > maxBytes) throw NetError("node: response too large")
                }
                buf.toByteArray()
            }
        } finally {
            conn.disconnect()
        }
    }

    /** check.torproject.org through the proxy: the exit is Tor, not some other SOCKS server. */
    private fun confirmTor(s: Settings) {
        val o = runCatching { JSONObject(String(call(s, "https://check.torproject.org/api/ip", null, 64 * 1024, 30_000))) }
            .getOrElse { throw TorError("tor_check", it.message ?: "Tor check failed") }
        if (!o.optBoolean("IsTor")) throw TorError("not_tor")
    }

    private fun info(s: Settings, n: Node): Long {
        val o = JSONObject(String(call(s, "${n.base}/get_info", null, 256 * 1024, 45_000)))
        if (o.optString("status") != "OK") throw NetError("node: ${o.optString("status")}")
        if (!o.optBoolean("mainnet", true) || o.optString("nettype", "mainnet") != "mainnet") throw NetError("node: not mainnet")
        return o.getLong("height")
    }

    /** A node that answers, its chain height; tried in turn (custom node only, when one is set). */
    private fun pick(s: Settings, exclude: Set<String> = emptySet()): Pair<Node, Long> {
        if (!torPortOpen(s)) throw TorError("tor_off", "no Tor at ${s.torHost}:${s.torPort}")
        val specs = (if (s.moneroNode.isNotBlank()) listOf(s.moneroNode)
            else listOfNotNull(goodNode) + ONION_NODES.shuffled().filter { it != goodNode })
            .let { all -> all.filter { it !in exclude }.ifEmpty { all } }
        var last: Exception? = null
        for (spec in specs) {
            try {
                val n = node(spec)
                if (!n.onion) confirmTor(s)
                val h = info(s, n)
                goodNode = spec
                return n to h
            } catch (e: Exception) {
                if (e is TorError && e.code != "tor_check") throw e
                last = e
            }
        }
        throw NetError("no Monero node answered" + (last?.message?.let { ": $it" } ?: ""))
    }

    /** Is anything listening on the configured Tor SOCKS port (Orbot running)? */
    suspend fun torReachable(s: Settings): Boolean = withContext(Dispatchers.IO) { torPortOpen(s) }

    /** Test the configured Tor port and node from Settings. Returns the node and its height. */
    suspend fun test(s: Settings): String = withContext(Dispatchers.IO) {
        val (n, h) = pick(s)
        "${n.host} · #$h"
    }

    // ── scanning ────────────────────────────────────────────────────────────

    /**
     * Bring every view-only wallet up to the chain tip. [onUpdate] runs after each
     * batch so balances appear while a long first scan is still going.
     */
    suspend fun syncAll(wallets: List<Wallet>, settings: Settings, force: Boolean, history: Boolean = true,
                        onUpdate: (Wallet, String?) -> Unit) {
        val list = wallets.filter { it.moneroView }
        if (list.isEmpty()) return
        if (!force && System.currentTimeMillis() - lastRun < MIN_INTERVAL_MS) return
        if (!lock.tryLock()) return
        try {
            lastRun = System.currentTimeMillis()
            withContext(Dispatchers.IO) {
                var (n, tip) = try {
                    pick(settings)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    val msg = if (e is TorError) "tor:${e.code}" else e.message ?: e.javaClass.simpleName
                    lastRun = 0L                                   // retry on the next refresh, e.g. once Orbot is started
                    list.forEach { onUpdate(it, msg) }
                    return@withContext
                }
                for (w in list) {
                    try {
                        // A node that keeps cutting downloads is swapped for another; the scan resumes where it was.
                        val failed = HashSet<String>()
                        for (attempt in 0 until 3) {
                            try {
                                syncOne(w, settings, n, tip, history) { onUpdate(w, null) }
                                break
                            } catch (e: Exception) {
                                if (e !is java.io.IOException && e !is NetError) throw e
                                if (attempt == 2 || settings.moneroNode.isNotBlank()) throw e
                                goodNode?.let { failed += it }
                                goodNode = null
                                val next = pick(settings, failed)
                                n = next.first; tip = next.second
                            }
                        }
                        onUpdate(w, null)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        goodNode = null                                    // try another node next time
                        onUpdate(w, e.message ?: e.javaClass.simpleName)
                    } finally {
                        _progress.value = _progress.value - w.id
                    }
                }
            }
        } finally {
            lock.unlock()
        }
    }

    private class Batch(val parsed: List<Parsed>, val start: Long, val count: Int, val tip: Long?)

    private suspend fun fetch(s: Settings, n: Node, height: Long): Batch {
        // max_block_count keeps each answer a few MB: long Tor downloads are the ones that get cut (older nodes ignore it).
        val req = Epee.Writer().blob("block_ids", GENESIS).u64("start_height", height).bool("prune", true).bool("no_miner_tx", false)
            .u64("max_block_count", MAX_BLOCKS).bytes()
        // Tor circuits drop now and then mid-download: try the same request a few times before giving up.
        var bytes: ByteArray? = null
        var last: Exception? = null
        for (attempt in 0 until 3) {
            try {
                bytes = call(s, "${n.base}/get_blocks.bin", req, 48 * 1024 * 1024, 180_000)
                break
            } catch (e: java.io.IOException) {
                last = e
                kotlinx.coroutines.delay(2_000L * (attempt + 1))
            }
        }
        val res = Epee.read(bytes ?: throw NetError("node: ${last?.message ?: "failed"}"))
        val status = (res["status"] as? ByteArray)?.let { String(it) } ?: ""
        if (status != "OK") throw NetError("node: $status")
        @Suppress("UNCHECKED_CAST")
        val blocks = res["blocks"] as? List<Map<String, Any?>> ?: emptyList()
        @Suppress("UNCHECKED_CAST")
        val indices = res["output_indices"] as? List<Map<String, Any?>> ?: emptyList()
        val start = (res["start_height"] as? Long) ?: height
        val parsed = withContext(scanDispatcher) { parse(blocks, indices, start) }
        return Batch(parsed, start, blocks.size, res["current_height"] as? Long)
    }

    /** The expensive part — one scalar multiplication per transaction — on the scan threads. */
    private suspend fun scanAll(vw: ViewWallet, parsed: List<Parsed>): List<List<Scanner.Found>> = withContext(scanDispatcher) {
        coroutineScope {
            parsed.chunked(maxOf(1, (parsed.size + threads - 1) / threads))
                .map { part -> async { part.map { Scanner.scan(vw, it.tx) } } }.awaitAll().flatten()
        }
    }

    private suspend fun syncOne(w: Wallet, s: Settings, n: Node, tip0: Long, history: Boolean, batchDone: () -> Unit) {
        val key = secrets.get(keyName(w.id)) ?: throw TorError("no_key", "view key missing — add the wallet again")
        val vw = withContext(scanDispatcher) { ViewWallet(w.address, key) }
        // No start date: begin at the tip and read history backwards, newest first.
        val back = w.restoreHeight <= 0
        val st = state(w.id) ?: ScanState()
        if (st.next == 0L) {
            if (back) { st.next = tip0; st.low = tip0; st.floor = 1L } else st.next = maxOf(1L, w.restoreHeight)
        }
        val start = if (back) st.low else maxOf(1L, w.restoreHeight)

        // ── forward: from where the last scan stopped (a few blocks back, for reorgs) to the tip
        var height = maxOf(start, st.next - RESCAN_TAIL)
        st.rewind(height)
        var tip = tip0
        _progress.value = _progress.value + (w.id to Progress(height, tip, start, n.host))
        while (height < tip) {
            val b = fetch(s, n, height)
            b.tip?.let { tip = maxOf(tip, it) }
            if (b.count == 0) break
            if (b.start < height) st.rewind(b.start)
            val found = scanAll(vw, b.parsed)
            b.parsed.forEachIndexed { i, p -> Scanner.apply(st, p.tx, found[i], p.globals, p.height, p.ts) }
            st.resolve()
            height = b.start + b.count
            st.next = height
            st.tip = tip
            saveState(w.id, st)
            _progress.value = _progress.value + (w.id to Progress(height, tip, start, n.host))
            batchDone()
        }
        st.next = maxOf(st.next, height)
        st.tip = tip
        saveState(w.id, st)

        // ── backward: older history, one window at a time, until the floor (or until stopped / no Wi-Fi)
        while (back && history) {
            historyRequest.remove(w.id)?.let { resume -> st.floor = if (resume) 1L else st.low; saveState(w.id, st) }
            if (st.backDone) break
            val from = maxOf(st.floor, st.low - BACK_STEP)
            // Fill [from, low) completely: a node may send fewer blocks than asked for.
            var pos = from
            while (pos < st.low) {
                val b = fetch(s, n, pos)
                if (b.count == 0 || b.start > pos) throw NetError("node: missing blocks at $pos")
                val keep = b.parsed.filter { it.height >= pos && it.height < st.low }
                val found = scanAll(vw, keep)
                keep.forEachIndexed { i, p -> Scanner.apply(st, p.tx, found[i], p.globals, p.height, p.ts) }
                if (pos == from) keep.firstOrNull()?.let { st.lowTs = it.ts }
                pos = b.start + b.count
            }
            st.resolve()
            st.low = from
            saveState(w.id, st)
            _progress.value = _progress.value + (w.id to Progress(st.low, tip, start, n.host, "back", st.lowTs))
            batchDone()
        }
    }

    private class Parsed(val tx: Tx, val globals: List<Long>, val height: Long, val ts: Long)

    private fun parse(blocks: List<Map<String, Any?>>, indices: List<Map<String, Any?>>, start: Long): List<Parsed> {
        val out = ArrayList<Parsed>()
        blocks.forEachIndexed { bi, entry ->
            val blob = entry["block"] as? ByteArray ?: throw WireError("block")
            val b = Blob(blob)
            val header = b.header()
            val miner = b.tx(null)
            val hashes = b.hashes()
            val txs = (entry["txs"] as? List<*>).orEmpty().map { t ->
                when (t) {
                    is ByteArray -> t
                    is Map<*, *> -> t["blob"] as? ByteArray ?: throw WireError("tx")
                    else -> throw WireError("tx")
                }
            }
            if (txs.size != hashes.size) throw WireError("tx count")
            @Suppress("UNCHECKED_CAST")
            val perTx = (indices.getOrNull(bi)?.get("indices") as? List<Map<String, Any?>>).orEmpty()
                .map { (it["indices"] as? List<*>).orEmpty().map { v -> (v as? Long) ?: -1L } }
            val height = start + bi
            out += Parsed(miner, perTx.getOrElse(0) { emptyList() }, height, header.timestamp)
            txs.forEachIndexed { ti, txb ->
                out += Parsed(Blob(txb).tx(hashes[ti]), perTx.getOrElse(ti + 1) { emptyList() }, height, header.timestamp)
            }
        }
        return out
    }
}
