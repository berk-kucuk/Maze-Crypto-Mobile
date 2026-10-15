package com.mazecrypto.app.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10

/** Number formatting that follows the interface language (1,234.56 vs 1.234,56). */
object Fmt {
    val SYMBOLS = mapOf(
        "USD" to "$", "EUR" to "€", "TRY" to "₺", "GBP" to "£", "JPY" to "¥", "CHF" to "CHF ", "KRW" to "₩",
        "INR" to "₹", "BRL" to "R$", "PLN" to "zł", "CAD" to "C$", "AUD" to "A$",
    )
    const val DASH = "—"
    const val MINUS = "−"     // typographic minus: same width as the digits

    private fun sep(s: String): String =
        if (I18n.language == "tr") s.replace(",", "\u0000").replace(".", ",").replace("\u0000", ".") else s

    fun decimalsFor(value: Double): Int {
        val a = abs(value)
        return when {
            a == 0.0 -> 2
            a >= 10 -> 2
            a >= 1 -> 3
            // Small prices: keep 4 significant digits (0.0001234 -> 0.0001234).
            else -> minOf(10, maxOf(4, 3 - floor(log10(a)).toInt()))
        }
    }

    fun num(value: Double?, decimals: Int? = null): String {
        if (value == null || !value.isFinite()) return DASH
        val d = decimals ?: decimalsFor(value)
        return sep(String.format(Locale.US, "%,.${d}f", value)).replaceFirst("-", MINUS)
    }

    fun money(value: Double?, currency: String, decimals: Int? = null): String {
        if (value == null || !value.isFinite()) return DASH
        val sym = SYMBOLS[currency]
        val body = num(abs(value), decimals)
        val sign = if (value < 0) MINUS else ""
        return if (sym != null) "$sign$sym$body" else "$sign$body $currency"
    }

    fun signedMoney(value: Double?, currency: String, decimals: Int? = 2): String {
        if (value == null || !value.isFinite()) return DASH
        return (if (value > 0) "+" else "") + money(value, currency, decimals)
    }

    fun pct(value: Double?, decimals: Int = 2, signed: Boolean = true): String {
        if (value == null || !value.isFinite()) return DASH
        val body = sep(String.format(Locale.US, "%.${decimals}f", abs(value)))
        val rounded = String.format(Locale.US, "%.${decimals}f", abs(value)).toDouble()
        val sign = if (value < 0 && rounded != 0.0) MINUS else if (signed && value > 0) "+" else ""
        return if (I18n.language == "tr") "$sign%$body" else "$sign$body%"
    }

    fun compact(value: Double?): String {
        if (value == null || !value.isFinite()) return DASH
        for ((div, suf) in listOf(1e12 to "T", 1e9 to "B", 1e6 to "M", 1e3 to "K")) {
            if (abs(value) >= div) return sep(String.format(Locale.US, "%.2f", value / div)) + suf
        }
        return num(value)
    }

    /** Money that fits a narrow widget cell: ₺4.03M rather than ₺4,029,426.00. */
    fun moneyShort(value: Double?, currency: String, maxChars: Int = 9): String {
        if (value == null || !value.isFinite()) return DASH
        val full = money(value, currency)
        if (full.length <= maxChars) return full
        val noCents = money(value, currency, 0)
        if (noCents.length <= maxChars && abs(value) >= 100) return noCents
        val sym = SYMBOLS[currency] ?: ""
        val sign = if (value < 0) MINUS else ""
        return "$sign$sym${compact(abs(value))}" + if (sym.isEmpty()) " $currency" else ""
    }

    /** Coin quantities: up to 8 decimals, trailing zeros trimmed. */
    fun amount(value: Double?): String {
        if (value == null || !value.isFinite()) return DASH
        val s = String.format(Locale.US, "%,.8f", value).trimEnd('0').trimEnd('.')
        return sep(s)
    }

    fun date(tsSeconds: Long): String {
        val pattern = if (I18n.language == "tr") "dd.MM.yyyy" else "yyyy-MM-dd"
        return DateTimeFormatter.ofPattern(pattern).format(Instant.ofEpochSecond(tsSeconds).atZone(ZoneId.systemDefault()))
    }

    fun date(d: java.time.LocalDate): String =
        DateTimeFormatter.ofPattern(if (I18n.language == "tr") "dd.MM.yyyy" else "yyyy-MM-dd").format(d)

    fun clock(tsMillis: Long): String =
        DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(tsMillis).atZone(ZoneId.systemDefault()))

    fun age(seconds: Double): String {
        val s = seconds.toLong().coerceAtLeast(0)
        return when {
            s < 90 -> "${s}s"
            s < 5400 -> "${s / 60}m"
            s < 172800 -> "${s / 3600}h"
            else -> "${s / 86400}d"
        }
    }

    /** Accept both 1,234.5 and 1.234,5 (and plain 1234,5). */
    fun parseNumber(text: String): Double {
        var t = text.trim().replace(" ", "").replace("_", "").replace(MINUS, "-")
        require(t.isNotEmpty()) { "empty" }
        if (t.contains(",") && t.contains(".")) {
            t = if (t.lastIndexOf(',') > t.lastIndexOf('.')) t.replace(".", "").replace(",", ".") else t.replace(",", "")
        } else if (t.contains(",")) {
            t = if (t.count { it == ',' } == 1) t.replace(",", ".") else t.replace(",", "")
        }
        val v = t.toDouble()
        require(v.isFinite()) { "not finite" }
        return v
    }

    fun parseOrNull(text: String): Double? = runCatching { parseNumber(text) }.getOrNull()
}
