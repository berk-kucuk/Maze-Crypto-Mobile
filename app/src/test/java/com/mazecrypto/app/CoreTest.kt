package com.mazecrypto.app

import com.mazecrypto.app.core.Condition
import com.mazecrypto.app.core.DcaExtra
import com.mazecrypto.app.core.DcaPlan
import com.mazecrypto.app.core.Fmt
import com.mazecrypto.app.core.Frequency
import com.mazecrypto.app.core.I18n
import com.mazecrypto.app.core.Ind
import com.mazecrypto.app.core.OTHER
import com.mazecrypto.app.core.PortfolioError
import com.mazecrypto.app.core.Rule
import com.mazecrypto.app.core.RuleContext
import com.mazecrypto.app.core.RuleState
import com.mazecrypto.app.core.Trade
import com.mazecrypto.app.core.bucketWeights
import com.mazecrypto.app.core.buildPositions
import com.mazecrypto.app.core.current
import com.mazecrypto.app.core.dataNeeds
import com.mazecrypto.app.core.drift
import com.mazecrypto.app.core.dueDate
import com.mazecrypto.app.core.evaluateRules
import com.mazecrypto.app.core.progress
import com.mazecrypto.app.core.schedule
import com.mazecrypto.app.core.splitContribution
import com.mazecrypto.app.core.summarize
import com.mazecrypto.app.core.validateTargets
import com.mazecrypto.app.data.Export
import com.mazecrypto.app.data.MarketService
import com.mazecrypto.app.data.resample
import com.mazecrypto.app.core.Candle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/** Ports of the desktop's core tests (tests/test_*.py), so both apps compute the same numbers. */
class CoreTest {
    private val e = 1e-6

    private fun buy(ts: Long, coin: String, amount: Double, cost: Double, fee: Double = 0.0, rate: Double? = 40.0, fiat: String = "TRY") =
        Trade(ts, coin, coin.uppercase().take(4), amount, cost, fiat, fee, "buy", usdRate = rate)

    private fun sell(ts: Long, coin: String, amount: Double, cost: Double, fee: Double = 0.0, rate: Double? = 40.0) =
        Trade(ts, coin, coin.uppercase().take(4), amount, cost, "TRY", fee, "sell", usdRate = rate)

    private inline fun throws(block: () -> Unit) {
        try { block(); fail("expected an exception") } catch (_: Exception) {}
    }

    // ── portfolio ───────────────────────────────────────────────────────────
    @Test fun averageCostIncludesFees() {
        val p = buildPositions(listOf(buy(1, "bitcoin", 0.01, 1000.0, 10.0), buy(2, "bitcoin", 0.01, 2000.0, 10.0)), "TRY").getValue("bitcoin")
        assertEquals(0.02, p.amount, e)
        assertEquals(3020.0, p.costFiat, e)
        assertEquals(151000.0, p.avgCostFiat!!, e)
        assertEquals(20.0, p.feesFiat, e)
    }

    @Test fun usdBasisIsHistorical() {
        val p = buildPositions(listOf(buy(1, "eth", 1.0, 4000.0, rate = 40.0), buy(2, "eth", 1.0, 4000.0, rate = 50.0)), "TRY")
        assertEquals(180.0, p.getValue("eth").costUsd!!, e)
    }

    @Test fun sellBooksRealisedAndKeepsAverage() {
        val p = buildPositions(listOf(buy(1, "sol", 10.0, 1000.0), sell(2, "sol", 4.0, 600.0)), "TRY").getValue("sol")
        assertEquals(6.0, p.amount, e)
        assertEquals(100.0, p.avgCostFiat!!, e)
        assertEquals(200.0, p.realizedFiat, e)
    }

    @Test fun sellEverythingResets() {
        val p = buildPositions(listOf(buy(1, "sol", 2.0, 100.0), sell(2, "sol", 2.0, 150.0)), "TRY").getValue("sol")
        assertEquals(0.0, p.amount, 0.0)
        assertEquals(0.0, p.costFiat, 0.0)
        assertEquals(50.0, p.realizedFiat, e)
    }

