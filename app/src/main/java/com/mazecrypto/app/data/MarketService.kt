package com.mazecrypto.app.data

import com.mazecrypto.app.core.Candle
import com.mazecrypto.app.core.Coin
import com.mazecrypto.app.core.CoinRow
import com.mazecrypto.app.core.Interval
import com.mazecrypto.app.core.MarketInfo
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * One refresh = fresh data where possible, cached data otherwise (port of
 * providers/service.py).
 *
 * * Fiat price: the user's exchanges (in their order) listing SYM/<fiat>,
 *   otherwise USD price × exchange rate.
 * * USD price: the user's exchanges listing SYM/USD|USDT|USDC, else CoinGecko.
 * * Candles: the user's exchanges, then other exchanges with candles, then CoinGecko.
 * * Anything that fails falls back to its last cached value, flagged stale.
 *
 * An exchange price that disagrees with CoinGecko by more than 25 % is
 * ignored: that is almost always two different tokens sharing a ticker.
 */
class MarketService(private val settings: Settings, private val cache: Cache) {
    val http = Http(allowedHosts(), if (settings.proxyEnabled) settings.proxyUrl else null)
    /** Wallet lookups share the same HTTP layer: allow-list, proxy and per-host spacing. */
    val wallets = WalletService(http, cache)
    private val exchanges: List<Exchange> = settings.exchanges.mapNotNull { EXCHANGES[it]?.invoke() }
    private val chartSources: List<Exchange> = exchanges.filter { it.hasKlines } +
        EXCHANGES.filterKeys { id -> exchanges.none { it.id == id } }.values.map { it() }.filter { it.hasKlines }
    private val tickers = ConcurrentHashMap<String, Map<Pair2, Double>>()
    @Volatile var fx: Map<String, Double> = mapOf("USD" to 1.0)
        private set
    private val noPair = ConcurrentHashMap.newKeySet<String>()

    /** Per-call bookkeeping: what failed and how old the oldest cached fallback was. */
    private class Track(val cacheOnly: Boolean = false) {
        val errors = mutableListOf<String>()
        var oldest: Double? = null      // seconds
        var freshAny = false

        @Synchronized fun stale(ageS: Double) { oldest = maxOf(oldest ?: 0.0, ageS) }
        @Synchronized fun error(e: Throwable) { errors += (e.message ?: e.javaClass.simpleName) }
    }

    private suspend fun cached(key: String, ttl: Double, trk: Track, fetch: suspend () -> String): String {
        cache.fresh(key, ttl)?.let { trk.freshAny = true; return it }
        if (trk.cacheOnly) {
            val (ts, v) = cache.stale(key) ?: throw NetError("not cached")
            trk.stale((System.currentTimeMillis() - ts) / 1000.0)
            return v
        }
        val value = try {
            fetch()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            val (ts, v) = cache.stale(key) ?: throw e
            trk.error(e)
            trk.stale((System.currentTimeMillis() - ts) / 1000.0)
            return v
        }
        cache.put(key, value)
        trk.freshAny = true
        return value
    }

    // ── pieces ─────────────────────────────────────────────────────────────
    private suspend fun loadFx(trk: Track): Map<String, Double> {
        val wanted = setOf(settings.fiat) + setOf(settings.secondaryFiat).filter { it.isNotEmpty() }
        val raw = cached("fx:" + wanted.sorted().joinToString(","), TTL_FX, trk) {
            val (rates, errs) = Fx.fetchRates(http, wanted)
            if ((wanted - rates.keys).isNotEmpty()) throw NetError(errs.joinToString("; ").ifEmpty { "exchange rate unavailable" })
            JSONObject(rates as Map<*, *>).toString()
        }
        val o = JSONObject(raw)
        fx = fx + o.keys().asSequence().associateWith { o.getDouble(it) }
        return fx
    }

    private suspend fun markets(ids: List<String>, trk: Track): Map<String, MarketInfo> {
        val raw = cached("cg:markets7:" + ids.sorted().joinToString(","), maxOf(30.0, settings.refreshSeconds - 5.0), trk) {
            JSONArray().apply { CoinGecko.markets(http, ids).values.forEach { put(it.toJson()) } }.toString()
        }
        return JSONArray(raw).objects().map { MarketInfo.fromJson(it) }.associateBy { it.id }
    }

