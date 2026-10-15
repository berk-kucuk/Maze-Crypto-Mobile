package com.mazecrypto.app.data

import com.mazecrypto.app.core.DcaPlan
import com.mazecrypto.app.core.PortfolioError
import com.mazecrypto.app.core.Rule
import com.mazecrypto.app.core.RuleState
import com.mazecrypto.app.core.Trade
import com.mazecrypto.app.core.buildPositions
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** The user's own data: trades, targets, rules, DCA plan and chart levels. */
data class Profile(
    val trades: List<Trade> = emptyList(),
    val targets: Map<String, Double> = emptyMap(),
    val targetSymbols: Map<String, String> = emptyMap(),
    val rules: List<Rule> = emptyList(),
    val ruleState: Map<String, RuleState> = emptyMap(),
    val dcaPlan: DcaPlan? = null,
    val dcaDone: Set<Int> = emptySet(),
    val levels: Map<String, List<Double>> = emptyMap(),
    val nextTradeId: Long = 1,
)

/**
 * One JSON file, written atomically. Small on purpose: a few hundred trades
 * are a few dozen kilobytes, and a single file is trivial to back up.
 */
class ProfileStore(private val file: File) {

    fun load(): Profile {
        if (!file.exists()) return Profile()
        return try {
            parse(JSONObject(file.readText()))
        } catch (e: Exception) {
            file.renameTo(File(file.parentFile, file.name + ".broken"))
            Profile()
        }
    }

    fun save(p: Profile) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(toJson(p).toString())
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    private fun parse(o: JSONObject): Profile {
        val trades = o.optJSONArray("trades")?.objects()?.mapNotNull { runCatching { Trade.fromJson(it) }.getOrNull() } ?: emptyList()
        val tg = o.optJSONObject("targets") ?: JSONObject()
        val ts = o.optJSONObject("target_symbols") ?: JSONObject()
        val rs = o.optJSONObject("rule_state") ?: JSONObject()
        val lv = o.optJSONObject("levels") ?: JSONObject()
        val done = o.optJSONArray("dca_done")
        return Profile(
            trades = trades,
            targets = tg.keys().asSequence().associateWith { tg.getDouble(it) },
            targetSymbols = ts.keys().asSequence().associateWith { ts.getString(it) },
            rules = o.optJSONArray("rules")?.objects()?.mapNotNull { runCatching { Rule.fromJson(it) }.getOrNull() } ?: emptyList(),
            ruleState = rs.keys().asSequence().associateWith { RuleState.fromJson(rs.getJSONObject(it)) },
            dcaPlan = o.optJSONObject("dca_plan")?.let { runCatching { DcaPlan.fromJson(it) }.getOrNull() },
            dcaDone = done?.let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() } ?: emptySet(),
            levels = lv.keys().asSequence().associateWith { k ->
                val a = lv.getJSONArray(k)
                (0 until a.length()).map { a.getDouble(it) }
            },
            nextTradeId = maxOf(o.optLong("next_trade_id", 1), (trades.maxOfOrNull { it.id } ?: 0) + 1),
        )
    }

    private fun toJson(p: Profile): JSONObject = JSONObject().apply {
        put("version", 1)
        put("trades", JSONArray().apply { p.trades.forEach { put(it.toJson()) } })
        put("targets", JSONObject().apply { p.targets.forEach { (k, v) -> put(k, v) } })
        put("target_symbols", JSONObject().apply { p.targetSymbols.forEach { (k, v) -> put(k, v) } })
        put("rules", JSONArray().apply { p.rules.forEach { put(it.toJson()) } })
        put("rule_state", JSONObject().apply { p.ruleState.forEach { (k, v) -> put(k, v.toJson()) } })
        p.dcaPlan?.let { put("dca_plan", it.toJson()) }
        put("dca_done", JSONArray(p.dcaDone.sorted()))
        put("levels", JSONObject().apply { p.levels.forEach { (k, v) -> put(k, JSONArray(v)) } })
        put("next_trade_id", p.nextTradeId)
    }

    companion object {
        /** Validate a trade the way the desktop store does before it is saved. */
        fun checkTrade(t: Trade): Trade {
            if (t.side != "buy" && t.side != "sell") throw ValidationError("side")
            return t.copy(
                coinId = V.coinId(t.coinId), symbol = V.symbol(t.symbol), fiat = V.fiat(t.fiat),
                amount = V.number(t.amount, "amount", allowZero = false), cost = V.number(t.cost, "cost"),
                fee = V.number(t.fee, "fee"),
                usdRate = t.usdRate?.let { V.number(it, "usd_rate", allowZero = false) },
                ts = V.number(t.ts, "ts", allowZero = false).toLong(),
                platform = t.platform.trim().take(60), note = t.note.trim().take(2000),
            )
        }

        /** True when replaying [trades] never sells more than is held. */
        fun consistent(trades: List<Trade>, base: String, rate: Double?): Boolean =
            try {
                buildPositions(trades, base, rate)
                true
            } catch (e: PortfolioError) {
                false
            }
    }
}