    @Test fun sellWithoutPositionThrows() {
        throws { buildPositions(listOf(sell(1, "sol", 1.0, 10.0)), "TRY") }
        throws { buildPositions(listOf(buy(1, "sol", 1.0, 10.0), sell(2, "sol", 1.5, 10.0)), "TRY") }
    }

    @Test fun missingRateMakesUsdUnknown() {
        val p = buildPositions(listOf(buy(1, "btc", 1.0, 10.0, rate = null)), "TRY").getValue("btc")
        assertNull(p.costUsd)
        assertNull(p.avgCostUsd)
    }

    @Test fun foreignCurrencyGoesThroughUsd() {
        val p = buildPositions(listOf(buy(1, "btc", 1.0, 100.0, fiat = "USD", rate = 1.0)), "TRY", 40.0)
        assertEquals(4000.0, p.getValue("btc").costFiat, e)
        try { buildPositions(listOf(buy(1, "btc", 1.0, 100.0, fiat = "EUR", rate = null)), "TRY", 40.0); fail() } catch (_: PortfolioError) {}
    }

    @Test fun summaryPnlAndWeights() {
        val pos = buildPositions(listOf(buy(1, "bitcoin", 1.0, 100.0, rate = 10.0), buy(1, "ethereum", 10.0, 100.0, rate = 10.0)), "TRY")
        val s = summarize(pos, mapOf("bitcoin" to 150.0, "ethereum" to 5.0), mapOf("bitcoin" to 15.0, "ethereum" to 0.5))
        assertEquals(200.0, s.valueFiat, e)
        assertEquals(0.0, s.pnlFiat, e)
        assertEquals(75.0, s.weights.getValue("bitcoin"), e)
        val btc = s.holdings.first { it.position.coinId == "bitcoin" }
        assertEquals(50.0, btc.pnlPct!!, e)
        assertEquals(-50.0, btc.belowCostPct!!, e)
        assertEquals(0.0, s.pnlUsd!!, e)
        assertEquals("bitcoin", s.holdings[0].position.coinId)
    }

    @Test fun targetsDriftAndSplit() {
        assertEquals(mapOf("a" to 50.0), validateTargets(mapOf("a" to 50.0, "b" to 0.0)))
        throws { validateTargets(mapOf("a" to 60.0, "b" to 50.0)) }
        val targets = mapOf("bitcoin" to 50.0, "tether" to 10.0, OTHER to 40.0)
        val weights = mapOf("bitcoin" to 60.0, "pepe" to 30.0, "dogecoin" to 10.0)
        assertEquals(mapOf("bitcoin" to 60.0, "tether" to 0.0, OTHER to 40.0), bucketWeights(weights, targets))
        val rows = drift(weights, targets, 1000.0).associateBy { it.bucket }
        assertEquals(10.0, rows.getValue("bitcoin").diffPct, e)
        assertEquals(-100.0, rows.getValue("bitcoin").rebalanceFiat, e)
        assertEquals(100.0, rows.getValue("tether").rebalanceFiat, e)
        val t2 = mapOf("bitcoin" to 50.0, "ethereum" to 50.0)
        assertEquals(mapOf("ethereum" to 100.0), splitContribution(100.0, mapOf("bitcoin" to 300.0, "ethereum" to 100.0), t2))
        assertEquals(250.0, splitContribution(250.0, mapOf("bitcoin" to 50.0), t2).values.sum(), e)
        assertTrue(splitContribution(0.0, emptyMap(), t2).isEmpty())
    }

    // ── DCA ─────────────────────────────────────────────────────────────────
    private fun plan(start: LocalDate = LocalDate.of(2026, 10, 5), freq: Frequency = Frequency.WEEKLY,
                     alloc: Map<String, Double> = mapOf("bitcoin" to 50.0, "ethereum" to 50.0),
                     extras: List<DcaExtra> = listOf(DcaExtra(0, "tether", "USDT", 2500.0)), follow: Boolean = false) =
        DcaPlan("p", start, freq, 5, 4500.0, "TRY", alloc, mapOf("bitcoin" to "BTC", "ethereum" to "ETH"), extras, follow)