    private suspend fun loadTickers(ex: Exchange, trk: Track): Map<Pair2, Double> {
        val raw = cached("tk:${ex.id}", maxOf(15.0, settings.refreshSeconds - 5.0), trk) {
            JSONObject().apply { ex.tickers(http).forEach { (k, v) -> put("${k.first}/${k.second}", v) } }.toString()
        }
        val o = JSONObject(raw)
        val out = HashMap<Pair2, Double>(o.length())
        for (k in o.keys()) {
            val i = k.indexOf('/')
            if (i > 0) out[k.substring(0, i) to k.substring(i + 1)] = o.getDouble(k)
        }
        tickers[ex.id] = out
        return out
    }

    private fun pick(sym: String, quotes: List<String>, reference: Double?): Pair<Double, String>? {
        for (ex in exchanges) {
            val book = tickers[ex.id] ?: continue
            for (q in quotes) {
                val p = book[sym to q] ?: continue
                if (sym == q) continue
                if (reference != null && abs(p / reference - 1) > SANITY) continue
                return p to ex.name
            }
        }
        return null
    }

    // ── refresh ────────────────────────────────────────────────────────────
    /**
     * Prices and market data for [watchlist]: a handful of requests in parallel.
     * [cacheOnly] builds the snapshot without touching the network, so the
     * screen has numbers the moment it opens.
     */
    suspend fun refresh(watchlist: List<Coin>, cacheOnly: Boolean = false): Snapshot = coroutineScope {
        val trk = Track(cacheOnly)
        val fiat = settings.fiat
        val fxJob = async { runCatching { loadFx(trk) }.onFailure { trk.error(it) } }
        val mkJob = async { runCatching { markets(watchlist.map { it.id }, trk) }.onFailure { trk.error(it) }.getOrNull() }
        val tkJobs = exchanges.map { ex -> async { runCatching { loadTickers(ex, trk) }.onFailure { trk.error(it) } } }
        fxJob.await()
        val markets = mkJob.await() ?: emptyMap()
        tkJobs.forEach { it.await() }
        val rate = fx[fiat]
        val now = System.currentTimeMillis()
        val rows = watchlist.map { coin ->
            val m = markets[coin.id]
            var priceUsd: Double? = null
            var usdSource = ""
            var priceFiat: Double? = null
            var fiatSource = ""
            val refUsd = m?.priceUsd
            val usd = pick(coin.symbol, USD_QUOTES, refUsd)
            if (usd != null) {
                priceUsd = usd.first; usdSource = usd.second
            } else if (m != null) {
                priceUsd = m.priceUsd; usdSource = "CoinGecko"
            }
            if (fiat == "USD") {
                priceFiat = priceUsd; fiatSource = usdSource
            } else {
                val refFiat = if (refUsd != null && rate != null) refUsd * rate else null
                val hit = pick(coin.symbol, listOf(fiat), refFiat)
                if (hit != null) {
                    priceFiat = hit.first; fiatSource = hit.second
                } else if (priceUsd != null && rate != null) {
                    priceFiat = priceUsd * rate; fiatSource = "$usdSource × FX"
                }
            }
            var ath = m?.athChangePct
            if (m?.athUsd != null && priceUsd != null) {
                // Recompute against the live price so ATH distance matches the shown price.
                ath = (priceUsd / m.athUsd - 1) * 100
            }
            CoinRow(
                coin, priceUsd, priceFiat, usdSource, fiatSource,
                m?.change1h, m?.change24h, m?.change7d, m?.change30d, ath, m?.marketCap, m?.volume24h,
                m?.high24h, m?.low24h, stale = trk.oldest != null, updated = now, spark = m?.spark7d ?: emptyList(),
            )
        }
        Snapshot(rows, fx, now, trk.oldest, !trk.freshAny, trk.errors.toList())
    }

    /** Daily USD closes: Binance first (fast), CoinGecko otherwise. Cached for hours. */
    suspend fun daily(coin: Coin, days: Int = 31, refUsd: Double? = null): List<Candle> {
        val trk = Track()
        val raw = cached("daily:${coin.id}:$days", TTL_DAILY, trk) {
            val cs = try {
                if ("binance:${coin.symbol}USDT" in noPair) throw NotSupported(coin.symbol)
                val k = Binance().klines(http, coin.symbol, "USDT", Interval.D1, days)
                if (!plausible(k, refUsd)) throw NotSupported("${coin.symbol}USDT looks delisted or mismatched")
                k
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                noPair += "binance:${coin.symbol}USDT"
                CoinGecko.dailyCloses(http, coin.id, days)
            }
            Candle.listToJson(cs)
        }
        // Align to UTC midnight so series from different sources match day by day.
        return V.candles(Candle.listFromJson(raw).map { it.copy(ts = it.ts / 86400 * 86400) })
    }

