package com.mazecrypto.app.data

import com.mazecrypto.app.core.Address
import com.mazecrypto.app.core.Asset
import com.mazecrypto.app.core.Coin
import com.mazecrypto.app.core.Leg
import com.mazecrypto.app.core.Transfer
import com.mazecrypto.app.core.Wallet
import com.mazecrypto.app.core.WalletBalance
import com.mazecrypto.app.core.optDoubleOrNull
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.BigInteger
import java.security.MessageDigest
import java.time.Instant

/** A network the app can watch addresses on. URL templates take {a} (address) or {t} (transaction id). */
class ChainInfo(
    val id: String,
    val name: String,
    val native: Coin?,
    val decimals: Int,
    val explorerAddress: String = "",
    val explorerTx: String = "",
    /** Transaction history can be read in the app (otherwise: balances only, history in the explorer). */
    val activity: Boolean = false,
    /** No public balance: the user enters the amount. */
    val manual: Boolean = false,
)

/** A token whose contract is known for certain, so it joins the coin it represents. */
class KnownToken(val chain: String, val contract: String, val coin: Coin, val decimals: Int)

object Chains {
    private val BTC = Coin("bitcoin", "BTC", "Bitcoin")
    private val ETH = Coin("ethereum", "ETH", "Ethereum")
    private val USDT = Coin("tether", "USDT", "Tether")
    private val USDC = Coin("usd-coin", "USDC", "USDC")

    val ALL = listOf(
        ChainInfo("bitcoin", "Bitcoin", BTC, 8, "https://blockstream.info/address/{a}", "https://blockstream.info/tx/{t}", activity = true),
        ChainInfo("ethereum", "Ethereum", ETH, 18, "https://etherscan.io/address/{a}", "https://etherscan.io/tx/{t}", activity = true),
        ChainInfo("solana", "Solana", Coin("solana", "SOL", "Solana"), 9, "https://solscan.io/account/{a}", "https://solscan.io/tx/{t}", activity = true),
        ChainInfo("tron", "TRON", Coin("tron", "TRX", "TRON"), 6, "https://tronscan.org/#/address/{a}", "https://tronscan.org/#/transaction/{t}", activity = true),
        ChainInfo("litecoin", "Litecoin", Coin("litecoin", "LTC", "Litecoin"), 8, "https://litecoinspace.org/address/{a}", "https://litecoinspace.org/tx/{t}", activity = true),
        ChainInfo("base", "Base", ETH, 18, "https://basescan.org/address/{a}", "https://basescan.org/tx/{t}"),
        ChainInfo("arbitrum", "Arbitrum One", ETH, 18, "https://arbiscan.io/address/{a}", "https://arbiscan.io/tx/{t}"),
        ChainInfo("optimism", "OP Mainnet", ETH, 18, "https://optimistic.etherscan.io/address/{a}", "https://optimistic.etherscan.io/tx/{t}"),
        ChainInfo("polygon", "Polygon", Coin("polygon-ecosystem-token", "POL", "POL (ex-MATIC)"), 18,
            "https://polygonscan.com/address/{a}", "https://polygonscan.com/tx/{t}"),
        // Spot, perp margin and staked HYPE on HyperCore plus HYPE on HyperEVM, as one balance.
        ChainInfo("hyperliquid", "Hyperliquid", Coin("hyperliquid", "HYPE", "Hyperliquid"), 18,
            "https://app.hyperliquid.xyz/explorer/address/{a}", "https://app.hyperliquid.xyz/explorer/tx/{t}", activity = true),
        // Monero balances cannot be read from an address — that is the point of Monero.
        ChainInfo("monero", "Monero", Coin("monero", "XMR", "Monero"), 12, manual = true),
        ChainInfo("manual", "Manual", null, 8, manual = true),
    )
    private val BY_ID = ALL.associateBy { it.id }

    fun of(id: String): ChainInfo? = BY_ID[id]

    /** Contracts checked against the chains themselves; lower-case for EVM, exact for base58. */
    val TOKENS = listOf(
        KnownToken("ethereum", "0xdac17f958d2ee523a2206206994597c13d831ec7", USDT, 6),
        KnownToken("ethereum", "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48", USDC, 6),
        KnownToken("ethereum", "0x6b175474e89094c44da98b954eedeac495271d0f", Coin("dai", "DAI", "Dai"), 18),
        KnownToken("ethereum", "0x2260fac5e5542a773aa44fbcfedf7c193bc2c599", Coin("wrapped-bitcoin", "WBTC", "Wrapped Bitcoin"), 8),
        KnownToken("ethereum", "0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2", Coin("weth", "WETH", "WETH"), 18),
        KnownToken("ethereum", "0x514910771af9ca656af840dff83e8264ecf986ca", Coin("chainlink", "LINK", "Chainlink"), 18),
        KnownToken("ethereum", "0x1f9840a85d5af5bf1d1762f925bdaddc4201f984", Coin("uniswap", "UNI", "Uniswap"), 18),
        KnownToken("base", "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913", USDC, 6),
        KnownToken("arbitrum", "0xaf88d065e77c8cc2239327c5edb3a432268e5831", USDC, 6),
        KnownToken("arbitrum", "0xfd086bc7cd5c481dcc9c85ebe478a1c0b69fcbb9", USDT, 6),
        KnownToken("optimism", "0x0b2c639c533813f4aa9d7837caf62653d097ff85", USDC, 6),
        KnownToken("polygon", "0x3c499c542cef5e3811e1192ce70d8cc03d5c3359", USDC, 6),
        KnownToken("polygon", "0xc2132d05d31c914a87c6611c10748aeb04b58e8f", USDT, 6),
        KnownToken("solana", "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v", USDC, 6),
        KnownToken("solana", "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB", USDT, 6),
        KnownToken("solana", "JUPyiwrYJFskUPiHa7hkeR8VUtAeFoSYbKedZNsDvCN", Coin("jupiter-exchange-solana", "JUP", "Jupiter"), 6),
        KnownToken("solana", "DezXAZ8z7PnrnRJjz3wXBoRgixCa6xjnB7YaB1pPB263", Coin("bonk", "BONK", "Bonk"), 5),
        KnownToken("tron", "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t", USDT, 6),
        KnownToken("tron", "TEkxiTehnzSmSe2XqrBj4w32RUN966rdz8", USDC, 6),
    )
    private val TOKEN_INDEX = TOKENS.associateBy { it.chain + ":" + it.contract }

