package com.mazecrypto.app.data

import com.mazecrypto.app.core.Candle
import com.mazecrypto.app.core.Interval

/** (base symbol, quote symbol), e.g. BTC/TRY. */
typealias Pair2 = Pair<String, String>

/** Quotes treated as US dollars when looking for a USD price. */
val USD_QUOTES = listOf("USD", "USDT", "USDC")

/**
 * An exchange price source (port of providers/exchanges): all of its tickers
 * in one request where the API allows it, and optionally candles for a pair.
 */
abstract class Exchange {
    abstract val id: String
    abstract val name: String
    abstract val country: String
    abstract val hosts: Map<String, Double>       // host -> minimum spacing between requests (s)
    open val hasKlines: Boolean = true

    abstract suspend fun tickers(http: Http): Map<Pair2, Double>

    open suspend fun klines(http: Http, base: String, quote: String, interval: Interval, limit: Int): List<Candle> =
        throw NotSupported(id)
}

class Binance : Exchange() {
    override val id = "binance"
    override val name = "Binance"
    override val country = "Global"
    override val hosts = mapOf("api.binance.com" to 0.25)

    override suspend fun tickers(http: Http): Map<Pair2, Double> {
        val out = HashMap<Pair2, Double>()
        for (row in http.getArray("$API/ticker/price").objects()) {
            runCatching {
                val sym = row.getString("symbol")
                val quote = QUOTES.first { sym.endsWith(it) && sym.length > it.length }
                out[sym.dropLast(quote.length) to quote] = V.price(row.get("price"))
            }
        }
        return out
    }

    override suspend fun klines(http: Http, base: String, quote: String, interval: Interval, limit: Int): List<Candle> {
        val rows = http.getArray("$API/klines", mapOf(
            "symbol" to V.symbol(base) + V.symbol(quote), "interval" to interval.key, "limit" to minOf(limit, 1000)))
        return V.candles(rows.arrays().map { V.candle(it.get(0), it.get(1), it.get(2), it.get(3), it.get(4), it.get(5)) })
    }

    companion object {
        const val API = "https://api.binance.com/api/v3"
        // Longest first so "FDUSD" wins over "USD"-like suffixes.
        val QUOTES = listOf("FDUSD", "USDT", "USDC", "TRY", "EUR", "BRL", "JPY", "PLN", "MXN", "ARS", "ZAR", "UAH", "COP",
            "BTC", "ETH", "BNB")
    }
}

class BtcTurk : Exchange() {
    override val id = "btcturk"
    override val name = "BtcTurk"
    override val country = "TR"
    override val hosts = mapOf("api.btcturk.com" to 0.5, "graph-api.btcturk.com" to 0.5)

    override suspend fun tickers(http: Http): Map<Pair2, Double> {
        val out = HashMap<Pair2, Double>()
        val data = http.getObject("https://api.btcturk.com/api/v2/ticker").optJSONArray("data")
            ?: throw ValidationError("btcturk: no data")
        for (row in data.objects()) {
            runCatching { out[V.symbol(row.get("numeratorSymbol")) to V.symbol(row.get("denominatorSymbol"))] = V.price(row.get("last")) }
        }
        return out
    }

    override suspend fun klines(http: Http, base: String, quote: String, interval: Interval, limit: Int): List<Candle> {
        val now = System.currentTimeMillis() / 1000
        val res = mapOf(Interval.H1 to 60, Interval.H4 to 240, Interval.D1 to 1440, Interval.W1 to 10080)
        val data = http.getObject("https://graph-api.btcturk.com/v1/klines/history", mapOf(
            "symbol" to V.symbol(base) + V.symbol(quote), "resolution" to res.getValue(interval),
            "from" to now - interval.seconds * limit, "to" to now))
        if (data.optString("s") != "ok") throw ValidationError("btcturk: no data")
        val cols = listOf("t", "o", "h", "l", "c", "v").map { data.optJSONArray(it) ?: throw ValidationError("btcturk: missing $it") }
        if (cols.map { it.length() }.toSet().size != 1) throw ValidationError("btcturk: ragged columns")
        val rows = (0 until cols[0].length()).map { i -> V.candle(cols[0].get(i), cols[1].get(i), cols[2].get(i), cols[3].get(i), cols[4].get(i), cols[5].get(i)) }
        return V.candles(rows).takeLast(limit)
    }
}

class Coinbase : Exchange() {
    override val id = "coinbase"
    override val name = "Coinbase"
    override val country = "US · EU · UK"
    override val hosts = mapOf("api.coinbase.com" to 0.5, "api.exchange.coinbase.com" to 0.4)

    override suspend fun tickers(http: Http): Map<Pair2, Double> {
        val out = HashMap<Pair2, Double>()
        // Reference rates exist for every fiat, but only these are real order-book markets.
        for (q in listOf("USD", "EUR", "GBP")) {
            val rates = http.getObject("https://api.coinbase.com/v2/exchange-rates", mapOf("currency" to q))
                .optJSONObject("data")?.optJSONObject("rates") ?: throw ValidationError("coinbase: no rates")
            for (sym in rates.keys()) {
                runCatching { out[V.symbol(sym) to q] = 1.0 / V.price(rates.get(sym)) }
            }
        }
        return out
    }