    @Test fun scheduleWithExtra() {
        val s = schedule(plan())
        assertEquals(5, s.size)
        assertEquals(7000.0, s[0].total, e)
        assertEquals(setOf("BTC", "ETH", "USDT"), s[0].items.map { it.symbol }.toSet())
        assertEquals(LocalDate.of(2026, 11, 2), s[4].due)
        assertEquals(LocalDate.of(2026, 11, 2), dueDate(plan(freq = Frequency.BIWEEKLY), 2))
        assertEquals(LocalDate.of(2026, 2, 28), dueDate(plan(start = LocalDate.of(2026, 1, 31), freq = Frequency.MONTHLY), 1))
    }

    @Test fun planValidation() {
        plan().validate()
        throws { plan(alloc = mapOf("bitcoin" to 60.0)).validate() }
        throws { plan(extras = listOf(DcaExtra(9, "tether", "USDT", 1.0))).validate() }
        plan(alloc = emptyMap(), follow = true).validate()
    }

    @Test fun currentCardAndProgress() {
        val s = schedule(plan())
        assertEquals(s[0] to "upcoming", current(s, emptySet(), LocalDate.of(2026, 10, 1)))
        assertEquals(s[0] to "due", current(s, emptySet(), LocalDate.of(2026, 10, 7)))
        assertEquals(s[0] to "overdue", current(s, emptySet(), LocalDate.of(2026, 10, 13)))
        assertEquals(s[1] to "due", current(s, setOf(0), LocalDate.of(2026, 10, 13)))
        assertNull(current(s, setOf(0, 1, 2, 3, 4), LocalDate.of(2027, 1, 1)))
        val p = progress(s, setOf(0, 1))
        assertEquals(2, p.done)
        assertEquals(11500.0, p.invested, e)
    }

