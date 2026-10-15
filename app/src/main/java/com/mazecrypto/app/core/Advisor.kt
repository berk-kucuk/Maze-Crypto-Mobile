package com.mazecrypto.app.core

import java.time.LocalDate
import kotlin.math.abs

/**
 * The built-in assistant (port of core/advisor.py): plain-language tips from
 * the user's own data. Runs locally, needs no network and no AI. Tips describe
 * the user's own plan, targets and holdings; they never say "buy X" or
 * "sell Y" and never forecast prices.
 *
 * Titles and bodies are i18n keys; [Tip.params] are already formatted.
 */
data class Tip(
    val id: String,
    val level: String,                 // action | warn | info | good
    val title: String,
    val body: String,
    val params: Map<String, Any?> = emptyMap(),
    val action: String = "",           // screen key to open ("" = none)
    val actionLabel: String = "",
    val priority: Int = 50,            // lower comes first
    val extra: String = "",
) {
    fun titleText() = t(title, *params.toList().toTypedArray())
    fun bodyText(): String {
        val p = params.toList().toTypedArray()
        return t(body, *p) + if (extra.isNotEmpty()) " " + t(extra, *p) else ""
    }
}

class TipFormatter(
    val money: (Double) -> String = { "%,.2f".format(it) },
    val pct: (Double) -> String = { "%+.1f%%".format(it) },
    val num: (Double) -> String = { "%.1f".format(it) },
)

class AdvisorInput(
    val fiat: String,
    val rows: List<CoinRow> = emptyList(),
    val watchlist: List<String> = emptyList(),
    val summary: Summary? = null,
    val trades: Int = 0,
    val tradesWithoutRate: Int = 0,
    val targets: Map<String, Double> = emptyMap(),
    val names: Map<String, String> = emptyMap(),
    val planAmount: Double? = null,
    val current: Pair<Installment, String>? = null,
    val planExists: Boolean = false,
    val rules: Int = 0,
    val tech: Map<String, TechState> = emptyMap(),
    val offline: Boolean = false,
    val staleAge: Double? = null,
    val today: LocalDate = LocalDate.now(),
)

private val LEVELS = listOf("action", "warn", "info", "good")
private const val DRIFT_PP = 5.0
private const val CONCENTRATION = 70.0
private const val BELOW_COST = 15.0
private const val BIG_MOVE = 8.0
private const val NEAR_ATH = 5.0
private val LEARN = listOf("dca", "avg_cost", "correlation", "volatility", "ath", "reserve", "fees", "rsi", "drawdown", "targets")