    fun token(chain: String, contract: String): KnownToken? =
        TOKEN_INDEX["$chain:$contract"] ?: TOKEN_INDEX["$chain:${contract.lowercase()}"]

    /** Where an address for [coinId] can live, most common first; "manual" when none can be read. */
    fun forCoin(coinId: String): List<String> {
        val native = ALL.filter { !it.manual && it.native?.id == coinId }.map { it.id }
        val tokens = TOKENS.filter { it.coin.id == coinId }.map { it.chain }
        val xmr = if (coinId == "monero") listOf("monero") else emptyList()
        return (native + tokens + xmr).distinct().ifEmpty { listOf("manual") }
    }

    /** Every coin a wallet on [chain] may report, so their prices are loaded before the first balance. */
    fun coinsOf(chain: String): List<Coin> =
        listOfNotNull(of(chain)?.native) + TOKENS.filter { it.chain == chain }.map { it.coin }

    fun explorerUrl(chain: String, address: String): String? =
        of(chain)?.explorerAddress?.takeIf { it.isNotEmpty() }?.replace("{a}", address)

    fun txUrl(chain: String, txid: String): String? =
        of(chain)?.explorerTx?.takeIf { it.isNotEmpty() }?.replace("{t}", txid)
}

/** A value plus where it came from: [staleAge] seconds when served from the cache after a failure. */
class Fetched<T>(val value: T, val staleAge: Double? = null, val error: String? = null)

/**
 * Balances and history of public addresses, from public indexers that need no
 * account or API key. Every request goes through [Http] — the host allow-list,
 * HTTPS only, the optional Tor/SOCKS proxy, per-host spacing — and every
 * number that comes back is validated before it is shown or summed.
 *
 * Tokens: a token counts when its contract is in [Chains.TOKENS] (it then
 * joins its coin), or when the chain's indexer prices it and does not flag
 * it (Blockscout's reputation, Jupiter's verified list). Everything else —
 * the airdropped spam every busy address collects — is left out and counted
 * in [WalletBalance.ignored]. Zero-value token transfers (address-poisoning
 * bait) never appear in the history.
 */
class WalletService(private val http: Http, private val cache: Cache) {

    // ── cache ─────────────────────────────────────────────────────────────────

    private suspend fun cachedText(key: String, ttl: Double, cacheOnly: Boolean, fetch: suspend () -> String): Fetched<String> {
        cache.fresh(key, ttl)?.let { return Fetched(it) }
        if (cacheOnly) {
            val (ts, v) = cache.stale(key) ?: throw NetError("not cached")
            return Fetched(v, (System.currentTimeMillis() - ts) / 1000.0)
        }
        return try {
            val v = fetch()
            cache.put(key, v)
            Fetched(v)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val (ts, v) = cache.stale(key) ?: throw e
            Fetched(v, (System.currentTimeMillis() - ts) / 1000.0, e.message ?: e.javaClass.simpleName)
        }
    }

    suspend fun balance(w: Wallet, ttl: Double, cacheOnly: Boolean = false): Fetched<WalletBalance> {
        val f = cachedText("w:bal:${Address.key(w.chain, w.address)}", ttl, cacheOnly) { fetchBalance(w).toJson() }
        return Fetched(WalletBalance.fromJson(f.value), f.staleAge, f.error)
    }

    suspend fun activity(w: Wallet, cacheOnly: Boolean = false): Fetched<List<Transfer>> {
        val info = Chains.of(w.chain)
        if (info == null || !info.activity) return Fetched(emptyList())
        val f = cachedText("w:tx:${Address.key(w.chain, w.address)}", TTL_ACTIVITY, cacheOnly) { Transfer.listToJson(fetchActivity(w)) }
        return Fetched(Transfer.listFromJson(f.value).map { it.copy(walletIds = setOf(w.id)) }, f.staleAge, f.error)
    }

    private suspend fun fetchBalance(w: Wallet): WalletBalance = when (w.chain) {
        "bitcoin", "litecoin" -> esploraBalance(w)
        "ethereum" -> try {
            blockscoutBalance(w)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            rpcBalance(w)       // the indexer is down or throttled: native + known tokens from a node
        }
        "base", "arbitrum", "optimism", "polygon" -> rpcBalance(w)
        "solana" -> solanaBalance(w)
        "tron" -> tronBalance(w)
        "hyperliquid" -> hyperliquidBalance(w)
        else -> throw NotSupported(w.chain)
    }

