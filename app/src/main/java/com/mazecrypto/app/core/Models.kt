package com.mazecrypto.app.core

import org.json.JSONArray
import org.json.JSONObject

val STABLECOINS = setOf("USDT", "USDC", "DAI", "FDUSD", "TUSD", "USDE", "PYUSD")

enum class Interval(val key: String, val seconds: Long) {
    H1("1h", 3600), H4("4h", 14400), D1("1d", 86400), W1("1w", 604800);

    companion object {
        fun of(key: String): Interval = entries.firstOrNull { it.key == key } ?: D1
    }
}

/** A coin as the user tracks it. [id] is the CoinGecko id, [symbol] the ticker. */
data class Coin(val id: String, val symbol: String, val name: String) {
    val isStable: Boolean get() = symbol in STABLECOINS

    fun toJson(): JSONObject = JSONObject().put("id", id).put("symbol", symbol).put("name", name)

    companion object {
        fun fromJson(o: JSONObject): Coin {
            val sym = o.getString("symbol").uppercase()
            return Coin(o.getString("id"), sym, o.optString("name").ifEmpty { sym })
        }
    }
}

data class Candle(val ts: Long, val open: Double, val high: Double, val low: Double, val close: Double, val volume: Double) {
    fun toJson(): JSONArray = JSONArray().put(ts).put(open).put(high).put(low).put(close).put(volume)

    companion object {
        fun fromJson(a: JSONArray): Candle =
            Candle(a.getLong(0), a.getDouble(1), a.getDouble(2), a.getDouble(3), a.getDouble(4), a.getDouble(5))

        fun listToJson(cs: List<Candle>): String = JSONArray().apply { cs.forEach { put(it.toJson()) } }.toString()
        fun listFromJson(s: String): List<Candle> {
            val a = JSONArray(s)
            return (0 until a.length()).map { fromJson(a.getJSONArray(it)) }
        }
    }
}

/** Market-wide data for one coin (CoinGecko), always in USD. */
data class MarketInfo(
    val id: String,
    val symbol: String,
    val name: String,
    val priceUsd: Double,
    val marketCap: Double? = null,
    val athUsd: Double? = null,
    val athChangePct: Double? = null,
    val change1h: Double? = null,
    val change24h: Double? = null,
    val change7d: Double? = null,
    val change30d: Double? = null,
    val volume24h: Double? = null,
    val high24h: Double? = null,
    val low24h: Double? = null,
    /** Seven days of hourly USD prices, oldest first (CoinGecko's sparkline). */
    val spark7d: List<Double> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("symbol", symbol); put("name", name); put("price_usd", priceUsd)
        putNum("market_cap", marketCap); putNum("ath_usd", athUsd); putNum("ath_change_pct", athChangePct)
        putNum("change_1h", change1h); putNum("change_24h", change24h); putNum("change_7d", change7d)
        putNum("change_30d", change30d); putNum("volume_24h", volume24h)
        putNum("high_24h", high24h); putNum("low_24h", low24h)
        put("spark", JSONArray().apply { spark7d.forEach { put(it) } })
    }

    companion object {
        fun fromJson(o: JSONObject): MarketInfo = MarketInfo(
            o.getString("id"), o.getString("symbol"), o.optString("name"), o.getDouble("price_usd"),
            o.optDoubleOrNull("market_cap"), o.optDoubleOrNull("ath_usd"), o.optDoubleOrNull("ath_change_pct"),
            o.optDoubleOrNull("change_1h"), o.optDoubleOrNull("change_24h"), o.optDoubleOrNull("change_7d"),
            o.optDoubleOrNull("change_30d"), o.optDoubleOrNull("volume_24h"),
            o.optDoubleOrNull("high_24h"), o.optDoubleOrNull("low_24h"),
            o.optJSONArray("spark")?.let { a -> (0 until a.length()).map { a.getDouble(it) } } ?: emptyList(),
        )
    }
}

/**
 * A manually entered buy or sell.
 *
 * [cost] is what was paid (or received, for a sell) in [fiat], excluding the
 * fee. [usdRate] is fiat-per-USD at the time of the trade, so the USD cost
 * basis stays historical instead of moving with today's exchange rate.
 */
data class Trade(
    val ts: Long,
    val coinId: String,
    val symbol: String,
    val amount: Double,
    val cost: Double,
    val fiat: String,
    val fee: Double = 0.0,
    val side: String = "buy",
    val platform: String = "",
    val note: String = "",
    val usdRate: Double? = null,
    val id: Long = 0,
) {
    /** Fiat that left the wallet for a buy (cost + fee), or arrived for a sell (cost - fee). */
    val totalCost: Double get() = if (side == "buy") cost + fee else cost - fee

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("ts", ts); put("coin_id", coinId); put("symbol", symbol); put("side", side)
        put("amount", amount); put("cost", cost); put("fiat", fiat); put("fee", fee)
        put("platform", platform); put("note", note); putNum("usd_rate", usdRate)
    }

    companion object {
        fun fromJson(o: JSONObject): Trade = Trade(
            o.getLong("ts"), o.getString("coin_id"), o.getString("symbol"), o.getDouble("amount"),
            o.getDouble("cost"), o.getString("fiat"), o.optDouble("fee", 0.0), o.optString("side", "buy"),
            o.optString("platform"), o.optString("note"), o.optDoubleOrNull("usd_rate"), o.optLong("id", 0),
        )
    }
}

/** Everything the price list shows for one coin after a refresh. */
data class CoinRow(
    val coin: Coin,
    val priceUsd: Double? = null,
    val priceFiat: Double? = null,
    val usdSource: String = "",
    val fiatSource: String = "",
    val change1h: Double? = null,
    val change24h: Double? = null,
    val change7d: Double? = null,
    val change30d: Double? = null,
    val athChangePct: Double? = null,
    val marketCap: Double? = null,
    val volume24h: Double? = null,
    val high24hUsd: Double? = null,
    val low24hUsd: Double? = null,
    val stale: Boolean = false,
    val updated: Long = 0,
    /** Seven days of hourly USD prices — only the shape is drawn. */
    val spark: List<Double> = emptyList(),
) {
    fun change(period: String): Double? = when (period) {
        "1h" -> change1h
        "7d" -> change7d
        "30d" -> change30d
        else -> change24h
    }
}

fun JSONObject.optDoubleOrNull(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    val d = optDouble(key)
    return if (d.isNaN() || d.isInfinite()) null else d
}

fun JSONObject.putNum(key: String, value: Double?): JSONObject {
    if (value != null && value.isFinite()) put(key, value)
    return this
}
