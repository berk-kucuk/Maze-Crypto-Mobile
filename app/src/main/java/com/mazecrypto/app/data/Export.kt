package com.mazecrypto.app.data

import com.mazecrypto.app.core.Trade
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Trade import/export in the desktop's formats (core/export.py), so trades
 * move between Maze Crypto on the computer and on the phone, plus the yearly
 * realised profit/loss statement.
 */
object Export {
    private val FIELDS = listOf("date", "side", "coin_id", "symbol", "amount", "cost", "fee", "fiat", "usd_rate", "platform", "note")
    private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")

    private fun d(v: Double) = if (v == Math.floor(v) && !v.isInfinite() && kotlin.math.abs(v) < 1e15) "${v.toLong()}.0" else v.toString()

    private fun row(t: Trade): Map<String, String> = mapOf(
        "date" to ISO.format(LocalDateTime.ofEpochSecond(t.ts, 0, ZoneOffset.UTC)), "side" to t.side,
        "coin_id" to t.coinId, "symbol" to t.symbol, "amount" to d(t.amount), "cost" to d(t.cost),
        "fee" to d(t.fee), "fiat" to t.fiat, "usd_rate" to (t.usdRate?.let { d(it) } ?: ""),
        "platform" to t.platform, "note" to t.note,
    )

    // Spreadsheet apps execute cells starting with = + - @ — neutralise them.
    private fun safeCell(v: String) = if (v.isNotEmpty() && v[0] in "=+-@\t\r") "'$v" else v

    private fun csvCell(v: String) =
        if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"") + "\"" else v

    fun toCsv(trades: List<Trade>): String = buildString {
        append(FIELDS.joinToString(",")).append('\n')
        for (t in trades) {
            val r = row(t).toMutableMap()
            r["platform"] = safeCell(r.getValue("platform"))
            r["note"] = safeCell(r.getValue("note"))
            append(FIELDS.joinToString(",") { csvCell(r.getValue(it)) }).append('\n')
        }
    }

    fun toJson(trades: List<Trade>): String = JSONObject()
        .put("format", "maze-crypto-trades").put("version", 1)
        .put("trades", JSONArray().apply { trades.forEach { put(JSONObject(row(it))) } })
        .toString(2)

    private fun parseDate(s: String): Long {
        val v = s.trim()
        runCatching { return Instant.parse(v).epochSecond }
        runCatching { return LocalDateTime.parse(v, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).toEpochSecond(ZoneOffset.UTC) }
        runCatching { return LocalDate.parse(v).atStartOfDay().toEpochSecond(ZoneOffset.UTC) }
        runCatching { return LocalDate.parse(v, DateTimeFormatter.ofPattern("dd.MM.yyyy")).atStartOfDay().toEpochSecond(ZoneOffset.UTC) }
        throw IllegalArgumentException("bad date: $v")
    }

    private fun fromRow(r: Map<String, String>): Trade {
        fun strip(v: String) = if (v.length >= 2 && v[0] == '\'' && v[1] in "=+-@") v.substring(1) else v
        fun req(k: String) = r[k] ?: throw IllegalArgumentException("missing $k")
        return ProfileStore.checkTrade(Trade(
            ts = parseDate(req("date")), coinId = req("coin_id"), symbol = req("symbol"),
            amount = req("amount").toDouble(), cost = req("cost").toDouble(), fiat = req("fiat"),
            fee = r["fee"]?.takeIf { it.isNotBlank() }?.toDouble() ?: 0.0,
            side = (r["side"]?.takeIf { it.isNotBlank() } ?: "buy").lowercase(),
            platform = strip(r["platform"] ?: ""), note = strip(r["note"] ?: ""),
            usdRate = r["usd_rate"]?.takeIf { it.isNotBlank() }?.toDouble(),
        ))
    }

    /** RFC 4180 rows. */
    private fun parseCsv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { cell.append('"'); i++ } else quoted = false
                } else cell.append(c)
            } else when (c) {
                '"' -> quoted = true
                ',' -> { row += cell.toString(); cell.clear() }
                '\r' -> {}
                '\n' -> { row += cell.toString(); cell.clear(); rows += row; row = mutableListOf() }
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row += cell.toString(); rows += row }
        return rows.filter { r -> r.any { it.isNotEmpty() } }
    }

    /** Parse trades; throws naming the first bad line. */
    fun fromCsv(text: String): List<Trade> {
        val rows = parseCsv(text.removePrefix("﻿"))
        if (rows.isEmpty()) return emptyList()
        val header = rows[0]
        val missing = listOf("date", "coin_id", "symbol", "amount", "cost", "fiat").filter { it !in header }
        if (missing.isNotEmpty()) throw IllegalArgumentException("missing columns: ${missing.sorted().joinToString(", ")}")
        return rows.drop(1).mapIndexed { n, r ->
            try {
                fromRow(header.indices.associate { header[it] to (r.getOrNull(it) ?: "") })
            } catch (e: Exception) {
                throw IllegalArgumentException("line ${n + 2}: ${e.message}")
            }
        }
    }

    fun fromJson(text: String): List<Trade> {
        val data = JSONObject(text)
        if (data.optString("format") != "maze-crypto-trades") throw IllegalArgumentException("not a Maze Crypto trade file")
        return data.getJSONArray("trades").objects().map { o ->
            fromRow(o.keys().asSequence().associateWith { k -> if (o.isNull(k)) "" else o.get(k).toString() })
        }
    }

    data class Disposal(val ts: Long, val symbol: String, val amount: Double, val proceeds: Double, val costBasis: Double) {
        val gain get() = proceeds - costBasis
    }

    /** Sells in [year] with their average-cost basis. Only trades in [fiat] are included. */
    fun yearlyReport(trades: List<Trade>, year: Int, fiat: String): List<Disposal> {
        val pos = HashMap<String, Pair<Double, Double>>()
        val out = mutableListOf<Disposal>()
        for (t in trades.sortedWith(compareBy({ it.ts }, { it.id }))) {
            if (t.fiat != fiat) continue
            val y = LocalDateTime.ofEpochSecond(t.ts, 0, ZoneOffset.UTC).year
            val (amount, cost) = pos[t.coinId] ?: (0.0 to 0.0)
            if (t.side == "buy") {
                pos[t.coinId] = (amount + t.amount) to (cost + t.totalCost)
            } else if (amount > 1e-12) {
                val qty = minOf(t.amount, amount)
                val basis = cost * qty / amount
                pos[t.coinId] = (amount - qty) to (cost - basis)
                if (y == year) out += Disposal(t.ts, t.symbol, qty, t.totalCost, basis)
            }
        }
        return out
    }

    fun reportCsv(ds: List<Disposal>, fiat: String): String = buildString {
        fun f(v: Double) = String.format(Locale.US, "%.2f", v)
        append("date,symbol,amount,proceeds_$fiat,cost_basis_$fiat,gain_$fiat\n")
        for (x in ds) {
            append(DateTimeFormatter.ISO_LOCAL_DATE.format(LocalDateTime.ofEpochSecond(x.ts, 0, ZoneOffset.UTC)))
            append(",${x.symbol},${d(x.amount)},${f(x.proceeds)},${f(x.costBasis)},${f(x.gain)}\n")
        }
        append("TOTAL,,,${f(ds.sumOf { it.proceeds })},${f(ds.sumOf { it.costBasis })},${f(ds.sumOf { it.gain })}\n")
    }
}