    /**
     * Chart candles in the portfolio currency (`fiat`) or in USD (`usd`).
     * [refUsd] (the current USD price) lets frozen or mismatched pairs be skipped.
     */
    suspend fun candles(coin: Coin, quoteMode: String, interval: Interval, limit: Int = 300, refUsd: Double? = null): CandleSet {
        val trk = Track()
        val fiat = settings.fiat
        val quotes = if (quoteMode == "fiat" && fiat != "USD") listOf(fiat) else listOf("USDT", "USD", "USDC")
        val ttl = (interval.seconds / 6).coerceIn(30, 600).toDouble()
        for (ex in chartSources) {
            val book = tickers[ex.id]
            for (q in quotes) {
                val tag = "${ex.id}:${coin.symbol}$q:${interval.key}"
                if (tag in noPair || (book != null && (coin.symbol to q) !in book)) continue
                val ref = if (q in USD_QUOTES) refUsd else fx[q]?.let { r -> refUsd?.times(r) }
                val raw = try {
                    cached("k:$tag:$limit", ttl, trk) {
                        val cs = ex.klines(http, coin.symbol, q, interval, limit)
                        if (!plausible(cs, ref)) throw ValidationError("${ex.name} ${coin.symbol}$q: implausible candles")
                        Candle.listToJson(cs)
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    noPair += tag
                    continue
                }
                return CandleSet(Candle.listFromJson(raw), ex.name, if (q in USD_QUOTES) "USD" else q, trk.oldest)
            }
        }
        if (quoteMode == "fiat" && fiat != "USD") {
            val usd = candles(coin, "usd", interval, limit, refUsd)
            val rate = fx[fiat] ?: return usd
            val conv = usd.candles.map { Candle(it.ts, it.open * rate, it.high * rate, it.low * rate, it.close * rate, it.volume) }
            return CandleSet(conv, usd.source, fiat, usd.staleAge, converted = true)
        }
        val raw = cached("cg:ohlc:${coin.id}:${interval.key}", ttl, trk) {
            Candle.listToJson(CoinGecko.ohlc(http, coin.id, interval))
        }
        return CandleSet(Candle.listFromJson(raw), "CoinGecko", "USD", trk.oldest)
    }

    suspend fun search(query: String): List<Pair<Coin, Int?>> {
        val q = query.trim().lowercase()
        val raw = cached("search:$q", TTL_SEARCH, Track()) {
            JSONArray().apply {
                CoinGecko.search(http, q).forEach { (c, rank) ->
                    put(JSONArray().put(c.id).put(c.symbol).put(c.name).put(rank ?: JSONObject.NULL))
                }
            }.toString()
        }
        return JSONArray(raw).arrays().map { Coin(it.getString(0), it.getString(1), it.getString(2)) to (if (it.isNull(3)) null else it.getInt(3)) }
    }

    /** Test the configured proxy with one small request. */
    suspend fun testConnection() {
        http.get("${CoinGecko.API}/ping")
    }

    companion object {
        const val SANITY = 0.25
        const val TTL_FX = 3600.0
        const val TTL_DAILY = 6 * 3600.0
        const val TTL_SEARCH = 86400.0

        fun allowedHosts(): Map<String, Double> {
            val hosts = HashMap<String, Double>()
            hosts += CoinGecko.HOSTS
            hosts += Fx.HOSTS
            hosts += WalletService.HOSTS
            EXCHANGE_INFO.forEach { hosts += it.hosts }
            return hosts
        }

        /** False for frozen or mismatched series (delisted pairs, ticker collisions). */
        fun plausible(candles: List<Candle>, reference: Double?): Boolean {
            if (candles.size < 2) return false
            if (reference != null && abs(candles.last().close / reference - 1) > SANITY) return false
            return candles.takeLast(10).map { it.close }.toSet().size > 1
        }
    }
}

class Snapshot(
    val rows: List<CoinRow>,
    val fx: Map<String, Double>,
    val ts: Long,                       // epoch ms
    val staleAge: Double? = null,       // seconds: age of the oldest cached part used, null = all fresh
    val offline: Boolean = false,       // nothing at all came from the network
    val errors: List<String> = emptyList(),
) {
    fun row(coinId: String): CoinRow? = rows.firstOrNull { it.coin.id == coinId }
}

class CandleSet(
    val candles: List<Candle>,
    val source: String,
    val quote: String,
    val staleAge: Double? = null,
    val converted: Boolean = false,     // USD candles multiplied by an exchange rate
)