    private suspend fun fetchActivity(w: Wallet): List<Transfer> = when (w.chain) {
        "bitcoin", "litecoin" -> esploraActivity(w)
        "ethereum" -> blockscoutActivity(w)
        "solana" -> solanaActivity(w)
        "tron" -> tronActivity(w)
        "hyperliquid" -> hyperliquidActivity(w)
        else -> emptyList()
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun native(w: Wallet): Coin = Chains.of(w.chain)?.native ?: throw NotSupported(w.chain)

    /** An integer amount in base units (string or number, decimal or 0x-hex) scaled by [decimals]. */
    private fun units(raw: Any?, decimals: Int): Double {
        val s = raw?.toString()?.trim() ?: throw ValidationError("amount missing")
        val n = when {
            s.startsWith("0x") -> if (s.length == 2) BigInteger.ZERO else BigInteger(s.substring(2), 16)
            s.matches(Regex("^[0-9]{1,80}$")) -> BigInteger(s)
            else -> throw ValidationError("amount: not an integer")
        }
        if (n.signum() < 0) throw ValidationError("amount: negative")
        if (decimals !in 0..36) throw ValidationError("decimals")
        val v = BigDecimal(n).movePointLeft(decimals).toDouble()
        if (!v.isFinite()) throw ValidationError("amount: not finite")
        return v
    }

    private fun decimalsOf(raw: Any?): Int = V.number(raw, "decimals").toInt().also { if (it !in 0..36) throw ValidationError("decimals") }

    /** A stable id for a token only the chain's indexer can price; never sent to CoinGecko. */
    private fun syntheticId(chain: String, contract: String): String {
        val h = MessageDigest.getInstance("SHA-1").digest("$chain:$contract".toByteArray()).joinToString("") { "%02x".format(it) }
        return "x-$chain-${h.take(16)}"
    }

    private fun tokenSymbol(raw: Any?): String =
        raw?.toString()?.trim()?.uppercase()?.filter { it.isLetterOrDigit() }?.take(12)?.ifEmpty { "?" } ?: "?"

    private fun tokenName(raw: Any?, fallback: String): String =
        raw?.toString()?.trim()?.filter { !it.isISOControl() }?.take(40)?.ifEmpty { fallback } ?: fallback

    private fun iso(ts: String?): Long = ts?.let { runCatching { Instant.parse(it).epochSecond }.getOrNull() } ?: 0L

    /**
     * Try each provider in turn until one answers. A provider can be blocked
     * where the user is (country, VPN exit, ISP) or simply down; the next one
     * usually is not. When none answers, the caller falls back to the last
     * cached value, and the next refresh tries them all again.
     */
    private suspend fun <T> firstOk(urls: List<String>, call: suspend (String) -> T): T {
        var last: Exception? = null
        for (url in urls) {
            try {
                return call(url)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // The address itself was refused: another mirror will say the same.
                if (e is HttpError && e.code == 400) throw ValidationError("address rejected by the explorer")
                if (e is ValidationError && e.message?.startsWith("wrong chain") != true) throw e
                last = e
            }
        }
        throw last ?: NetError("no provider")
    }

    // ── Bitcoin / Litecoin: Esplora (Blockstream, mempool.space, litecoinspace) ──

    private suspend fun <T> esplora(w: Wallet, call: suspend (String) -> T): T =
        firstOk(ESPLORA[w.chain] ?: throw NotSupported(w.chain), call)

    private suspend fun esploraBalance(w: Wallet): WalletBalance = esplora(w) { base ->
        val o = http.getObject("$base/address/${w.address}")
        val chain = o.getJSONObject("chain_stats")
        val mem = o.optJSONObject("mempool_stats") ?: JSONObject()
        fun net(s: JSONObject) = V.number(s.opt("funded_txo_sum")) - V.number(s.opt("spent_txo_sum"), allowNegative = true)
        val confirmed = net(chain) / 1e8
        val pending = net(mem) / 1e8
        val coin = native(w)
        WalletBalance(
            listOf(Asset(coin.id, coin.symbol, coin.name, maxOf(0.0, confirmed + pending), pending = pending)),
            System.currentTimeMillis(), chain.optInt("tx_count") + mem.optInt("tx_count"),
        )
    }

    private suspend fun esploraActivity(w: Wallet): List<Transfer> = esplora(w) { base ->
        val coin = native(w)
        http.getArray("$base/address/${w.address}/txs").objects().take(25).mapNotNull { tx ->
            runCatching {
                var received = 0L
                var spent = 0L
                var from = ""
                var to = ""
                tx.optJSONArray("vin")?.objects()?.forEach { vin ->
                    val prev = vin.optJSONObject("prevout") ?: return@forEach
                    val a = prev.optString("scriptpubkey_address")
                    if (a == w.address) spent += V.number(prev.opt("value")).toLong() else if (from.isEmpty()) from = a
                }
                tx.optJSONArray("vout")?.objects()?.forEach { out ->
                    val a = out.optString("scriptpubkey_address")
                    if (a == w.address) received += V.number(out.opt("value")).toLong() else if (to.isEmpty() && a.isNotEmpty()) to = a
                }
                val status = tx.optJSONObject("status")
                val confirmed = status?.optBoolean("confirmed") == true
                val delta = (received - spent) / 1e8
                Transfer(
                    w.chain, tx.getString("txid").take(80), if (confirmed) status!!.optLong("block_time") else 0L,
                    if (confirmed) "ok" else "pending", listOf(Leg(coin.id, coin.symbol, delta)),
                    fee = if (spent > 0) V.number(tx.opt("fee")) / 1e8 else null, feeSymbol = coin.symbol, feeIncluded = true,
                    counterparty = if (delta < 0) to else from,
                )
            }.getOrNull()
        }
    }

    // ── Ethereum: Blockscout (balances, tokens, history) ─────────────────────

    private suspend fun blockscoutBalance(w: Wallet): WalletBalance {
        val coin = native(w)
        val base = "$BLOCKSCOUT/addresses/${w.address}"
        val info = try {
            http.getObject(base)
        } catch (e: HttpError) {
            // Never seen by the indexer: an unused address, empty.
            if (e.code == 404) return WalletBalance(listOf(Asset(coin.id, coin.symbol, coin.name, 0.0)), System.currentTimeMillis(), 0)
            throw e
        }
        val assets = mutableListOf(Asset(coin.id, coin.symbol, coin.name, units(info.opt("coin_balance").takeIf { it != JSONObject.NULL } ?: "0", 18)))
        var ignored = 0
        var params: Map<String, Any> = mapOf("type" to "ERC-20")
        for (page in 0 until 2) {
            val o = http.getObject("$base/tokens", params)
            for (item in o.optJSONArray("items")?.objects() ?: emptyList()) {
                val tok = item.optJSONObject("token") ?: continue
                val contract = tok.optString("address_hash").lowercase()
                val r = runCatching {
                    val dec = decimalsOf(tok.opt("decimals"))
                    val amount = units(item.opt("value"), dec)
                    val known = Chains.token("ethereum", contract)
                    if (known != null) return@runCatching Asset(known.coin.id, known.coin.symbol, known.coin.name, amount, contract = contract)
                    val rate = V.optional(tok.opt("exchange_rate"))
                    val flagged = tok.optString("reputation").let { it.isNotEmpty() && it != "ok" }
                    if (rate == null || rate <= 0 || flagged || !tradable(amount * rate, V.optional(tok.opt("circulating_market_cap")),
                            V.optional(tok.opt("volume_24h")))) return@runCatching null
                    val sym = tokenSymbol(tok.opt("symbol"))
                    Asset(syntheticId("ethereum", contract), sym, tokenName(tok.opt("name"), sym), amount, rate, contract)
                }.getOrNull()
                if (r != null && r.amount > 0) assets += r else ignored++
            }
            val next = o.optJSONObject("next_page_params") ?: break
            params = mapOf("type" to "ERC-20") + next.keys().asSequence().filter { !next.isNull(it) }.associateWith { next.get(it).toString() }
        }
        return WalletBalance(merge(assets), System.currentTimeMillis(), V.optional(info.opt("transactions_count"))?.toInt(), ignored)
    }

    private suspend fun blockscoutActivity(w: Wallet): List<Transfer> {
        val coin = native(w)
        val me = w.address.lowercase()
        val base = "$BLOCKSCOUT/addresses/${w.address}"
        val byHash = LinkedHashMap<String, Transfer>()
        val txs = try { http.getObject("$base/transactions").optJSONArray("items")?.objects() ?: emptyList() } catch (e: HttpError) {
            if (e.code == 404) emptyList() else throw e
        }
        for (tx in txs.take(25)) runCatching {
            val hash = tx.getString("hash").take(80)
            val from = tx.optJSONObject("from")?.optString("hash")?.lowercase() ?: ""
            val to = tx.optJSONObject("to")?.optString("hash")?.lowercase() ?: ""
            val value = units(tx.opt("value") ?: "0", 18)
            val legs = mutableListOf<Leg>()
            if (value > 0) {
                if (from == me && to != me) legs += Leg(coin.id, coin.symbol, -value)
                if (to == me && from != me) legs += Leg(coin.id, coin.symbol, value)
            }
            val pending = tx.isNull("block_number") || tx.optString("result") == "pending"
            val failed = tx.optString("status") == "error"
            val fee = if (from == me) tx.optJSONObject("fee")?.opt("value")?.let { units(it, 18) } else null
            byHash[hash] = Transfer(w.chain, hash, if (pending) 0 else iso(tx.optString("timestamp")),
                if (pending) "pending" else if (failed) "failed" else "ok", legs, fee, coin.symbol,
                counterparty = if (from == me) (tx.optJSONObject("to")?.optString("hash") ?: "") else (tx.optJSONObject("from")?.optString("hash") ?: ""))
        }
        val tts = try { http.getObject("$base/token-transfers", mapOf("type" to "ERC-20")).optJSONArray("items")?.objects() ?: emptyList() } catch (e: HttpError) {
            if (e.code == 404) emptyList() else throw e
        }
        for (tt in tts.take(40)) runCatching {
            val tok = tt.optJSONObject("token") ?: return@runCatching
            val contract = tok.optString("address_hash").lowercase()
            val known = Chains.token("ethereum", contract)
            val rate = V.optional(tok.opt("exchange_rate"))
            val flagged = tok.optString("reputation").let { it.isNotEmpty() && it != "ok" }
            if (known == null && (rate == null || flagged)) return@runCatching
            val total = tt.optJSONObject("total") ?: return@runCatching
            val amount = units(total.opt("value"), decimalsOf(total.opt("decimals") ?: tok.opt("decimals")))
            if (amount <= 0) return@runCatching          // zero-value transfers: address-poisoning bait
            val from = tt.optJSONObject("from")?.optString("hash")?.lowercase() ?: ""
            val to = tt.optJSONObject("to")?.optString("hash")?.lowercase() ?: ""
            val sign = when { from == me && to != me -> -1.0; to == me && from != me -> 1.0; else -> return@runCatching }
            val leg = if (known != null) Leg(known.coin.id, known.coin.symbol, sign * amount)
                else Leg(syntheticId("ethereum", contract), tokenSymbol(tok.opt("symbol")), sign * amount)
            val hash = tt.optString("transaction_hash").take(80)
            val cp = if (sign < 0) tt.optJSONObject("to")?.optString("hash") ?: "" else tt.optJSONObject("from")?.optString("hash") ?: ""
            val prev = byHash[hash]
            byHash[hash] = prev?.copy(legs = prev.legs + leg, counterparty = prev.counterparty.ifEmpty { cp })
                ?: Transfer(w.chain, hash, iso(tt.optString("timestamp")), "ok", listOf(leg), counterparty = cp)
        }
        // Calls that moved nothing the user holds (approvals, failed swaps …) still show: they cost a fee.
        return byHash.values.filter { it.legs.isNotEmpty() || it.fee != null }
            .sortedWith(compareBy<Transfer> { it.ts != 0L }.thenByDescending { it.ts })
    }

    // ── EVM networks: JSON-RPC (native balance + known tokens) ───────────────

    private suspend fun rpcBalance(w: Wallet): WalletBalance {
        val urls = EVM_RPC[w.chain] ?: throw NotSupported(w.chain)
        val chainId = EVM_CHAIN_ID[w.chain] ?: throw NotSupported(w.chain)
        val coin = native(w)
        val tokens = Chains.TOKENS.filter { it.chain == w.chain }
        val addr = w.address.lowercase().removePrefix("0x")
        val batch = JSONArray()
        batch.put(JSONObject().put("jsonrpc", "2.0").put("id", 0).put("method", "eth_getBalance").put("params", JSONArray().put(w.address).put("latest")))
        tokens.forEachIndexed { i, t ->
            // balanceOf(address)
            val data = "0x70a08231" + "0".repeat(24) + addr
            batch.put(JSONObject().put("jsonrpc", "2.0").put("id", i + 1).put("method", "eth_call")
                .put("params", JSONArray().put(JSONObject().put("to", t.contract).put("data", data)).put("latest")))
        }
        // The node must say which chain it is: a mislabelled fallback must not report another chain's balance.
        batch.put(JSONObject().put("jsonrpc", "2.0").put("id", 9999).put("method", "eth_chainId").put("params", JSONArray()))
        val byId = firstOk(urls) { url ->
            val res = http.postJson(url, batch) as? JSONArray ?: throw NetError("rpc: expected a batch reply")
            val m = res.objects().associateBy { it.optInt("id", -1) }
            val id = m[9999]?.optString("result")?.removePrefix("0x")?.toLongOrNull(16)
            if (id != chainId) throw ValidationError("wrong chain from ${java.net.URI(url).host}")
            if (m[0]?.has("result") != true) throw NetError("rpc: ${m[0]?.optJSONObject("error")?.optString("message")?.take(80) ?: "no balance"}")
            m
        }
        fun result(id: Int): String {
            val r = byId[id] ?: throw ValidationError("rpc: missing reply $id")
            r.optJSONObject("error")?.let { throw NetError("rpc: ${it.optString("message").take(80)}") }
            return r.optString("result")
        }
        val assets = mutableListOf(Asset(coin.id, coin.symbol, coin.name, units(result(0), 18)))
        tokens.forEachIndexed { i, t ->
            val raw = runCatching { result(i + 1) }.getOrNull() ?: return@forEachIndexed
            val amount = runCatching { units(raw.ifEmpty { "0x0" }.let { if (it == "0x") "0x0" else it }, t.decimals) }.getOrNull() ?: return@forEachIndexed
            if (amount > 0) assets += Asset(t.coin.id, t.coin.symbol, t.coin.name, amount, contract = t.contract)
        }
        return WalletBalance(merge(assets), System.currentTimeMillis())
    }

    // ── Solana: JSON-RPC + Jupiter for token names and prices ─────────────────

    private suspend fun solRpc(method: String, params: JSONArray): Any? {
        val body = JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", method).put("params", params)
        return firstOk(SOLANA_RPC) { url ->
            val o = http.postJson(url, body) as? JSONObject ?: throw NetError("rpc: expected object")
            o.optJSONObject("error")?.let { throw NetError("solana: ${it.optString("message").take(80)}") }
            o.opt("result")
        }
    }

    private suspend fun solanaBalance(w: Wallet): WalletBalance {
        val coin = native(w)
        val bal = solRpc("getBalance", JSONArray().put(w.address)) as? JSONObject ?: throw ValidationError("solana: balance")
        val assets = mutableListOf(Asset(coin.id, coin.symbol, coin.name, units(bal.get("value"), 9)))
        // Per mint: raw amount and decimals, summed over all token accounts (classic and Token-2022).
        val mints = LinkedHashMap<String, Pair<BigInteger, Int>>()
        for (program in SPL_PROGRAMS) {
            val res = solRpc("getTokenAccountsByOwner", JSONArray().put(w.address).put(JSONObject().put("programId", program))
                .put(JSONObject().put("encoding", "jsonParsed"))) as? JSONObject ?: continue
            for (acc in res.optJSONArray("value")?.objects() ?: emptyList()) runCatching {
                val info = acc.getJSONObject("account").getJSONObject("data").getJSONObject("parsed").getJSONObject("info")
                val ta = info.getJSONObject("tokenAmount")
                val raw = BigInteger(ta.getString("amount"))
                if (raw.signum() <= 0) return@runCatching
                val mint = info.getString("mint").take(50)
                val dec = decimalsOf(ta.opt("decimals"))
                val prev = mints[mint]
                mints[mint] = (prev?.first?.add(raw) ?: raw) to dec
            }
        }
        var ignored = 0
        val unknown = mutableListOf<String>()
        for ((mint, rd) in mints) {
            val known = Chains.token("solana", mint)
            val amount = BigDecimal(rd.first).movePointLeft(rd.second).toDouble()
            if (known != null) assets += Asset(known.coin.id, known.coin.symbol, known.coin.name, amount, contract = mint)
            else unknown += mint
        }
        // Only the first mints are looked up: an address with thousands of dust tokens must not cost thousands of requests.
        val lookup = unknown.take(MAX_TOKEN_LOOKUPS)
        ignored += unknown.size - lookup.size
        val meta = HashMap<String, JSONObject>()
        for (chunk in lookup.chunked(20)) {
            runCatching {
                http.getArray("$JUPITER/tokens/v2/search", mapOf("query" to chunk.joinToString(","))).objects()
                    .forEach { meta[it.optString("id")] = it }
            }
        }
        for (mint in lookup) {
            val m = meta[mint]
            val rd = mints[mint]!!
            val amount = BigDecimal(rd.first).movePointLeft(rd.second).toDouble()
            val price = m?.let { V.optional(it.opt("usdPrice")) }
            val liquidity = m?.let { V.optional(it.opt("liquidity")) } ?: 0.0
            // A price is only worth something if the holding could be sold against the pools behind it.
            if (m == null || !m.optBoolean("isVerified") || price == null || price <= 0 || amount * price < DUST_USD ||
                liquidity < MIN_LIQUIDITY_USD || amount * price > liquidity) {
                ignored++
                continue
            }
            val sym = tokenSymbol(m.opt("symbol"))
            assets += Asset(syntheticId("solana", mint), sym, tokenName(m.opt("name"), sym), amount, price, mint)
        }
        return WalletBalance(merge(assets), System.currentTimeMillis(), ignored = ignored)
    }

    private suspend fun solanaActivity(w: Wallet): List<Transfer> {
        val coin = native(w)
        val sigs = solRpc("getSignaturesForAddress", JSONArray().put(w.address).put(JSONObject().put("limit", 15))) as? JSONArray
            ?: return emptyList()
        val out = mutableListOf<Transfer>()
        for (s in sigs.objects()) {
            val sig = s.optString("signature").take(100)
            if (sig.isEmpty()) continue
            val finalized = s.optString("confirmationStatus") == "finalized"
            val key = "sol:tx:${w.address}:$sig"
            // A finalized transaction never changes: fetch its details once, ever.
            val cached = cache.stale(key)?.second
            val t = if (cached != null) runCatching { Transfer.fromJson(JSONObject(cached)) }.getOrNull() else null
            if (t != null) {
                out += t
                continue
            }
            val detail = runCatching { solanaTx(w, coin, sig) }.getOrNull()
            val failed = !s.isNull("err")
            val ts = s.optLong("blockTime")
            val tr = (detail ?: Transfer(w.chain, sig, ts, "ok", emptyList())).copy(
                ts = ts, status = if (failed) "failed" else if (finalized) "ok" else "pending")
            if (detail != null && finalized) cache.put(key, tr.toJson().toString())
            out += tr
        }
        return out
    }

    private suspend fun solanaTx(w: Wallet, coin: Coin, sig: String): Transfer? {
        val tx = solRpc("getTransaction", JSONArray().put(sig).put(JSONObject().put("encoding", "jsonParsed")
            .put("maxSupportedTransactionVersion", 0))) as? JSONObject ?: return null
        val meta = tx.getJSONObject("meta")
        val keys = tx.getJSONObject("transaction").getJSONObject("message").getJSONArray("accountKeys").objects().map { it.optString("pubkey") }
        val idx = keys.indexOf(w.address)
        val legs = mutableListOf<Leg>()
        val feeLamports = V.number(meta.opt("fee")).toLong()
        val payer = idx == 0
        var other = ""
        if (idx >= 0) {
            val pre = meta.getJSONArray("preBalances").getLong(idx)
            val post = meta.getJSONArray("postBalances").getLong(idx)
            // The fee payer's change includes the fee; show the transfer and the fee apart.
            val delta = post - pre + (if (payer) feeLamports else 0)
            if (delta != 0L) legs += Leg(coin.id, coin.symbol, delta / 1e9)
            other = keys.firstOrNull { it != w.address && it.isNotEmpty() } ?: ""
        }
        fun tokenMap(arr: JSONArray?): Map<String, BigDecimal> {
            val m = HashMap<String, BigDecimal>()
            arr?.objects()?.forEach { b ->
                if (b.optString("owner") != w.address) return@forEach
                val ui = b.optJSONObject("uiTokenAmount") ?: return@forEach
                val v = runCatching { BigDecimal(ui.getString("amount")).movePointLeft(ui.getInt("decimals")) }.getOrNull() ?: return@forEach
                m[b.optString("mint")] = (m[b.optString("mint")] ?: BigDecimal.ZERO) + v
            }
            return m
        }
        val pre = tokenMap(meta.optJSONArray("preTokenBalances"))
        val post = tokenMap(meta.optJSONArray("postTokenBalances"))
        for (mint in pre.keys + post.keys) {
            val d = (post[mint] ?: BigDecimal.ZERO) - (pre[mint] ?: BigDecimal.ZERO)
            if (d.signum() == 0) continue
            val known = Chains.token("solana", mint)
            legs += if (known != null) Leg(known.coin.id, known.coin.symbol, d.toDouble())
                else Leg(syntheticId("solana", mint), Address.short(mint, 4, 0).removeSuffix("…"), d.toDouble())
        }
        return Transfer(w.chain, sig, tx.optLong("blockTime"), "ok", legs,
            if (payer) feeLamports / 1e9 else null, coin.symbol, counterparty = other)
    }

    // ── TRON: TronGrid ─────────────────────────────────────────────────────────

    private suspend fun tronBalance(w: Wallet): WalletBalance {
        val coin = native(w)
        val data = http.getObject("$TRONGRID/v1/accounts/${w.address}").optJSONArray("data")
        // An address that never received anything is not "activated" and has no record yet.
        val acc = data?.optJSONObject(0)
            ?: return WalletBalance(listOf(Asset(coin.id, coin.symbol, coin.name, 0.0)), System.currentTimeMillis(), 0)
        val assets = mutableListOf(Asset(coin.id, coin.symbol, coin.name, units(acc.opt("balance") ?: 0, 6)))
        var ignored = 0
        acc.optJSONArray("trc20")?.objects()?.forEach { entry ->
            for (contract in entry.keys()) {
                val known = Chains.token("tron", contract)
                val amount = runCatching { units(entry.get(contract), known?.decimals ?: 0) }.getOrNull() ?: continue
                if (known == null) {
                    if (amount > 0) ignored++
                    continue
                }
                if (amount > 0) assets += Asset(known.coin.id, known.coin.symbol, known.coin.name, amount, contract = contract)
            }
        }
        return WalletBalance(merge(assets), System.currentTimeMillis(), ignored = ignored)
    }

    private suspend fun tronActivity(w: Wallet): List<Transfer> {
        val coin = native(w)
        val out = LinkedHashMap<String, Transfer>()
        val trc = http.getObject("$TRONGRID/v1/accounts/${w.address}/transactions/trc20", mapOf("limit" to 30, "only_confirmed" to "true"))
        for (t in trc.optJSONArray("data")?.objects() ?: emptyList()) runCatching {
            val known = Chains.token("tron", t.optJSONObject("token_info")?.optString("address") ?: "") ?: return@runCatching
            val amount = units(t.opt("value"), known.decimals)
            if (amount <= 0) return@runCatching       // address-poisoning bait
            val from = t.optString("from")
            val to = t.optString("to")
            val sign = when { from == w.address && to != w.address -> -1.0; to == w.address && from != w.address -> 1.0; else -> return@runCatching }
            val id = t.optString("transaction_id").take(80)
            out[id] = Transfer(w.chain, id, t.optLong("block_timestamp") / 1000, "ok",
                listOf(Leg(known.coin.id, known.coin.symbol, sign * amount)), counterparty = if (sign < 0) to else from)
        }
        val txs = http.getObject("$TRONGRID/v1/accounts/${w.address}/transactions", mapOf("limit" to 30, "only_confirmed" to "true"))
        for (t in txs.optJSONArray("data")?.objects() ?: emptyList()) runCatching {
            val c = t.getJSONObject("raw_data").getJSONArray("contract").getJSONObject(0)
            if (c.optString("type") != "TransferContract") return@runCatching
            val v = c.getJSONObject("parameter").getJSONObject("value")
            val from = Address.tronFromHex(v.optString("owner_address")) ?: return@runCatching
            val to = Address.tronFromHex(v.optString("to_address")) ?: return@runCatching
            val amount = units(v.opt("amount"), 6)
            if (amount <= 0) return@runCatching
            val sign = when { from == w.address && to != w.address -> -1.0; to == w.address && from != w.address -> 1.0; else -> return@runCatching }
            val ok = t.optJSONArray("ret")?.optJSONObject(0)?.optString("contractRet")?.let { it == "SUCCESS" } ?: true
            val id = t.optString("txID").take(80)
            out[id] = Transfer(w.chain, id, t.optLong("block_timestamp") / 1000, if (ok) "ok" else "failed",
                listOf(Leg(coin.id, coin.symbol, sign * amount)), counterparty = if (sign < 0) to else from)
        }
        return out.values.sortedByDescending { it.ts }
    }

    /**
     * Whether a token priced only by an indexer may count. Airdropped meme tokens
     * often carry a "price" from one thin pool; billions of them would make the
     * portfolio look rich. So: a real market (cap and volume), and a holding
     * small enough against that market for the price to mean anything.
     */
    private fun tradable(valueUsd: Double, marketCap: Double?, volume24h: Double?): Boolean {
        if (valueUsd < DUST_USD) return false
        if (marketCap == null || marketCap < MIN_MARKET_CAP_USD) return false
        if (volume24h == null || volume24h < MIN_VOLUME_USD) return false
        return valueUsd <= marketCap * MAX_SHARE_OF_CAP
    }

    // ── Hyperliquid: info API (HyperCore) + HyperEVM RPC ─────────────────────

    private suspend fun hlInfo(type: String, user: String, extra: Map<String, Any> = emptyMap()): Any {
        val body = JSONObject().put("type", type).put("user", user)
        extra.forEach { (k, v) -> body.put(k, v) }
        return http.postJson(HL_INFO, body)
    }

    private suspend fun hyperliquidBalance(w: Wallet): WalletBalance {
        val coin = native(w)
        val user = w.address.lowercase()
        val sums = LinkedHashMap<String, Pair<Coin, Double>>()
        fun add(c: Coin, a: Double?) {
            if (a != null && a > 0) sums[c.id] = c to ((sums[c.id]?.second ?: 0.0) + a)
        }
        var ignored = 0
        // Spot balances: the main lookup — if this fails, the whole balance does.
        val spot = hlInfo("spotClearinghouseState", user) as? JSONObject ?: throw ValidationError("hyperliquid: spot")
        for (b in spot.optJSONArray("balances")?.objects() ?: emptyList()) {
            val a = V.optional(b.opt("total")) ?: continue
            val c = HL_SPOT[b.optString("coin")]
            if (c == null) { if (a > 0) ignored++; continue }
            add(c, a)
        }
        // Perp account: its value is USDC margin plus open profit/loss.
        runCatching {
            val perp = hlInfo("clearinghouseState", user) as JSONObject
            add(HL_SPOT.getValue("USDC"), V.optional(perp.optJSONObject("marginSummary")?.opt("accountValue")))
        }
        // Staked HYPE (delegated, not yet delegated, and on its way out of staking).
        runCatching {
            val st = hlInfo("delegatorSummary", user) as JSONObject
            add(coin, listOf("delegated", "undelegated", "totalPendingWithdrawal").sumOf { V.optional(st.opt(it)) ?: 0.0 })
        }
        // HYPE on HyperEVM, the same address on the EVM side.
        runCatching {
            val batch = JSONArray()
                .put(JSONObject().put("jsonrpc", "2.0").put("id", 0).put("method", "eth_getBalance").put("params", JSONArray().put(w.address).put("latest")))
                .put(JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "eth_chainId").put("params", JSONArray()))
            val m = (http.postJson(HYPER_EVM, batch) as JSONArray).objects().associateBy { it.optInt("id", -1) }
            if (m[1]?.optString("result")?.removePrefix("0x")?.toLongOrNull(16) == 999L) add(coin, units(m[0]?.optString("result"), 18))
        }
        val assets = sums.values.map { (c, a) -> Asset(c.id, c.symbol, c.name, a) }.ifEmpty { listOf(Asset(coin.id, coin.symbol, coin.name, 0.0)) }
        return WalletBalance(assets, System.currentTimeMillis(), ignored = ignored)
    }

