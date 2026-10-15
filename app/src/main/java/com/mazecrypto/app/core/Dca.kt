package com.mazecrypto.app.core

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/** Recurring-purchase (DCA) plans: schedule, progress and "this period" card. */
enum class Frequency(val key: String) {
    WEEKLY("weekly"), BIWEEKLY("biweekly"), MONTHLY("monthly");

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key } ?: WEEKLY
    }
}

/** A one-off purchase added to one period (e.g. a first-week reserve buy). */
data class DcaExtra(val period: Int, val coinId: String, val symbol: String, val amount: Double) {
    fun toJson(): JSONObject =
        JSONObject().put("period", period).put("coin_id", coinId).put("symbol", symbol).put("amount", amount)

    companion object {
        fun fromJson(o: JSONObject) =
            DcaExtra(o.getInt("period"), o.getString("coin_id"), o.getString("symbol"), o.getDouble("amount"))
    }
}

data class DcaPlan(
    val name: String,
    val start: LocalDate,
    val frequency: Frequency,
    val periods: Int,
    val amount: Double,
    val fiat: String,
    val allocation: Map<String, Double>,           // coin_id -> percent of each period's amount
    val symbols: Map<String, String> = emptyMap(),
    val extras: List<DcaExtra> = emptyList(),
    /** Split each period toward the portfolio's target allocation instead of the fixed percentages. */
    val followTargets: Boolean = false,
) {
    fun validate() {
        require(periods in 1..520) { "periods must be 1..520" }
        require(amount >= 0) { "amount must not be negative" }
        if (!followTargets) {
            val total = allocation.values.sum()
            require(!(amount > 0 && abs(total - 100.0) > 0.01)) { "allocation must add up to 100%" }
        }
        for (e in extras) require(e.period in 0 until periods && e.amount > 0) { "invalid extra purchase" }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name); put("start", start.toString()); put("frequency", frequency.key)
        put("periods", periods); put("amount", amount); put("fiat", fiat)
        put("allocation", JSONObject().apply { allocation.forEach { (k, v) -> put(k, v) } })
        put("symbols", JSONObject().apply { symbols.forEach { (k, v) -> put(k, v) } })
        put("extras", JSONArray().apply { extras.forEach { put(it.toJson()) } })
        put("follow_targets", followTargets)
    }

    companion object {
        fun fromJson(o: JSONObject): DcaPlan {
            val alloc = o.optJSONObject("allocation") ?: JSONObject()
            val syms = o.optJSONObject("symbols") ?: JSONObject()
            val ex = o.optJSONArray("extras") ?: JSONArray()
            return DcaPlan(
                o.optString("name", "DCA"), LocalDate.parse(o.getString("start")), Frequency.of(o.getString("frequency")),
                o.getInt("periods"), o.getDouble("amount"), o.getString("fiat"),
                alloc.keys().asSequence().associateWith { alloc.getDouble(it) },
                syms.keys().asSequence().associateWith { syms.getString(it) },
                (0 until ex.length()).map { DcaExtra.fromJson(ex.getJSONObject(it)) },
                o.optBoolean("follow_targets", false),
            )
        }
    }
}

data class DcaItem(val coinId: String, val symbol: String, val amount: Double)

data class Installment(val index: Int, val due: LocalDate, val items: List<DcaItem>) {
    val total: Double get() = items.sumOf { it.amount }
}

fun dueDate(plan: DcaPlan, index: Int): LocalDate = when (plan.frequency) {
    Frequency.WEEKLY -> plan.start.plusWeeks(index.toLong())
    Frequency.BIWEEKLY -> plan.start.plusWeeks(2L * index)
    // plusMonths clamps to the month's last day, like the desktop's add_months.
    Frequency.MONTHLY -> plan.start.plusMonths(index.toLong())
}

private fun items(plan: DcaPlan, index: Int, values: Map<String, Double>?, targets: Map<String, Double>?): List<DcaItem> {
    val split = if (plan.followTargets && !targets.isNullOrEmpty()) {
        splitContribution(plan.amount, values ?: emptyMap(), targets)
    } else {
        plan.allocation.filterValues { it > 0 }.mapValues { (_, pct) -> plan.amount * pct / 100.0 }
    }
    val merged = LinkedHashMap(split)
    val symbols = plan.extras.associate { it.coinId to it.symbol } + plan.symbols
    for (e in plan.extras) if (e.period == index) merged[e.coinId] = (merged[e.coinId] ?: 0.0) + e.amount
    return merged.entries.sortedByDescending { it.value }.filter { it.value > 0 }
        .map { DcaItem(it.key, symbols[it.key] ?: it.key.uppercase(), it.value) }
}

fun schedule(plan: DcaPlan, values: Map<String, Double>? = null, targets: Map<String, Double>? = null): List<Installment> =
    (0 until plan.periods).map { Installment(it, dueDate(plan, it), items(plan, it, values, targets)) }

data class Progress(val done: Int, val total: Int, val invested: Double, val planned: Double) {
    val remaining: Double get() = planned - invested
}

fun progress(installments: List<Installment>, done: Set<Int>) = Progress(
    installments.count { it.index in done }, installments.size,
    installments.filter { it.index in done }.sumOf { it.total }, installments.sumOf { it.total },
)

/**
 * The installment to show on the "this period" card and its status:
 * `overdue` (an earlier period was skipped), `due` or `upcoming`.
 * Null means the plan is finished.
 */
fun current(installments: List<Installment>, done: Set<Int>, today: LocalDate): Pair<Installment, String>? {
    val pending = installments.filter { it.index !in done }
    val first = pending.firstOrNull() ?: return null
    val next = installments.firstOrNull { it.index == first.index + 1 }
    if (first.due.isAfter(today)) return first to "upcoming"
    if (next != null && !next.due.isAfter(today)) return first to "overdue"
    return first to "due"
}

fun daysBetween(a: LocalDate, b: LocalDate): Long = ChronoUnit.DAYS.between(a, b)
