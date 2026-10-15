package com.mazecrypto.app.core

import org.json.JSONObject
import java.util.UUID
import kotlin.math.abs

/**
 * Alert rules (port of core/rules). A condition answers "is it true right
 * now?" — true/false, or null when the data it needs is missing, in which
 * case the engine leaves the rule's state untouched instead of guessing.
 *
 * A rule fires when its condition *becomes* true and never twice within its
 * cooldown, so a price hovering around a threshold notifies once.
 */
class RuleContext(
    val pricesFiat: Map<String, Double> = emptyMap(),
    val pricesUsd: Map<String, Double> = emptyMap(),
    val avgCostFiat: Map<String, Double> = emptyMap(),
    val weights: Map<String, Double> = emptyMap(),
    val targets: Map<String, Double> = emptyMap(),
    val closes: MutableMap<Pair<String, String>, List<Double>> = mutableMapOf(),
    val fiat: String = "USD",
)

data class Eval(val active: Boolean?, val value: Double? = null, val subject: String = "")

private fun cmp(value: Double, op: String, threshold: Double) = if (op == "above") value >= threshold else value <= threshold

/** Every condition kind in one class; unused fields keep their defaults, like the desktop's dataclasses. */
data class Condition(
    val kind: String,
    val coinId: String = "",
    val symbol: String = "",
    val op: String = "below",
    val value: Double = 0.0,
    val currency: String = "fiat",
    val pct: Double = 0.0,
    val interval: String = "1d",
    val period: Int = 14,
    val fast: Int = 50,
    val slow: Int = 200,
    val direction: String = "any",
    val hours: Int = 24,
) {
    fun needs(): List<Pair<String, String>> = when (kind) {
        "rsi", "sma_cross" -> listOf(coinId to interval)
        "move" -> listOf(coinId to "1h")
        else -> emptyList()
    }

    fun evaluate(ctx: RuleContext): Eval = when (kind) {
        "price" -> {
            val p = (if (currency == "usd") ctx.pricesUsd else ctx.pricesFiat)[coinId]
            if (p == null) Eval(null) else Eval(cmp(p, op, value), p)
        }
        "cost_change" -> {
            val p = ctx.pricesFiat[coinId]
            val avg = ctx.avgCostFiat[coinId]
            if (p == null || avg == null || avg == 0.0) Eval(null) else {
                val change = (p / avg - 1.0) * 100.0
                Eval(if (pct < 0) change <= pct else change >= pct, change)
            }
        }
        "drift" -> {
            if (ctx.targets.isEmpty() || ctx.weights.isEmpty()) Eval(null) else {
                val actual = bucketWeights(ctx.weights, ctx.targets)
                var diffs = (actual.keys + ctx.targets.keys).associateWith { (actual[it] ?: 0.0) - (ctx.targets[it] ?: 0.0) }
                if (coinId.isNotEmpty()) diffs = mapOf(coinId to (diffs[coinId] ?: 0.0))
                val worst = diffs.maxBy { abs(it.value) }
                Eval(abs(worst.value) > pct, worst.value, worst.key)
            }
        }
        "rsi" -> {
            val c = ctx.closes[coinId to interval]
            val r = c?.let { Ind.last(Ind.rsi(it.toDoubleArray(), period)) }
            if (r == null) Eval(null) else Eval(cmp(r, op, value), r)
        }
        "sma_cross" -> {
            val c = ctx.closes[coinId to interval]?.toDoubleArray()
            val f = c?.let { Ind.last(Ind.sma(it, fast)) }
            val s = c?.let { Ind.last(Ind.sma(it, slow)) }
            if (f == null || s == null) Eval(null) else {
                val above = f > s
                Eval(if (direction == "golden") above else !above, (f / s - 1) * 100)
            }
        }
        "move" -> {
            val c = ctx.closes[coinId to "1h"]
            val ch = c?.let { Ind.pctChange(it.toDoubleArray(), hours) }
            when {
                ch == null -> Eval(null)
                direction == "up" -> Eval(ch >= pct, ch)
                direction == "down" -> Eval(ch <= -pct, ch)
                else -> Eval(abs(ch) >= pct, ch)
            }
        }
        else -> Eval(null)
    }

    /** Desktop-compatible dict: only the fields this kind uses. */
    fun toJson(): JSONObject {
        val o = JSONObject().put("kind", kind)
        when (kind) {
            "price" -> o.put("coin_id", coinId).put("symbol", symbol).put("op", op).put("value", value).put("currency", currency)
            "cost_change" -> o.put("coin_id", coinId).put("symbol", symbol).put("pct", pct)
            "drift" -> o.put("pct", pct).put("coin_id", coinId)
            "rsi" -> o.put("coin_id", coinId).put("symbol", symbol).put("interval", interval).put("op", op)
                .put("value", value).put("period", period)
            "sma_cross" -> o.put("coin_id", coinId).put("symbol", symbol).put("interval", interval)
                .put("fast", fast).put("slow", slow).put("direction", direction)
            "move" -> o.put("coin_id", coinId).put("symbol", symbol).put("hours", hours).put("pct", pct)
                .put("direction", direction)
        }
        return o
    }

    companion object {
        val KINDS = listOf("price", "cost_change", "move", "drift", "rsi", "sma_cross")
        val BASIC_KINDS = listOf("price", "cost_change", "move")

        fun fromJson(o: JSONObject): Condition {
            val kind = o.getString("kind")
            require(kind in KINDS) { "unknown condition kind" }
            val defaults = defaults(kind)
            return Condition(
                kind, o.optString("coin_id", defaults.coinId), o.optString("symbol", defaults.symbol),
                o.optString("op", defaults.op), o.optDouble("value", defaults.value),
                o.optString("currency", defaults.currency), o.optDouble("pct", defaults.pct),
                o.optString("interval", defaults.interval), o.optInt("period", defaults.period),
                o.optInt("fast", defaults.fast), o.optInt("slow", defaults.slow),
                o.optString("direction", defaults.direction), o.optInt("hours", defaults.hours),
            )
        }

        /** The desktop dataclass defaults for each kind. */
        fun defaults(kind: String, coinId: String = "", symbol: String = ""): Condition = when (kind) {
            "cost_change" -> Condition(kind, coinId, symbol, pct = -15.0)
            "drift" -> Condition(kind, pct = 5.0)
            "rsi" -> Condition(kind, coinId, symbol, op = "below", value = 30.0)
            "sma_cross" -> Condition(kind, coinId, symbol, direction = "golden")
            "move" -> Condition(kind, coinId, symbol, pct = 10.0, hours = 24, direction = "any")
            else -> Condition("price", coinId, symbol)
        }
    }
}