    private suspend fun hyperliquidActivity(w: Wallet): List<Transfer> {
        val me = w.address.lowercase()
        val usdc = HL_SPOT.getValue("USDC")
        val start = System.currentTimeMillis() - 90L * 86_400_000
        val list = hlInfo("userNonFundingLedgerUpdates", me, mapOf("startTime" to start)) as? JSONArray ?: return emptyList()
        val out = mutableListOf<Transfer>()
        for (u in list.objects().takeLast(40).reversed()) runCatching {
            val d = u.getJSONObject("delta")
            val ts = u.optLong("time") / 1000
            val hash = u.optString("hash").take(80)
            val from = d.optString("user").lowercase()
            val to = d.optString("destination").lowercase()
            val fee = V.optional(d.opt("fee"))?.takeIf { it > 0 }
            val t = when (d.optString("type")) {
                "deposit" -> Transfer(w.chain, hash, ts, "ok", listOf(Leg(usdc.id, usdc.symbol, V.number(d.opt("usdc")))), counterparty = "Bridge")
                "withdraw" -> Transfer(w.chain, hash, ts, "ok", listOf(Leg(usdc.id, usdc.symbol, -V.number(d.opt("usdc")))), fee, usdc.symbol,
                    counterparty = "Bridge")
                "internalTransfer", "spotTransfer" -> {
                    val c = if (d.optString("type") == "internalTransfer") usdc else HL_SPOT[d.optString("token")] ?: return@runCatching
                    val amount = V.number(if (d.has("usdc")) d.opt("usdc") else d.opt("amount"))
                    if (amount <= 0) return@runCatching
                    val sign = when { from == me && to != me -> -1.0; to == me && from != me -> 1.0; else -> return@runCatching }
                    Transfer(w.chain, hash, ts, "ok", listOf(Leg(c.id, c.symbol, sign * amount)), if (sign < 0) fee else null, c.symbol,
                        counterparty = if (sign < 0) d.optString("destination") else d.optString("user"))
                }
                else -> return@runCatching
            }
            out += t
        }
        return out
    }