    // ── indicators ──────────────────────────────────────────────────────────
    @Test fun smaEmaRsi() {
        val s = Ind.sma(doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0), 3)
        assertTrue(s[0].isNaN() && s[1].isNaN())
        assertEquals(listOf(2.0, 3.0, 4.0), s.drop(2))
        val em = Ind.ema(doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0), 3)
        assertEquals(2.0, em[2], e); assertEquals(3.0, em[3], e); assertEquals(5.0, em[5], e)
        assertEquals(100.0, Ind.rsi((1..39).map { it.toDouble() }.toDoubleArray()).last(), e)
        assertEquals(0.0, Ind.rsi((40 downTo 2).map { it.toDouble() }.toDoubleArray()).last(), e)
        assertEquals(50.0, Ind.rsi(DoubleArray(30) { 5.0 }).last(), e)
        val closes = doubleArrayOf(44.34, 44.09, 44.15, 43.61, 44.33, 44.83, 45.10, 45.42, 45.84, 46.08,
            45.89, 46.03, 45.61, 46.28, 46.28, 46.00, 46.03, 46.41, 46.22, 45.64)
        val r = Ind.rsi(closes, 14)
        assertEquals(70.46, r[14], 0.01)
        assertTrue(r[13].isNaN())
    }

    @Test fun macdAndCorrelation() {
        val x = DoubleArray(80) { 100.0 + it * 100.0 / 79 }
        val m = Ind.macd(x)
        assertTrue(Ind.last(m.macd)!! > 0)
        assertTrue(m.signal.take(33).all { it.isNaN() })
        assertFalse(m.signal[33].isNaN())
        val a = doubleArrayOf(100.0, 102.0, 101.0, 105.0, 107.0, 106.0, 110.0)
        assertEquals(1.0, Ind.correlation(a, a)!!, e)
        assertEquals(1.0, Ind.correlation(a, a.map { it * 2 }.toDoubleArray())!!, e)
        assertNull(Ind.correlation(doubleArrayOf(1.0, 1.0, 1.0), doubleArrayOf(1.0, 2.0, 3.0)))
        assertEquals(21.0, Ind.pctChange(doubleArrayOf(100.0, 110.0, 121.0), 2)!!, e)
        assertEquals(0.0, Ind.volatility(doubleArrayOf(100.0, 100.0, 100.0))!!, 0.0)
    }

    // ── rules ───────────────────────────────────────────────────────────────
    @Test fun conditions() {
        val c = Condition("cost_change", "bitcoin", "BTC", pct = -15.0)
        assertEquals(true, c.evaluate(RuleContext(pricesFiat = mapOf("bitcoin" to 84.0), avgCostFiat = mapOf("bitcoin" to 100.0))).active)
        assertEquals(false, c.evaluate(RuleContext(pricesFiat = mapOf("bitcoin" to 90.0), avgCostFiat = mapOf("bitcoin" to 100.0))).active)
        assertNull(c.evaluate(RuleContext(pricesFiat = mapOf("bitcoin" to 90.0))).active)
        val d = Condition("drift", pct = 5.0)
        assertEquals(true, d.evaluate(RuleContext(weights = mapOf("bitcoin" to 57.0, "ethereum" to 43.0),
            targets = mapOf("bitcoin" to 50.0, "ethereum" to 50.0))).active)
        val falling = DoubleArray(60) { 200.0 - it * 100.0 / 59 }.toList()
        val rsi = Condition("rsi", "bitcoin", "BTC", op = "below", value = 30.0, interval = "1d")
        assertEquals(true, rsi.evaluate(RuleContext(closes = mutableMapOf(("bitcoin" to "1d") to falling))).active)
        val rising = DoubleArray(250) { 100.0 + it * 200.0 / 249 }.toList()
        val g = Condition("sma_cross", "bitcoin", "BTC", interval = "1d", fast = 50, slow = 200, direction = "golden")
        assertEquals(true, g.evaluate(RuleContext(closes = mutableMapOf(("bitcoin" to "1d") to rising))).active)
        assertNull(g.evaluate(RuleContext(closes = mutableMapOf(("bitcoin" to "1d") to rising.take(100)))).active)
        val mv = Condition("move", "bitcoin", "BTC", hours = 24, pct = 10.0, direction = "down")
        assertEquals(true, mv.evaluate(RuleContext(closes = mutableMapOf(("bitcoin" to "1h") to List(30) { 100.0 } + 85.0))).active)
        assertEquals(setOf("bitcoin" to "1d", "bitcoin" to "1h"), dataNeeds(listOf(Rule("x", rsi), Rule("y", mv))))
    }

    @Test fun engineEdgeAndCooldown() {
        val h = 3600.0
        val rule = Rule("dip", Condition("cost_change", "bitcoin", "BTC", pct = -15.0), cooldownHours = 24.0)
        val state = HashMap<String, RuleState>()
        val low = RuleContext(pricesFiat = mapOf("bitcoin" to 80.0), avgCostFiat = mapOf("bitcoin" to 100.0))
        val high = RuleContext(pricesFiat = mapOf("bitcoin" to 95.0), avgCostFiat = mapOf("bitcoin" to 100.0))
        assertEquals(1, evaluateRules(listOf(rule), low, state, 1000.0).size)
        assertEquals(0, evaluateRules(listOf(rule), low, state, 1060.0).size)
        assertEquals(0, evaluateRules(listOf(rule), high, state, 1120.0).size)
        assertEquals(0, evaluateRules(listOf(rule), low, state, 1180.0).size)
        assertEquals(0, evaluateRules(listOf(rule), high, state, 2000.0).size)
        assertEquals(1, evaluateRules(listOf(rule), low, state, 1000 + 25 * h).size)
    }

    @Test fun ruleRoundTripMatchesDesktopFormat() {
        val rule = Rule("x", Condition("rsi", "solana", "SOL", op = "above", value = 70.0, interval = "4h"), "note", cooldownHours = 6.0)
        val back = Rule.fromJson(org.json.JSONObject(rule.toJson().toString()))
        assertEquals(rule, back)
        throws { Condition.fromJson(org.json.JSONObject("""{"kind":"exec","cmd":"rm"}""")) }
        val c = Condition.fromJson(org.json.JSONObject("""{"kind":"drift","pct":"7","junk":1}"""))
        assertEquals(7.0, c.pct, e)
    }

    // ── export ──────────────────────────────────────────────────────────────
    private fun ts(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).atStartOfDay().toEpochSecond(ZoneOffset.UTC)
    private val trades = listOf(
        Trade(ts(2025, 1, 5), "bitcoin", "BTC", 0.02, 2000.0, "TRY", 20.0, "buy", "BtcTurk", "=HYPERLINK(evil)", 40.0, 1),
        Trade(ts(2026, 3, 1), "bitcoin", "BTC", 0.01, 3000.0, "TRY", 10.0, "sell", "BtcTurk", "", 45.0, 2),
        Trade(ts(2026, 4, 1), "ethereum", "ETH", 1.0, 100.0, "USD", 0.0, "buy", id = 3),
    )

    @Test fun csvRoundTripAndFormulaGuard() {
        val text = Export.toCsv(trades)
        assertTrue("'=HYPERLINK" in text)
        val back = Export.fromCsv(text)
        assertEquals(trades.map { listOf(it.symbol, it.amount, it.side, it.note) }, back.map { listOf(it.symbol, it.amount, it.side, it.note) })
        assertNull(back[2].usdRate)
        try { Export.fromCsv("a,b\n1,2\n"); fail() } catch (x: IllegalArgumentException) { assertTrue(x.message!!.startsWith("missing columns")) }
        try { Export.fromCsv(Export.toCsv(trades.take(1)).replace("0.02", "-1")); fail() } catch (x: IllegalArgumentException) { assertTrue(x.message!!.startsWith("line 2")) }
    }

    @Test fun jsonRoundTripAndYearlyReport() {
        val back = Export.fromJson(Export.toJson(trades))
        assertEquals(3, back.size)
        assertEquals("=HYPERLINK(evil)", back[0].note)
        throws { Export.fromJson("""{"format": "other"}""") }
        val d = Export.yearlyReport(trades, 2026, "TRY").single()
        assertEquals(1010.0, d.costBasis, e)
        assertEquals(2990.0, d.proceeds, e)
        assertTrue("TOTAL" in Export.reportCsv(listOf(d), "TRY"))
        assertTrue(Export.yearlyReport(trades, 2025, "TRY").isEmpty())
    }

    // ── formatting ──────────────────────────────────────────────────────────
    @Test fun numbers() {
        assertEquals(1234.56, Fmt.parseNumber("1.234,56"), e)
        assertEquals(1234.56, Fmt.parseNumber("1,234.56"), e)
        assertEquals(0.5, Fmt.parseNumber("0,5"), e)
        throws { Fmt.parseNumber("abc") }
        I18n.setLanguage("tr")
        assertEquals("−%1,50", Fmt.pct(-1.5))
        assertEquals("₺4.045.809,00", Fmt.money(4045809.0, "TRY"))
        I18n.setLanguage("en")
        assertEquals("+2.00%", Fmt.pct(2.0))
        assertEquals("−$3.500", Fmt.money(-3.5, "USD"))
        assertEquals("0.0001235", Fmt.num(0.000123456))
        assertEquals("₺4.03M", Fmt.moneyShort(4029426.0, "TRY"))
        assertEquals("$112,480", Fmt.moneyShort(112480.0, "USD"))
    }

    // ── market data ─────────────────────────────────────────────────────────
    @Test fun plausibleAndResample() {
        val c = { ts: Long, v: Double -> Candle(ts, v, v, v, v, 1.0) }
        assertFalse(MarketService.plausible(List(12) { c(it.toLong(), 5.0) }, null))      // frozen
        assertFalse(MarketService.plausible(listOf(c(0, 1.0), c(1, 2.0)), 10.0))         // mismatched
        assertTrue(MarketService.plausible(listOf(c(0, 1.0), c(1, 2.0)), 2.1))
        val hourly = (0 until 8).map { Candle(it * 3600L, 1.0 + it, 2.0 + it, 0.5 + it, 1.5 + it, 1.0) }
        val four = resample(hourly, 4 * 3600L)
        assertEquals(2, four.size)
        assertEquals(1.0, four[0].open, e); assertEquals(5.0, four[0].high, e); assertEquals(4.5, four[0].close, e)
        assertEquals(4.0, four[0].volume, e)
    }
}
