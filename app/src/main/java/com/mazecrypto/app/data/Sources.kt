package com.mazecrypto.app.data

import android.util.Xml
import com.mazecrypto.app.core.Candle
import com.mazecrypto.app.core.Coin
import com.mazecrypto.app.core.Interval
import com.mazecrypto.app.core.MarketInfo
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/** CoinGecko public API: market data, coin search, daily history. */
object CoinGecko {
    const val API = "https://api.coingecko.com/api/v3"
    // The keyless tier allows only a few calls a minute in practice; space them out.
    val HOSTS = mapOf("api.coingecko.com" to 4.0)

    private fun market(row: JSONObject): MarketInfo {
        fun o(k: String, neg: Boolean = false) = V.optional(row.opt(k), allowNegative = neg)
        val spark = row.optJSONObject("sparkline_in_7d")?.optJSONArray("price")
        val sparkList = if (spark == null) emptyList() else (0 until spark.length()).mapNotNull { i ->
            V.optional(spark.opt(i))?.takeIf { it > 0 }
        }
        return MarketInfo(
            id = V.coinId(row.get("id")), symbol = V.symbol(row.get("symbol")), name = row.optString("name").take(60),
            priceUsd = V.price(row.opt("current_price")),
            marketCap = o("market_cap"), athUsd = o("ath"), athChangePct = o("ath_change_percentage", true),
            change1h = o("price_change_percentage_1h_in_currency", true),
            change24h = o("price_change_percentage_24h_in_currency", true),
            change7d = o("price_change_percentage_7d_in_currency", true),
            change30d = o("price_change_percentage_30d_in_currency", true),
            volume24h = o("total_volume"), high24h = o("high_24h"), low24h = o("low_24h"),
            spark7d = sparkList,
        )
    }

    /**
     * One call for the whole list: prices, 1h/24h/7d/30d change and a 7-day
     * hourly sparkline. This is what keeps the widgets cheap to refresh.
     */
    suspend fun markets(http: Http, ids: List<String>): Map<String, MarketInfo> {
        val out = LinkedHashMap<String, MarketInfo>()
        val clean = ids.map { V.coinId(it) }
        for (chunk in clean.chunked(250)) {
            val rows = http.getArray("$API/coins/markets", mapOf(
                "vs_currency" to "usd", "ids" to chunk.joinToString(","), "per_page" to 250,
                "sparkline" to "true", "price_change_percentage" to "1h,24h,7d,30d"))
            for (row in rows.objects()) {
                runCatching { market(row) }.getOrNull()?.let { out[it.id] = it }
            }
        }
        return out
    }

    /** Coins matching [query] with their market-cap rank (best first). */
    suspend fun search(http: Http, query: String): List<Pair<Coin, Int?>> {
        val q = query.trim().take(40)
        if (q.isEmpty()) return emptyList()
        val coins = http.getObject("$API/search", mapOf("query" to q)).optJSONArray("coins") ?: return emptyList()
        return coins.objects().take(25).mapNotNull { row ->
            runCatching {
                Coin(V.coinId(row.get("id")), V.symbol(row.get("symbol")), row.optString("name").take(60)) to
                    (row.opt("market_cap_rank") as? Int)
            }.getOrNull()
        }
    }

    /** Daily closing prices in USD as flat candles. */
    suspend fun dailyCloses(http: Http, coinId: String, days: Int = 31): List<Candle> {
        val data = http.getObject("$API/coins/${V.coinId(coinId)}/market_chart",
            mapOf("vs_currency" to "usd", "days" to days, "interval" to "daily"))
        val prices = data.optJSONArray("prices") ?: throw ValidationError("coingecko: no prices")
        val rows = prices.arrays().mapNotNull { r ->
            runCatching {
                val px = V.price(r.get(1))
                Candle(V.number(r.get(0)).toLong() / 1000 / 86400 * 86400, px, px, px, px, 0.0)
            }.getOrNull()
        }
        return V.candles(rows)
    }

