package com.mazecrypto.app.core

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Technical indicators (port of core/indicators.py).
 *
 * Every function takes closes and returns an array of the same length;
 * positions where the indicator is not defined yet are NaN, so results line
 * up index-for-index with the candles they were computed from.
 */
object Ind {
    fun sma(x: DoubleArray, period: Int): DoubleArray {
        val out = DoubleArray(x.size) { Double.NaN }
        if (period <= 0 || x.size < period) return out
        var sum = 0.0
        for (i in x.indices) {
            sum += x[i]
            if (i >= period) sum -= x[i - period]
            if (i >= period - 1) out[i] = sum / period
        }
        return out
    }

    /** Exponential moving average seeded with the SMA of the first [period] values. */
    fun ema(x: DoubleArray, period: Int): DoubleArray {
        val out = DoubleArray(x.size) { Double.NaN }
        if (period <= 0 || x.size < period) return out
        val k = 2.0 / (period + 1)
        out[period - 1] = x.take(period).average()
        for (i in period until x.size) out[i] = x[i] * k + out[i - 1] * (1 - k)
        return out
    }

    /** Wilder's RSI. */
    fun rsi(x: DoubleArray, period: Int = 14): DoubleArray {
        val out = DoubleArray(x.size) { Double.NaN }
        if (x.size <= period) return out
        val gain = DoubleArray(x.size - 1) { max(0.0, x[it + 1] - x[it]) }
        val loss = DoubleArray(x.size - 1) { max(0.0, x[it] - x[it + 1]) }
        var g = gain.take(period).average()
        var l = loss.take(period).average()
        fun value(g: Double, l: Double) = if (l == 0.0) (if (g > 0) 100.0 else 50.0) else 100.0 - 100.0 / (1.0 + g / l)
        out[period] = value(g, l)
        for (i in period + 1 until x.size) {
            g = (g * (period - 1) + gain[i - 1]) / period
            l = (l * (period - 1) + loss[i - 1]) / period
            out[i] = value(g, l)
        }
        return out
    }

    class Macd(val macd: DoubleArray, val signal: DoubleArray, val hist: DoubleArray)

    fun macd(x: DoubleArray, fast: Int = 12, slow: Int = 26, signal: Int = 9): Macd {
        val f = ema(x, fast)
        val s = ema(x, slow)
        val line = DoubleArray(x.size) { f[it] - s[it] }
        val sig = DoubleArray(x.size) { Double.NaN }
        val start = line.indexOfFirst { !it.isNaN() }
        if (start >= 0 && x.size - start >= signal) {
            val e = ema(line.copyOfRange(start, line.size), signal)
            e.copyInto(sig, start)
        }
        return Macd(line, sig, DoubleArray(x.size) { line[it] - sig[it] })
    }

    /** Simple period-over-period returns (length n-1). */
    fun returns(x: DoubleArray): DoubleArray =
        if (x.size < 2) DoubleArray(0) else DoubleArray(x.size - 1) { x[it + 1] / x[it] - 1.0 }

    fun std(x: DoubleArray, ddof: Int = 0): Double {
        if (x.size - ddof <= 0) return 0.0
        val m = x.average()
        return sqrt(x.sumOf { (it - m) * (it - m) } / (x.size - ddof))
    }

    /** Pearson correlation of the *returns* of two aligned price series. */
    fun correlation(a: DoubleArray, b: DoubleArray): Double? {
        val n = minOf(a.size, b.size)
        if (n < 3) return null
        val ra = returns(a.copyOfRange(a.size - n, a.size))
        val rb = returns(b.copyOfRange(b.size - n, b.size))
        val sa = std(ra)
        val sb = std(rb)
        if (sa == 0.0 || sb == 0.0) return null
        val ma = ra.average()
        val mb = rb.average()
        val cov = ra.indices.sumOf { (ra[it] - ma) * (rb[it] - mb) } / ra.size
        return cov / (sa * sb)
    }

    /** Annualised standard deviation of returns. */
    fun volatility(x: DoubleArray, periodsPerYear: Int = 365): Double? {
        val r = returns(x)
        if (r.size < 2) return null
        return std(r, 1) * sqrt(periodsPerYear.toDouble())
    }

    fun pctChange(x: DoubleArray, lookback: Int): Double? {
        if (x.size <= lookback || x[x.size - 1 - lookback] == 0.0) return null
        return (x.last() / x[x.size - 1 - lookback] - 1.0) * 100.0
    }

    /** Last value of an indicator array, or null when it is not defined there. */
    fun last(a: DoubleArray): Double? = a.lastOrNull()?.takeUnless { it.isNaN() }

    /** Largest peak-to-trough fall, in percent (negative). */
    fun maxDrawdown(x: DoubleArray): Double? {
        if (x.size < 2) return null
        var peak = x[0]
        var worst = 0.0
        for (v in x) {
            peak = max(peak, v)
            worst = minOf(worst, v / peak - 1.0)
        }
        return worst * 100.0
    }
}

/** What a daily chart looks like now — measurements, never instructions. */
data class TechState(
    val rsi: Double?,
    val rsiState: String,      // overbought | oversold | neutral | ""
    val trend: String,         // above_both | below_both | mixed | ""
    val macdState: String,     // bullish | bearish | ""
    val volatility: Double?,   // annualised, percent
)

fun techState(closes: List<Double>): TechState {
    val x = closes.toDoubleArray()
    val r = if (x.size > 15) Ind.last(Ind.rsi(x)) else null
    val rsiState = when {
        r == null -> ""
        r >= 70 -> "overbought"
        r <= 30 -> "oversold"
        else -> "neutral"
    }
    val s50 = Ind.last(Ind.sma(x, 50))
    val s200 = Ind.last(Ind.sma(x, 200))
    var trend = ""
    if (x.isNotEmpty() && s50 != null && s200 != null) {
        val p = x.last()
        trend = if (p > s50 && p > s200) "above_both" else if (p < s50 && p < s200) "below_both" else "mixed"
    }
    val h = Ind.last(Ind.macd(x).hist)
    val macdState = if (h == null) "" else if (h > 0) "bullish" else "bearish"
    val vol = if (x.size > 10) Ind.volatility(x.copyOfRange(maxOf(0, x.size - 91), x.size)) else null
    return TechState(r, rsiState, trend, macdState, vol?.times(100))
}