    override suspend fun klines(http: Http, base: String, quote: String, interval: Interval, limit: Int): List<Candle> {
        val src = when (interval) { Interval.H1, Interval.H4 -> Interval.H1; else -> Interval.D1 }
        val rows = http.getArray("https://api.exchange.coinbase.com/products/${V.symbol(base)}-${V.symbol(quote)}/candles",
            mapOf("granularity" to src.seconds))
        // Coinbase rows: [time, low, high, open, close, volume], newest first.
        var candles = V.candles(rows.arrays().map { V.candle(it.get(0), it.get(3), it.get(2), it.get(1), it.get(4), it.get(5)) })
        if (src != interval) candles = resample(candles, interval.seconds)
        return candles.takeLast(limit)
    }
}

class Kraken : Exchange() {
    override val id = "kraken"
    override val name = "Kraken"
    override val country = "Global · EU · US"
    override val hosts = mapOf("api.kraken.com" to 1.0)
    private var pairs: Map<String, Pair2> = emptyMap()
    private var names: Map<Pair2, String> = emptyMap()
    private var pairsTs = 0L

    private fun norm(s: String) = ALIASES[s] ?: s

    private suspend fun loadPairs(http: Http) {
        if (pairs.isNotEmpty() && System.currentTimeMillis() - pairsTs < 86_400_000) return
        val res = http.getObject("$API/AssetPairs").optJSONObject("result") ?: throw ValidationError("kraken: no pairs")
        val out = HashMap<String, Pair2>()
        for (name in res.keys()) {
            val ws = res.optJSONObject(name)?.optString("wsname") ?: continue
            if ("/" !in ws) continue
            val (b, q) = ws.split("/", limit = 2)
            runCatching { out[name] = V.symbol(norm(b)) to V.symbol(norm(q)) }
        }
        pairs = out
        names = out.entries.associate { it.value to it.key }
        pairsTs = System.currentTimeMillis()
    }

    override suspend fun tickers(http: Http): Map<Pair2, Double> {
        loadPairs(http)
        val res = http.getObject("$API/Ticker").optJSONObject("result") ?: throw ValidationError("kraken: no tickers")
        val out = HashMap<Pair2, Double>()
        for (name in res.keys()) {
            val pair = pairs[name] ?: continue
            runCatching { out[pair] = V.price(res.getJSONObject(name).getJSONArray("c").get(0)) }
        }
        return out
    }

    override suspend fun klines(http: Http, base: String, quote: String, interval: Interval, limit: Int): List<Candle> {
        loadPairs(http)
        val name = names[base to quote] ?: throw ValidationError("kraken: unknown pair")
        val minutes = mapOf(Interval.H1 to 60, Interval.H4 to 240, Interval.D1 to 1440, Interval.W1 to 10080)
        val res = http.getObject("$API/OHLC", mapOf("pair" to name, "interval" to minutes.getValue(interval)))
            .optJSONObject("result") ?: throw ValidationError("kraken: no candles")
        val rows = res.optJSONArray(name) ?: throw ValidationError("kraken: no candles")
        return V.candles(rows.arrays().map { V.candle(it.get(0), it.get(1), it.get(2), it.get(3), it.get(4), it.get(6)) }).takeLast(limit)
    }

    companion object {
        const val API = "https://api.kraken.com/0/public"
        val ALIASES = mapOf("XBT" to "BTC", "XDG" to "DOGE")
    }
}

class Paribu : Exchange() {
    override val id = "paribu"
    override val name = "Paribu"
    override val country = "TR"
    override val hosts = mapOf("www.paribu.com" to 1.0)
    override val hasKlines = false

    override suspend fun tickers(http: Http): Map<Pair2, Double> {
        val data = http.getObject("https://www.paribu.com/ticker")
        val out = HashMap<Pair2, Double>()
        for (name in data.keys()) {
            if ("_" !in name) continue
            val row = data.optJSONObject(name) ?: continue
            val (b, q) = name.split("_", limit = 2)
            runCatching { out[V.symbol(b) to V.symbol(if (q.equals("TL", true)) "TRY" else q)] = V.price(row.get("last")) }
        }
        return out
    }
}

/** Order here is the fallback order for chart data when the user's own exchanges do not list a pair. */
val EXCHANGES: Map<String, () -> Exchange> = linkedMapOf(
    "binance" to ::Binance, "kraken" to ::Kraken, "coinbase" to ::Coinbase, "btcturk" to ::BtcTurk, "paribu" to ::Paribu,
)

val EXCHANGE_INFO: List<Exchange> = EXCHANGES.values.map { it() }

private const val WEEK_OFFSET = 4 * 86400L   // unix epoch was a Thursday; weeks start on Monday

/** Merge candles into [seconds]-wide buckets (e.g. 1h -> 4h, 1d -> 1w). */
fun resample(candles: List<Candle>, seconds: Long): List<Candle> {
    val out = ArrayList<Candle>()
    val offset = if (seconds == 604800L) WEEK_OFFSET else 0L
    for (c in candles) {
        val start = Math.floorDiv(c.ts - offset, seconds) * seconds + offset
        val p = out.lastOrNull()
        if (p != null && p.ts == start) {
            out[out.size - 1] = Candle(start, p.open, maxOf(p.high, c.high), minOf(p.low, c.low), c.close, p.volume + c.volume)
        } else {
            out += Candle(start, c.open, c.high, c.low, c.close, c.volume)
        }
    }
    return out
}