    /** Last-resort chart data. CoinGecko picks candle width from `days`. */
    suspend fun ohlc(http: Http, coinId: String, interval: Interval): List<Candle> {
        val days = when (interval) { Interval.H1 -> 1; Interval.H4 -> 30; else -> 365 }
        val rows = http.getArray("$API/coins/${V.coinId(coinId)}/ohlc", mapOf("vs_currency" to "usd", "days" to days))
        return V.candles(rows.arrays().map { V.candle(it.get(0), it.get(1), it.get(2), it.get(3), it.get(4), 0) })
    }
}

/**
 * Fiat exchange rates, expressed as units of currency per 1 USD: TCMB for
 * TRY, ECB reference rates for the other majors, CoinGecko's USDT price as a
 * last resort for anything else.
 */
object Fx {
    val HOSTS = mapOf("www.tcmb.gov.tr" to 2.0, "www.ecb.europa.eu" to 2.0)
    private const val ECB_URL = "https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml"
    private const val TCMB_URL = "https://www.tcmb.gov.tr/kurlar/today.xml"

    private fun parser(text: String): XmlPullParser {
        if ("<!DOCTYPE" in text || "<!ENTITY" in text) throw ValidationError("refusing XML with DTD")
        return Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(StringReader(text))
        }
    }

    fun parseEcb(text: String): Map<String, Double> {
        val perEur = hashMapOf("EUR" to 1.0)
        val p = parser(text)
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG && p.name.endsWith("Cube")) {
                val cur = p.getAttributeValue(null, "currency") ?: continue
                runCatching { perEur[V.fiat(cur)] = V.price(p.getAttributeValue(null, "rate")) }
            }
        }
        val usd = perEur["USD"] ?: throw ValidationError("ECB: no USD rate")
        return perEur.mapValues { it.value / usd }
    }

    /** TRY per USD (mid of forex buying/selling). */
    fun parseTcmb(text: String): Map<String, Double> {
        val p = parser(text)
        var inUsd = false
        var buy: Double? = null
        var sell: Double? = null
        var unit = 1.0
        var tag = ""
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> {
                    tag = p.name
                    if (p.name == "Currency") inUsd = p.getAttributeValue(null, "CurrencyCode") == "USD"
                }
                XmlPullParser.TEXT -> if (inUsd) {
                    val v = p.text.trim()
                    if (v.isNotEmpty()) when (tag) {
                        "ForexBuying" -> buy = V.price(v, "ForexBuying")
                        "ForexSelling" -> sell = V.price(v, "ForexSelling")
                        "Unit" -> unit = V.price(v, "Unit")
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (p.name == "Currency" && inUsd) {
                        val b = buy ?: break
                        val s = sell ?: break
                        return mapOf("TRY" to (b + s) / 2 / unit, "USD" to 1.0)
                    }
                    tag = ""
                }
            }
        }
        throw ValidationError("TCMB: no USD")
    }

    /** Rates for [wanted] currencies and the list of sources that failed. */
    suspend fun fetchRates(http: Http, wanted: Set<String>): Pair<Map<String, Double>, List<String>> {
        val rates = hashMapOf("USD" to 1.0)
        val errors = mutableListOf<String>()
        val need = wanted - "USD"
        if ("TRY" in need) {
            try { rates.putAll(parseTcmb(http.getText(TCMB_URL))) } catch (e: Exception) { errors += "TCMB: ${e.message}" }
        }
        if ((need - rates.keys).isNotEmpty()) {
            try {
                parseEcb(http.getText(ECB_URL)).forEach { (k, v) -> if (k !in rates) rates[k] = v }
            } catch (e: Exception) { errors += "ECB: ${e.message}" }
        }
        val missing = need - rates.keys
        if (missing.isNotEmpty()) {
            try {
                val data = http.getObject("${CoinGecko.API}/simple/price", mapOf(
                    "ids" to "tether", "vs_currencies" to missing.sorted().joinToString(",") { it.lowercase() }))
                val tether = data.optJSONObject("tether") ?: throw ValidationError("no tether")
                for (c in tether.keys()) rates[V.fiat(c)] = V.price(tether.get(c))
            } catch (e: Exception) { errors += "CoinGecko FX: ${e.message}" }
        }
        return rates to errors
    }
}