    /** One row per coin: the same coin can come from the native balance and a token list. */
    private fun merge(assets: List<Asset>): List<Asset> {
        val m = LinkedHashMap<String, Asset>()
        for (a in assets) {
            val p = m[a.coinId]
            m[a.coinId] = if (p == null) a else p.copy(amount = p.amount + a.amount, pending = p.pending + a.pending)
        }
        return m.values.toList()
    }

    companion object {
        const val TTL_ACTIVITY = 300.0
        const val DUST_USD = 0.01
        const val MAX_TOKEN_LOOKUPS = 100
        const val MIN_MARKET_CAP_USD = 1_000_000.0
        const val MIN_VOLUME_USD = 10_000.0
        const val MAX_SHARE_OF_CAP = 0.01
        const val MIN_LIQUIDITY_USD = 50_000.0

        private val ESPLORA = mapOf(
            // Blockstream first: mempool.space is blocked on some networks. Both serve the same API.
            "bitcoin" to listOf("https://blockstream.info/api", "https://mempool.space/api"),
            "litecoin" to listOf("https://litecoinspace.org/api"),
        )
        private const val BLOCKSCOUT = "https://eth.blockscout.com/api/v2"
        /** Public nodes per network, tried in order. */
        private val EVM_RPC = mapOf(
            "ethereum" to listOf("https://ethereum-rpc.publicnode.com", "https://1rpc.io/eth", "https://eth.drpc.org"),
            "base" to listOf("https://base-rpc.publicnode.com", "https://mainnet.base.org", "https://base.drpc.org"),
            "arbitrum" to listOf("https://arbitrum-one-rpc.publicnode.com", "https://arb1.arbitrum.io/rpc", "https://arbitrum.drpc.org"),
            "optimism" to listOf("https://optimism-rpc.publicnode.com", "https://mainnet.optimism.io", "https://optimism.drpc.org"),
            "polygon" to listOf("https://polygon-bor-rpc.publicnode.com", "https://polygon.drpc.org"),
        )
        private val EVM_CHAIN_ID = mapOf("ethereum" to 1L, "base" to 8453L, "arbitrum" to 42161L, "optimism" to 10L, "polygon" to 137L)
        private val SOLANA_RPC = listOf("https://api.mainnet-beta.solana.com", "https://solana-rpc.publicnode.com")
        private val SPL_PROGRAMS = listOf("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA", "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb")
        private const val JUPITER = "https://lite-api.jup.ag"
        private const val TRONGRID = "https://api.trongrid.io"
        private const val HL_INFO = "https://api.hyperliquid.xyz/info"
        private const val HYPER_EVM = "https://rpc.hyperliquid.xyz/evm"
        /** Hyperliquid spot tokens that stand for a coin (Unit-bridged BTC/ETH/SOL, USDT0). */
        private val HL_SPOT = mapOf(
            "HYPE" to Coin("hyperliquid", "HYPE", "Hyperliquid"), "USDC" to Coin("usd-coin", "USDC", "USDC"),
            "USDT0" to Coin("tether", "USDT", "Tether"), "UBTC" to Coin("bitcoin", "BTC", "Bitcoin"),
            "UETH" to Coin("ethereum", "ETH", "Ethereum"), "USOL" to Coin("solana", "SOL", "Solana"),
        )

        /** Hosts and the minimum gap between two requests to each, in seconds. */
        val HOSTS = mapOf(
            "blockstream.info" to 0.5, "mempool.space" to 0.5, "litecoinspace.org" to 0.5,
            "eth.blockscout.com" to 0.3,
            "ethereum-rpc.publicnode.com" to 0.2, "base-rpc.publicnode.com" to 0.2, "arbitrum-one-rpc.publicnode.com" to 0.2,
            "optimism-rpc.publicnode.com" to 0.2, "polygon-bor-rpc.publicnode.com" to 0.2,
            "1rpc.io" to 0.3, "mainnet.base.org" to 0.3, "arb1.arbitrum.io" to 0.3, "mainnet.optimism.io" to 0.3,
            "eth.drpc.org" to 0.3, "base.drpc.org" to 0.3, "arbitrum.drpc.org" to 0.3, "optimism.drpc.org" to 0.3, "polygon.drpc.org" to 0.3,
            "api.mainnet-beta.solana.com" to 0.35, "solana-rpc.publicnode.com" to 0.35, "lite-api.jup.ag" to 1.0,
            "api.trongrid.io" to 0.5, "api.hyperliquid.xyz" to 0.3, "rpc.hyperliquid.xyz" to 0.3,
        )
    }
}

/** Keeps the user's watched addresses in wallets.json, written atomically like the profile. */
class WalletStore(private val file: java.io.File) {
    fun load(): List<Wallet> {
        if (!file.exists()) return emptyList()
        return try {
            JSONObject(file.readText()).optJSONArray("wallets")?.objects()?.mapNotNull { o ->
                runCatching { Wallet.fromJson(o) }.getOrNull()?.takeIf { Chains.of(it.chain) != null }
            } ?: emptyList()
        } catch (e: Exception) {
            file.renameTo(java.io.File(file.parentFile, file.name + ".broken"))
            emptyList()
        }
    }

    fun save(list: List<Wallet>) {
        file.parentFile?.mkdirs()
        val tmp = java.io.File(file.parentFile, file.name + ".tmp")
        tmp.writeText(JSONObject().put("version", 1).put("wallets", JSONArray().apply { list.forEach { put(it.toJson()) } }).toString())
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    companion object {
        const val MAX_WALLETS = 60

        fun newId(): String {
            val b = ByteArray(8)
            java.security.SecureRandom().nextBytes(b)
            return b.joinToString("") { "%02x".format(it) }
        }
    }
}
