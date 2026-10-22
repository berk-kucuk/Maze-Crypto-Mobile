package com.mazecrypto.app.core

import kotlin.math.abs

/**
 * Positions, average cost, profit/loss and target-allocation drift — a port of
 * the desktop's core/portfolio.py.
 *
 * Cost basis uses the average-cost method: a sell removes the sold fraction of
 * the basis and books the difference as realised profit/loss.
 */
const val OTHER = "*other*"   // target bucket for every coin without a target of its own
private const val EPS = 1e-12

class Position(val coinId: String, val symbol: String) {
    var amount = 0.0
    var costFiat = 0.0          // remaining basis in the portfolio currency
    var costUsd: Double? = 0.0  // null once any trade lacks a USD rate
    var realizedFiat = 0.0
    var realizedUsd: Double? = 0.0
    var feesFiat = 0.0
    var trades = 0
    /** False for a coin held in a wallet without recorded buys: its cost basis is unknown, not zero. */
    var costKnown = true
    /** "trades" (replayed from the trades) or "wallet" (amount from the user's addresses). */
    var source = "trades"

    val avgCostFiat: Double? get() = if (costKnown && amount > EPS) costFiat / amount else null
    val avgCostUsd: Double? get() = costUsd?.takeIf { costKnown && amount > EPS }?.let { it / amount }
}

class PortfolioError(message: String) : Exception(message)

private fun toBase(amount: Double, t: Trade, base: String, basePerUsd: Double?): Double {
    if (t.fiat == base) return amount
    if (t.usdRate != null && t.usdRate > 0 && basePerUsd != null) return amount / t.usdRate * basePerUsd
    throw PortfolioError("cannot convert ${t.fiat} trade to $base")
}

/** Replay trades in time order into one [Position] per coin. */
fun buildPositions(trades: Iterable<Trade>, base: String, basePerUsd: Double? = null): Map<String, Position> {
    val out = LinkedHashMap<String, Position>()
    for (t in trades.sortedWith(compareBy({ it.ts }, { it.id }))) {
        val p = out.getOrPut(t.coinId) { Position(t.coinId, t.symbol) }
        p.trades += 1
        val total = toBase(t.totalCost, t, base, basePerUsd)
        val totalUsd = t.usdRate?.takeIf { it > 0 }?.let { t.totalCost / it }
        p.feesFiat += toBase(t.fee, t, base, basePerUsd)
        if (t.side == "buy") {
            p.amount += t.amount
            p.costFiat += total
            p.costUsd = p.costUsd?.let { c -> totalUsd?.let { c + it } }
        } else {
            if (p.amount <= EPS || t.amount > p.amount * (1 + 1e-9) + EPS) {
                throw PortfolioError("sell of ${t.symbol} exceeds the position")
            }
            val qty = minOf(t.amount, p.amount)
            val frac = qty / p.amount
            val basis = p.costFiat * frac
            p.realizedFiat += total - basis
            p.costFiat -= basis
            val cu = p.costUsd
            val ru = p.realizedUsd
            if (cu != null && totalUsd != null && ru != null) {
                val basisUsd = cu * frac
                p.realizedUsd = ru + totalUsd - basisUsd
                p.costUsd = cu - basisUsd
            } else {
                p.costUsd = null
                p.realizedUsd = null
            }
            p.amount -= qty
            if (p.amount <= EPS) {
                p.amount = 0.0
                p.costFiat = 0.0
                if (p.costUsd != null) p.costUsd = 0.0
            }
        }
    }
    return out
}

class Holding(val position: Position, val priceFiat: Double?, val priceUsd: Double?) {
    val valueFiat: Double? get() = priceFiat?.let { position.amount * it }
    val valueUsd: Double? get() = priceUsd?.let { position.amount * it }
    val pnlFiat: Double? get() = if (!position.costKnown) null else valueFiat?.let { it - position.costFiat }
    val pnlUsd: Double? get() {
        val v = valueUsd ?: return null
        val c = position.costUsd ?: return null
        return v - c
    }
    val pnlPct: Double? get() {
        val p = pnlFiat ?: return null
        return if (position.costFiat <= EPS) null else p / position.costFiat * 100.0
    }

    /** How far the price is under the average cost, in percent (negative = above). */
    val belowCostPct: Double? get() {
        val avg = position.avgCostFiat ?: return null
        val p = priceFiat ?: return null
        return (1.0 - p / avg) * 100.0
    }
}

class Summary(
    val holdings: List<Holding>,
    val valueFiat: Double,
    val costFiat: Double,
    val valueUsd: Double?,
    val costUsd: Double?,
    val realizedFiat: Double,
    val weights: Map<String, Double>,  // coin_id -> percent of value
    /** Value of the holdings whose cost basis is known — what [costFiat] is compared with. */
    val knownValueFiat: Double = valueFiat,
    /** Symbols held without a known cost basis (wallet coins never recorded as trades). */
    val unknownCost: List<String> = emptyList(),
) {
    val pnlFiat: Double get() = knownValueFiat - costFiat
    val pnlPct: Double? get() = if (costFiat > EPS) pnlFiat / costFiat * 100.0 else null
    val pnlUsd: Double? get() = if (valueUsd == null || costUsd == null) null else valueUsd - costUsd
}