fun tips(x: AdvisorInput, f: TipFormatter = TipFormatter()): List<Tip> {
    val out = mutableListOf<Tip>()
    val s = x.summary
    val held = s?.holdings?.associateBy { it.position.coinId } ?: emptyMap()

    fun name(cid: String): String =
        x.names[cid] ?: x.rows.firstOrNull { it.coin.id == cid }?.coin?.symbol ?: cid

    // ── data health ──────────────────────────────────────────────────────────
    if (x.offline) {
        out += Tip("data.offline", "warn", "tip.offline.t", "tip.offline.b", priority = 5)
    } else if (x.staleAge != null && x.staleAge > 900) {
        out += Tip("data.stale", "info", "tip.stale.t", "tip.stale.b", priority = 40)
    }
    val noPrice = held.values.filter { it.priceFiat == null }.map { it.position.symbol }
    if (noPrice.isNotEmpty() && !x.offline) {
        out += Tip("data.no_price", "warn", "tip.no_price.t", "tip.no_price.b", mapOf("coins" to noPrice.joinToString(", ")), priority = 20)
    }

    // ── getting started ──────────────────────────────────────────────────────
    if (x.watchlist.isEmpty()) {
        out += Tip("start.watch", "action", "tip.start_watch.t", "tip.start_watch.b", action = "watchlist",
            actionLabel = "tip.btn.add_coin", priority = 10)
    }
    if (x.trades == 0) {
        out += Tip("start.trades", "action", "tip.start_trades.t", "tip.start_trades.b", action = "holdings:add",
            actionLabel = "tip.btn.add_trade", priority = 12)
    } else if (x.targets.isEmpty()) {
        out += Tip("start.targets", "info", "tip.start_targets.t", "tip.start_targets.b", action = "holdings:targets",
            actionLabel = "tip.btn.set_targets", priority = 30)
    }
    if (x.trades > 0 && !x.planExists) {
        out += Tip("start.plan", "info", "tip.start_plan.t", "tip.start_plan.b", action = "dca",
            actionLabel = "tip.btn.make_plan", priority = 60)
    }
    if (x.trades > 0 && x.rules == 0) {
        out += Tip("start.rules", "info", "tip.start_rules.t", "tip.start_rules.b", action = "rules",
            actionLabel = "tip.btn.add_alert", priority = 65)
    }

    // ── the user's own plan ──────────────────────────────────────────────────
    x.current?.let { (inst, state) ->
        val items = inst.items.take(5).joinToString(", ") { "${it.symbol} ${f.money(it.amount)}" }
        val days = daysBetween(x.today, inst.due)
        val params = mapOf("amount" to f.money(inst.total), "items" to items, "n" to inst.index + 1, "days" to abs(days))
        if (state == "due" || state == "overdue") {
            out += Tip("plan.$state", "action", "tip.plan_$state.t", "tip.plan_due.b", params, action = "dca",
                actionLabel = "tip.btn.open_plan", priority = 8)
        } else if (days <= 2) {
            out += Tip("plan.soon", "info", "tip.plan_soon.t", "tip.plan_due.b", params + ("days" to days), action = "dca",
                actionLabel = "tip.btn.open_plan", priority = 25)
        }
    }

    // ── balance against the user's targets ──────────────────────────────────
    if (s != null && x.targets.isNotEmpty() && s.valueFiat > 0) {
        val off = drift(s.weights, x.targets, s.valueFiat).filter { abs(it.diffPct) > DRIFT_PP }
        if (off.isNotEmpty()) {
            val d = off[0]
            val key = if (d.diffPct > 0) "tip.drift_over.b" else "tip.drift_under.b"
            val params = mutableMapOf<String, Any?>(
                "coin" to if (d.bucket != OTHER) name(d.bucket) else (x.names[OTHER] ?: "Other"),
                "pp" to f.num(abs(d.diffPct)), "actual" to f.num(d.actualPct), "target" to f.num(d.targetPct),
            )
            var body2 = ""
            if (x.planAmount != null && x.planAmount > 0) {
                val values = held.mapValues { it.value.valueFiat ?: 0.0 }
                val split = splitContribution(x.planAmount, values, x.targets)
                if (split.isNotEmpty()) {
                    params["amount"] = f.money(x.planAmount)
                    params["split"] = split.entries.sortedByDescending { it.value }.take(3)
                        .joinToString(", ") { "${name(it.key)} ${f.money(it.value)}" }
                    body2 = "tip.drift_split.b"
                }
            }
            out += Tip("balance.drift", "warn", "tip.drift.t", key, params, action = "holdings:alloc",
                actionLabel = "tip.btn.see_alloc", priority = 15, extra = body2)
        } else {
            out += Tip("balance.ok", "good", "tip.balance_ok.t", "tip.balance_ok.b", mapOf("pp" to f.num(DRIFT_PP)), priority = 70)
        }
    }

    // ── risk descriptions ────────────────────────────────────────────────────
    if (s != null && s.valueFiat > 0) {
        val risky = s.weights.filterKeys { held[it]?.position?.symbol !in STABLECOINS }
        if (risky.isNotEmpty()) {
            val top = risky.maxBy { it.value }
            if (top.value >= CONCENTRATION && (x.targets[top.key] ?: 0.0) < CONCENTRATION) {
                out += Tip("risk.concentration", "info", "tip.concentration.t", "tip.concentration.b",
                    mapOf("coin" to held[top.key]!!.position.symbol, "pct" to f.num(top.value)), priority = 35)
            }
        }
        val stable = s.weights.filterKeys { held[it]?.position?.symbol in STABLECOINS }.values.sum()
        if (stable == 0.0 && x.targets.keys.none { name(it) in STABLECOINS } && held.size >= 2) {
            out += Tip("risk.no_reserve", "info", "tip.no_reserve.t", "tip.no_reserve.b", priority = 75)
        }
        for (h in held.values) {
            val below = h.belowCostPct
            if (below != null && below >= BELOW_COST) {
                out += Tip("risk.below_cost.${h.position.coinId}", "info", "tip.below_cost.t", "tip.below_cost.b",
                    mapOf("coin" to h.position.symbol, "pct" to f.num(below), "avg" to f.money(h.position.avgCostFiat ?: 0.0)),
                    priority = 32)
            }
        }
    }

    // ── market descriptions (held coins first, then watchlist) ──────────────
    val order = held.keys.toList() + x.watchlist.filter { it !in held }
    val byId = x.rows.associateBy { it.coin.id }
    var moves = 0
    for (cid in order) {
        val r = byId[cid] ?: continue
        val ch = r.change24h
        if (ch != null && abs(ch) >= BIG_MOVE && moves < 2 && !r.coin.isStable) {
            moves++
            out += Tip("market.move.$cid", "info", if (ch > 0) "tip.move_up.t" else "tip.move_down.t", "tip.move.b",
                mapOf("coin" to r.coin.symbol, "pct" to f.pct(ch)), action = "chart:$cid",
                actionLabel = "tip.btn.open_chart", priority = if (cid in held) 28 else 45)
        }
        val ath = r.athChangePct
        if (ath != null && ath > -NEAR_ATH && !r.coin.isStable) {
            out += Tip("market.ath.$cid", "info", "tip.near_ath.t", "tip.near_ath.b",
                mapOf("coin" to r.coin.symbol, "pct" to f.num(abs(ath))), priority = 55)
        }
        val st = x.tech[cid]
        if (st?.rsi != null && cid in held) {
            if (st.rsiState == "overbought") {
                out += Tip("market.hot.$cid", "info", "tip.rsi_hot.t", "tip.rsi_hot.b",
                    mapOf("coin" to r.coin.symbol, "rsi" to f.num(st.rsi)), action = "chart:$cid",
                    actionLabel = "tip.btn.open_chart", priority = 50)
            } else if (st.rsiState == "oversold") {
                out += Tip("market.cold.$cid", "info", "tip.rsi_cold.t", "tip.rsi_cold.b",
                    mapOf("coin" to r.coin.symbol, "rsi" to f.num(st.rsi)), action = "chart:$cid",
                    actionLabel = "tip.btn.open_chart", priority = 50)
            }
        }
    }

    if (x.tradesWithoutRate > 0) {
        out += Tip("data.no_rate", "info", "tip.no_rate.t", "tip.no_rate.b", mapOf("n" to x.tradesWithoutRate),
            action = "holdings:trades", actionLabel = "tip.btn.see_trades", priority = 80)
    }

    // ── one learning card a day ─────────────────────────────────────────────
    // Python's date.toordinal() counts from 0001-01-01 = 1; epoch day 0 is ordinal 719163.
    val topic = LEARN[((x.today.toEpochDay() + 719163) % LEARN.size).toInt()]
    out += Tip("learn.$topic", "info", "learn.$topic.t", "learn.$topic.b", priority = 90)

    return out.sortedWith(compareBy({ it.priority }, { LEVELS.indexOf(it.level) }))
}