data class Rule(
    val name: String,
    val condition: Condition,
    val message: String = "",
    val enabled: Boolean = true,
    val cooldownHours: Double = 24.0,
    val id: String = UUID.randomUUID().toString().replace("-", "").take(12),
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("condition", condition.toJson())
        .put("message", message).put("enabled", enabled).put("cooldown_hours", cooldownHours)

    companion object {
        fun fromJson(o: JSONObject) = Rule(
            o.getString("name"), Condition.fromJson(o.getJSONObject("condition")), o.optString("message"),
            o.optBoolean("enabled", true), o.optDouble("cooldown_hours", 24.0),
            o.optString("id").ifEmpty { UUID.randomUUID().toString().replace("-", "").take(12) },
        )
    }
}

data class RuleState(var active: Boolean = false, var lastFired: Double = 0.0) {
    fun toJson(): JSONObject = JSONObject().put("active", active).put("last_fired", lastFired)

    companion object {
        fun fromJson(o: JSONObject) = RuleState(o.optBoolean("active"), o.optDouble("last_fired", 0.0))
    }
}

data class Alert(val rule: Rule, val value: Double?, val subject: String, val ts: Double)

/** Evaluate all enabled rules, update [state] in place, return new alerts. */
fun evaluateRules(rules: List<Rule>, ctx: RuleContext, state: MutableMap<String, RuleState>, now: Double): List<Alert> {
    val alerts = mutableListOf<Alert>()
    for (rule in rules) {
        val st = state.getOrPut(rule.id) { RuleState() }
        if (!rule.enabled) {
            st.active = false
            continue
        }
        val res = runCatching { rule.condition.evaluate(ctx) }.getOrNull() ?: continue
        val active = res.active ?: continue
        val cooled = st.lastFired == 0.0 || now - st.lastFired >= rule.cooldownHours * 3600
        if (active && !st.active && cooled) {
            st.lastFired = now
            alerts += Alert(rule, res.value, res.subject, now)
        }
        st.active = active
    }
    return alerts
}

/** Every (coin_id, interval) close series the enabled rules need. */
fun dataNeeds(rules: List<Rule>): Set<Pair<String, String>> =
    rules.filter { it.enabled }.flatMap { it.condition.needs() }.toSet()