fun summarize(positions: Map<String, Position>, pricesFiat: Map<String, Double>, pricesUsd: Map<String, Double>): Summary {
    val holdings = positions.values.filter { it.amount > EPS }
        .map { Holding(it, pricesFiat[it.coinId], pricesUsd[it.coinId]) }
    val value = holdings.sumOf { it.valueFiat ?: 0.0 }
    val cost = holdings.filter { it.position.costKnown }.sumOf { it.position.costFiat }
    val usdVals = holdings.map { it.valueUsd }
    val usdCosts = holdings.map { it.position.costUsd }
    val valueUsd = if (usdVals.any { it == null }) null else usdVals.sumOf { it!! }
    val costUsd = if (usdCosts.any { it == null }) null else usdCosts.sumOf { it!! }
    val known = holdings.filter { it.position.costKnown }
    val weights = if (value > 0) holdings.associate { it.position.coinId to (it.valueFiat ?: 0.0) / value * 100.0 } else emptyMap()
    return Summary(
        holdings.sortedByDescending { it.valueFiat ?: 0.0 }, value, cost, valueUsd, costUsd,
        positions.values.sumOf { it.realizedFiat }, weights,
        known.sumOf { it.valueFiat ?: 0.0 }, holdings.filter { !it.position.costKnown }.map { it.position.symbol },
    )
}

/** What the portfolio was worth 24 hours ago, from each coin's 24h change. */
fun valueChange24h(s: Summary, change24h: Map<String, Double?>): Pair<Double, Double>? {
    if (s.holdings.isEmpty() || s.valueFiat <= 0) return null
    var before = 0.0
    var now = 0.0
    for (h in s.holdings) {
        val v = h.valueFiat ?: continue
        val ch = change24h[h.position.coinId] ?: 0.0
        now += v
        before += v / (1 + ch / 100.0)
    }
    if (before <= 0) return null
    return (now - before) to (now / before - 1) * 100.0
}

// ── target allocation ──────────────────────────────────────────────────────

fun validateTargets(targets: Map<String, Double>): Map<String, Double> {
    val clean = targets.filterValues { it > 0 }
    if (clean.values.any { it > 100 }) throw PortfolioError("a target above 100%")
    if (clean.values.sum() > 100.0 + 1e-6) throw PortfolioError("targets add up to more than 100%")
    return clean
}

/** Fold coins without their own target into the OTHER bucket. */
fun bucketWeights(weights: Map<String, Double>, targets: Map<String, Double>): Map<String, Double> {
    val out = LinkedHashMap<String, Double>()
    targets.keys.forEach { out[it] = 0.0 }
    for ((cid, w) in weights) {
        val key = if (cid in targets) cid else OTHER
        out[key] = (out[key] ?: 0.0) + w
    }
    return out
}

data class Drift(val bucket: String, val targetPct: Double, val actualPct: Double, val rebalanceFiat: Double) {
    val diffPct: Double get() = actualPct - targetPct
}

/** Actual vs target per bucket, largest absolute deviation first. */
fun drift(weights: Map<String, Double>, targets: Map<String, Double>, totalValue: Double): List<Drift> {
    if (targets.isEmpty()) return emptyList()
    val actual = bucketWeights(weights, targets)
    val keys = targets.keys + actual.keys
    return keys.map { k ->
        val tg = targets[k] ?: 0.0
        val ac = actual[k] ?: 0.0
        Drift(k, tg, ac, (tg - ac) / 100.0 * totalValue)
    }.sortedWith(compareBy({ -abs(it.diffPct) }, { it.bucket }))
}

/**
 * Split new money so the portfolio moves toward its targets without selling.
 * Pure arithmetic, not a recommendation.
 */
fun splitContribution(amount: Double, values: Map<String, Double>, targets: Map<String, Double>): Map<String, Double> {
    if (amount <= 0 || targets.isEmpty()) return emptyMap()
    val bucketed = LinkedHashMap<String, Double>()
    targets.keys.forEach { bucketed[it] = 0.0 }
    for ((cid, v) in values) {
        val key = if (cid in targets) cid else OTHER
        bucketed[key] = (bucketed[key] ?: 0.0) + v
    }
    val totalAfter = bucketed.values.sum() + amount
    val tsum = targets.values.sum()
    val deficit = targets.mapValues { (k, tg) -> maxOf(0.0, tg / tsum * totalAfter - (bucketed[k] ?: 0.0)) }
    val dsum = deficit.values.sum()
    if (dsum <= EPS) return targets.mapValues { (_, tg) -> amount * tg / tsum }
    return deficit.filterValues { it > EPS }.mapValues { (_, d) -> amount * d / dsum }
}

/**
 * The portfolio's value over the last seven days at today's amounts: each
 * holding follows its coin's hourly price line ([sparks], any currency — only
 * the shape is used, scaled so the last point is today's value). Holdings with
 * no price line count as flat. Empty when nothing moves.
 */
fun portfolioTrend(sum: Summary, sparks: Map<String, List<Double>>, points: Int = 84): List<Double> {
    if (sum.holdings.isEmpty() || points < 2) return emptyList()
    val out = DoubleArray(points)
    var moving = false
    for (h in sum.holdings) {
        val v = h.valueFiat ?: continue
        val s = sparks[h.position.coinId]?.filter { it > 0 && it.isFinite() }
        if (s == null || s.size < 2) {
            for (i in out.indices) out[i] += v
            continue
        }
        moving = true
        val last = s.last()
        for (i in 0 until points) {
            val x = i.toDouble() * (s.size - 1) / (points - 1)
            val lo = x.toInt().coerceIn(0, s.size - 1)
            val hi = minOf(lo + 1, s.size - 1)
            val f = x - lo
            out[i] += v * (s[lo] * (1 - f) + s[hi] * f) / last
        }
    }
    return if (moving) out.toList() else emptyList()
}
