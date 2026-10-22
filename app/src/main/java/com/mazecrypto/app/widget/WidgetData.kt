package com.mazecrypto.app.widget

import android.content.Context
import com.mazecrypto.app.core.Summary
import com.mazecrypto.app.core.current
import com.mazecrypto.app.core.optDoubleOrNull
import com.mazecrypto.app.core.progress
import com.mazecrypto.app.core.putNum
import com.mazecrypto.app.core.schedule
import com.mazecrypto.app.core.valueChange24h
import com.mazecrypto.app.data.Profile
import com.mazecrypto.app.data.Settings
import com.mazecrypto.app.data.Snapshot
import com.mazecrypto.app.data.objects
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/**
 * Everything the widgets draw, written by the app after every refresh.
 *
 * A widget is drawn in the launcher's process whether or not the app runs,
 * so it cannot fetch anything at draw time: it shows the last snapshot the
 * app saved here, and always says how old it is.
 */
data class WidgetData(
    val ts: Long,                         // epoch ms of the prices
    val fiat: String,
    val offline: Boolean,
    val change: String,                   // 1h | 24h | 7d
    val private: Boolean,
    val coins: List<WCoin>,               // the whole watchlist, in its order
    val favorites: List<String>,          // ids shown on the favorites widget
    val portfolio: WPortfolio?,
    val plan: WPlan?,
    /** Portfolio value over the last 7 days at today's amounts, oldest first. */
    val trend: List<Double> = emptyList(),
    val wallets: List<WWallet> = emptyList(),
) {
    /** One wallet as the wallets widget shows it; [state] is ok | syncing:<pct> | error | manual. */
    data class WWallet(val title: String, val chain: String, val device: String, val value: Double?, val state: String, val inPortfolio: Boolean, val ref: String = "")

    data class WCoin(
        val id: String, val sym: String, val name: String, val price: Double?,
        val ch1h: Double?, val ch24h: Double?, val ch7d: Double?, val ch30d: Double?,
        val high24: Double?, val low24: Double?,  // in the portfolio currency
        val stable: Boolean,
        val spark: List<Double>,
    ) {
        fun change(period: String) = when (period) { "1h" -> ch1h; "7d" -> ch7d; "30d" -> ch30d; else -> ch24h }
    }

    data class WHolding(val id: String, val sym: String, val value: Double?, val pnlPct: Double?, val weight: Double)

    data class WPortfolio(
        val value: Double, val cost: Double, val pnl: Double, val pnlPct: Double?,
        val day: Double?, val dayPct: Double?, val holdings: List<WHolding>,
    ) {
        val top: List<Pair<String, Double>> get() = holdings.map { it.sym to it.weight }
    }

    /** The buying plan's "this period" card; [state] is due | overdue | upcoming | finished. */
    data class WPlan(
        val name: String, val fiat: String, val state: String, val index: Int, val periods: Int, val done: Int,
        val dueEpochDay: Long, val amount: Double, val items: List<Pair<String, Double>>,
    )

    fun coin(id: String): WCoin? = coins.firstOrNull { it.id == id }
    fun favoriteCoins(): List<WCoin> = favorites.mapNotNull { coin(it) }

    fun toJson(): JSONObject = JSONObject().apply {
        put("v", 2)
        put("ts", ts); put("fiat", fiat); put("offline", offline); put("change", change); put("private", private)
        put("favorites", JSONArray(favorites))
        put("coins", JSONArray().apply {
            coins.forEach { c ->
                put(JSONObject().apply {
                    put("id", c.id); put("sym", c.sym); put("name", c.name); putNum("price", c.price)
                    putNum("ch1h", c.ch1h); putNum("ch24h", c.ch24h); putNum("ch7d", c.ch7d); putNum("ch30d", c.ch30d)
                    putNum("high24", c.high24); putNum("low24", c.low24); put("stable", c.stable)
                    put("spark", JSONArray().apply { c.spark.forEach { put(it) } })
                })
            }
        })
        portfolio?.let { p ->
            put("portfolio", JSONObject().apply {
                put("value", p.value); put("cost", p.cost); put("pnl", p.pnl); putNum("pnl_pct", p.pnlPct)
                putNum("day", p.day); putNum("day_pct", p.dayPct)
                put("holdings", JSONArray().apply {
                    p.holdings.forEach { h ->
                        put(JSONObject().put("id", h.id).put("sym", h.sym).put("weight", h.weight)
                            .putNum("value", h.value).putNum("pnl_pct", h.pnlPct))
                    }
                })
            })
        }
        put("trend", JSONArray().apply { trend.forEach { put(it) } })
        put("wallets", JSONArray().apply {
            wallets.forEach { w ->
                put(JSONObject().put("t", w.title).put("c", w.chain).put("d", w.device).putNum("v", w.value).put("s", w.state).put("p", w.inPortfolio).put("r", w.ref))
            }
        })
        plan?.let { pl ->
            put("plan", JSONObject().apply {
                put("name", pl.name); put("fiat", pl.fiat); put("state", pl.state); put("index", pl.index)
                put("periods", pl.periods); put("done", pl.done); put("due", pl.dueEpochDay); put("amount", pl.amount)
                put("items", JSONArray().apply { pl.items.forEach { put(JSONArray().put(it.first).put(it.second)) } })
            })
        }
    }

    companion object {
        private const val FILE = "widget.json"
        private const val SPARK_POINTS = 48

        fun file(ctx: Context) = File(ctx.filesDir, FILE)

        fun load(ctx: Context): WidgetData? = runCatching { fromJson(JSONObject(file(ctx).readText())) }.getOrNull()

        fun save(ctx: Context, d: WidgetData) {
            val f = file(ctx)
            val tmp = File(f.parentFile, "$FILE.tmp")
            runCatching {
                tmp.writeText(d.toJson().toString())
                if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            }
        }

        private fun fromJson(o: JSONObject): WidgetData {
            val favs = o.optJSONArray("favorites")
            val pf = o.optJSONObject("portfolio")
            val pl = o.optJSONObject("plan")
            return WidgetData(
                o.getLong("ts"), o.getString("fiat"), o.optBoolean("offline"), o.optString("change", "24h"),
                o.optBoolean("private"),
                o.getJSONArray("coins").objects().map { c ->
                    val sp = c.optJSONArray("spark") ?: JSONArray()
                    WCoin(c.getString("id"), c.getString("sym"), c.optString("name"), c.optDoubleOrNull("price"),
                        c.optDoubleOrNull("ch1h"), c.optDoubleOrNull("ch24h"), c.optDoubleOrNull("ch7d"), c.optDoubleOrNull("ch30d"),
                        c.optDoubleOrNull("high24"), c.optDoubleOrNull("low24"), c.optBoolean("stable"),
                        (0 until sp.length()).map { sp.getDouble(it) })
                },
                favs?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                pf?.let { p ->
                    WPortfolio(p.getDouble("value"), p.getDouble("cost"), p.getDouble("pnl"), p.optDoubleOrNull("pnl_pct"),
                        p.optDoubleOrNull("day"), p.optDoubleOrNull("day_pct"),
                        (p.optJSONArray("holdings") ?: JSONArray()).objects().map { h ->
                            WHolding(h.getString("id"), h.getString("sym"), h.optDoubleOrNull("value"), h.optDoubleOrNull("pnl_pct"),
                                h.optDouble("weight", 0.0))
                        })
                },
                pl?.let { p ->
                    WPlan(p.optString("name"), p.getString("fiat"), p.getString("state"), p.getInt("index"), p.getInt("periods"),
                        p.getInt("done"), p.getLong("due"), p.getDouble("amount"),
                        (p.optJSONArray("items") ?: JSONArray()).let { a -> (0 until a.length()).map { a.getJSONArray(it).let { x -> x.getString(0) to x.getDouble(1) } } })
                },
                o.optJSONArray("trend")?.let { a -> (0 until a.length()).map { a.getDouble(it) } } ?: emptyList(),
                o.optJSONArray("wallets")?.objects()?.map { w ->
                    WWallet(w.optString("t"), w.optString("c"), w.optString("d"), w.optDoubleOrNull("v"), w.optString("s"), w.optBoolean("p", true), w.optString("r"))
                } ?: emptyList(),
            )
        }

        /** The wallets as the widget shows them: value in the portfolio currency and a short state. */
        private fun walletsOf(): List<WWallet> {
            val prices = com.mazecrypto.app.data.Maze.prices().first
            val data = com.mazecrypto.app.data.Maze.walletData.value
            val progress = com.mazecrypto.app.data.MoneroSync.progress.value
            return com.mazecrypto.app.data.Maze.wallets.value.map { w ->
                val st = data[w.id]
                val value = if (w.manual) w.amount?.let { a -> prices[w.coinId]?.let { a * it } }
                    else st?.balance?.assets?.let { a -> if (a.isEmpty()) 0.0 else a.sumOf { x -> x.amount * (prices[x.coinId] ?: 0.0) } }
                val state = when {
                    progress[w.id]?.phase == "back" -> "history:" + progress[w.id]!!.ts
                    progress[w.id] != null -> "syncing:" + (progress[w.id]!!.fraction * 100).toInt()
                    st?.error != null -> "error"
                    w.manual -> "manual"
                    else -> "ok"
                }
                WWallet(com.mazecrypto.app.ui.screens.walletTitle(w), w.chain, w.device, value, state, w.inPortfolio,
                    if (w.address.isEmpty()) "" else com.mazecrypto.app.ui.screens.walletRef(w))
            }.sortedByDescending { it.value ?: -1.0 }
        }

        /** Keep the shape, drop most of the 168 hourly points: a widget row is ~40 dp wide. */
        private fun downsample(xs: List<Double>, n: Int = SPARK_POINTS): List<Double> {
            if (xs.size <= n) return xs
            return (0 until n).map { i -> xs[(i.toDouble() * (xs.size - 1) / (n - 1)).toInt()] }
        }

        private fun planOf(profile: Profile, sum: Summary): WPlan? {
            val plan = profile.dcaPlan ?: return null
            val values = sum.holdings.associate { it.position.coinId to (it.valueFiat ?: 0.0) }
            val sched = schedule(plan, values, profile.targets)
            val prog = progress(sched, profile.dcaDone)
            val cur = current(sched, profile.dcaDone, LocalDate.now())
            val inst = cur?.first ?: sched.last()
            return WPlan(
                plan.name, plan.fiat, cur?.second ?: "finished", inst.index, plan.periods, prog.done,
                inst.due.toEpochDay(), if (cur == null) 0.0 else inst.total, inst.items.map { it.symbol to it.amount },
            )
        }

        /**
         * Build from the latest snapshot. With no snapshot yet, the previous
         * file keeps its prices and only takes the new settings (favorites,
         * order, change period), so a fresh start never blanks the widgets.
         */
        fun build(ctx: Context, s: Settings, snap: Snapshot?, sum: Summary, profile: Profile): WidgetData? {
            val previous = if (snap == null) load(ctx) else null
            val rows = snap?.rows?.associateBy { it.coin.id } ?: emptyMap()
            val rate = snap?.fx?.get(s.fiat)
            val coins = s.watchlist.map { c ->
                val r = rows[c.id]
                val old = previous?.coin(c.id)
                if (r == null && old != null) old.copy(sym = c.symbol, name = c.name)
                else {
                    // CoinGecko's 24h range is in USD and can lag the exchange price: never let the price sit outside it.
                    val hi = r?.high24hUsd?.let { h -> rate?.let { h * it } }?.let { h -> r.priceFiat?.let { maxOf(h, it) } ?: h }
                    val lo = r?.low24hUsd?.let { l -> rate?.let { l * it } }?.let { l -> r.priceFiat?.let { minOf(l, it) } ?: l }
                    WCoin(c.id, c.symbol, c.name, r?.priceFiat, r?.change1h, r?.change24h, r?.change7d, r?.change30d,
                        hi, lo, c.isStable, downsample(r?.spark ?: emptyList()))
                }
            }
            val portfolio = if (snap == null) previous?.portfolio else if (sum.holdings.isEmpty()) null else {
                val day = valueChange24h(sum, snap.rows.associate { it.coin.id to it.change24h })
                WPortfolio(
                    sum.valueFiat, sum.costFiat, sum.pnlFiat, sum.pnlPct, day?.first, day?.second,
                    sum.holdings.take(8).map {
                        WHolding(it.position.coinId, it.position.symbol, it.valueFiat, it.pnlPct, sum.weights[it.position.coinId] ?: 0.0)
                    },
                )
            }
            if (snap == null && previous == null && coins.isEmpty()) return null
            return WidgetData(
                ts = snap?.ts ?: previous?.ts ?: 0L,
                fiat = if (snap == null) (previous?.fiat ?: s.fiat) else s.fiat,
                offline = snap?.offline ?: previous?.offline ?: false,
                change = s.widgetChange, private = s.widgetPrivate, coins = coins,
                favorites = s.widgetCoins().map { it.id }, portfolio = portfolio,
                plan = planOf(profile, sum),
                trend = if (snap == null) previous?.trend ?: emptyList()
                    else downsample(com.mazecrypto.app.core.portfolioTrend(sum, snap.rows.associate { it.coin.id to it.spark }), 84),
                wallets = runCatching { walletsOf() }.getOrElse { previous?.wallets ?: emptyList() },
            )
        }
    }
}
