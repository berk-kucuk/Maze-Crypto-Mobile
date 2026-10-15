package com.mazecrypto.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mazecrypto.app.R
import com.mazecrypto.app.core.Candle
import com.mazecrypto.app.core.Coin
import com.mazecrypto.app.core.Fmt
import com.mazecrypto.app.core.Ind
import com.mazecrypto.app.core.Interval
import com.mazecrypto.app.core.t
import com.mazecrypto.app.data.CandleSet
import com.mazecrypto.app.data.Maze
import com.mazecrypto.app.ui.Nav
import com.mazecrypto.app.ui.components.ChangePill
import com.mazecrypto.app.ui.components.Chip
import com.mazecrypto.app.ui.components.Explain
import com.mazecrypto.app.ui.components.GhostButton
import com.mazecrypto.app.ui.components.Glass
import com.mazecrypto.app.ui.components.IconBtn
import com.mazecrypto.app.ui.components.KeyValue
import com.mazecrypto.app.ui.components.LinkButton
import com.mazecrypto.app.ui.components.SectionTitle
import com.mazecrypto.app.ui.components.Segmented
import com.mazecrypto.app.ui.theme.Inter
import com.mazecrypto.app.ui.theme.LocalPalette
import com.mazecrypto.app.ui.theme.Palette
import com.mazecrypto.app.ui.theme.Type
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun ChartScreen(nav: Nav, coinId: String) {
    val p = LocalPalette.current
    val settings by Maze.settings.collectAsStateWithLifecycle()
    val snap by Maze.snapshot.collectAsStateWithLifecycle()
    val profile by Maze.profile.collectAsStateWithLifecycle()
    val coin = remember(coinId, settings.watchlist) {
        Maze.trackedCoins().firstOrNull { it.id == coinId } ?: Coin(coinId, coinId.take(10).uppercase(), coinId)
    }
    val row = snap?.row(coinId)
    var interval by remember { mutableStateOf(Interval.of(settings.chartInterval)) }
    var quote by remember { mutableStateOf("fiat") }
    var data by remember { mutableStateOf<CandleSet?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    val expert = settings.expert
    var showSma50 by remember { mutableStateOf(true) }
    var showEma21 by remember { mutableStateOf(expert) }
    var showSma200 by remember { mutableStateOf(expert) }
    var showVol by remember { mutableStateOf(true) }
    var guide by remember { mutableStateOf(false) }
    val inWatch = settings.watchlist.any { it.id == coinId }
    val fav = coinId in settings.favorites

    LaunchedEffect(coinId, interval, quote, settings.fiat) {
        loading = true
        error = null
        try {
            data = Maze.service().candles(coin, quote, interval, 300, row?.priceUsd)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            data = null
            error = t("chart.failed", "err" to (e.message ?: e.javaClass.simpleName))
        }
        loading = false
    }

    // The user's average buy price, in the chart's currency.
    val avgCost = remember(profile, data, snap) {
        val pos = Maze.positions()[coinId]
        val d = data
        if (pos == null || pos.amount <= 0 || d == null) null
        else if (d.quote == settings.fiat) pos.avgCostFiat
        else if (d.quote == "USD") pos.avgCostUsd ?: snap?.fx?.get(settings.fiat)?.let { r -> pos.avgCostFiat?.div(r) }
        else null
    }
    val levels = profile.levels[coinId] ?: emptyList()

    SubScreen(nav, coin.symbol, actions = {
        IconBtn(if (fav) R.drawable.ic_star_fill else R.drawable.ic_star, tint = if (fav) p.accent else p.textMid) {
            Maze.updateSettings { s ->
                val wl = if (s.watchlist.any { it.id == coinId }) s.watchlist else s.watchlist + coin
                s.copy(watchlist = wl, favorites = if (fav) s.favorites - coinId else s.favorites + coinId)
            }
        }
    }) {
        // ── price header ──────────────────────────────────────────────────
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(coin.name, style = Type.bodySmall, color = p.muted)
                Text(Fmt.money(row?.priceFiat, settings.fiat), style = Type.big, color = p.text, maxLines = 1)
            }
            ChangePill(row?.change24h)
        }
        if (!inWatch) {
            LinkButton(t("m.chart.add_watch"), arrow = false) { Maze.updateSettings { s -> s.copy(watchlist = s.watchlist + coin) } }
        }
        Gap(12)
        Segmented(Interval.entries.map { it to it.key.uppercase() }, interval) {
            interval = it
            Maze.updateSettings { s -> s.copy(chartInterval = it.key) }
        }
        if (settings.fiat != "USD") {
            Gap(8)
            Segmented(listOf("fiat" to settings.fiat, "usd" to "USD"), quote, Modifier.width(160.dp)) { quote = it }
        }
        Gap(10)

        // ── the chart ─────────────────────────────────────────────────────
        val d = data
        Glass(Modifier.fillMaxWidth(), padding = 10.dp) {
            when {
                d != null && d.candles.size >= 2 -> CandleChart(d.candles, avgCost, levels, showSma50, showEma21, showSma200, showVol, expert, interval)
                loading -> Box(Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) {
                    Text(t("chart.loading"), style = Type.body, color = p.muted)
                }
                else -> Box(Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) {
                    Text(error ?: t("chart.failed", "err" to ""), style = Type.body, color = p.muted)
                }
            }
            if (d != null) {
                val bits = mutableListOf(d.source, d.quote, t("chart.candles", "n" to d.candles.size))
                if (d.converted) bits += t("chart.converted")
                d.staleAge?.let { bits += t("chart.stale", "age" to Fmt.age(it)) }
                Text(bits.joinToString(" · "), style = Type.small, color = p.muted, modifier = Modifier.padding(top = 6.dp, start = 4.dp))
            }
        }
        Gap(10)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("SMA 50", showSma50) { showSma50 = !showSma50 }
            Chip("EMA 21", showEma21) { showEma21 = !showEma21 }
            Chip("SMA 200", showSma200) { showSma200 = !showSma200 }
            Chip(t("chart.volume"), showVol) { showVol = !showVol }
        }
        Gap(10)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton(t("chart.add_level"), Modifier.weight(1f), icon = R.drawable.ic_plus, enabled = d != null) {
                val last = d?.candles?.lastOrNull()?.close ?: return@GhostButton
                Maze.updateProfile { pr -> pr.copy(levels = pr.levels + (coinId to ((pr.levels[coinId] ?: emptyList()) + last))) }
            }
            if (levels.isNotEmpty()) {
                GhostButton(t("chart.clear_levels"), Modifier.weight(1f)) {
                    Maze.updateProfile { pr -> pr.copy(levels = pr.levels - coinId) }
                }
            }
        }
        Text(t("m.chart.touch_hint"), style = Type.small, color = p.muted, modifier = Modifier.padding(top = 8.dp))

        // ── numbers ───────────────────────────────────────────────────────
        if (row != null) {
            SectionTitle(t("m.chart.market"))
            Glass(Modifier.fillMaxWidth()) {
                val rate = snap?.fx?.get(settings.fiat)
                Row {
                    KeyValue(t("m.chart.high24"), Fmt.money(row.high24hUsd?.let { h -> rate?.let { h * it } }?.let { h -> row.priceFiat?.let { maxOf(h, it) } ?: h }, settings.fiat), Modifier.weight(1f))
                    KeyValue(t("m.chart.low24"), Fmt.money(row.low24hUsd?.let { h -> rate?.let { h * it } }?.let { l -> row.priceFiat?.let { minOf(l, it) } ?: l }, settings.fiat), Modifier.weight(1f))
                }
                Gap(10)
                Row {
                    KeyValue(t("col.7d"), Fmt.pct(row.change7d), Modifier.weight(1f), p.change(row.change7d))
                    KeyValue(t("col.30d"), Fmt.pct(row.change30d), Modifier.weight(1f), p.change(row.change30d))
                }
                Gap(10)
                Row {
                    KeyValue(t("col.ath"), Fmt.pct(row.athChangePct, 1), Modifier.weight(1f))
                    KeyValue(t("col.mcap"), "$" + Fmt.compact(row.marketCap), Modifier.weight(1f))
                }
                if (expert) {
                    Gap(10)
                    Row {
                        KeyValue(t("m.chart.volume24"), "$" + Fmt.compact(row.volume24h), Modifier.weight(1f))
                        KeyValue(t("col.source"), row.fiatSource.ifEmpty { Fmt.DASH }, Modifier.weight(1f))
                    }
                }
            }
        }

        // ── what the chart says, in sentences ─────────────────────────────
        if (d != null && d.candles.size > 2) {
            SectionTitle(t("chart.x.title"))
            Glass(Modifier.fillMaxWidth()) {
                ExplainChart(coin.symbol, d, interval, avgCost, expert, p)
                Text(t("chart.x.note"), style = Type.small, color = p.muted, modifier = Modifier.padding(top = 6.dp))
            }
        }
        SectionTitle(t("chart.g.title")) { LinkButton(if (guide) t("m.hide") else t("m.show"), arrow = false) { guide = !guide } }
        if (guide) {
            Glass(Modifier.fillMaxWidth()) {
                val topics = listOf("candles", "average", "volume", "levels", "rsi") + if (expert) listOf("macd") else emptyList()
                topics.forEach { k ->
                    Text(t("chart.g.${k}_t"), style = Type.strong, color = p.text, modifier = Modifier.padding(top = 6.dp))
                    Text(t(if (k == "levels") "m.chart.levels_b" else "chart.g.${k}_b"), style = Type.bodySmall, color = p.textMid,
                        modifier = Modifier.padding(top = 2.dp, bottom = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun ExplainChart(coin: String, d: CandleSet, interval: Interval, cost: Double?, expert: Boolean, p: Palette) {
    val close = d.candles.map { it.close }.toDoubleArray()
    val unit = t("chart.unit.${interval.key}")
    val n = min(30, close.size - 1)
    if (n > 0) {
        val ch = (close.last() / close[close.size - 1 - n] - 1) * 100
        Explain(t("chart.x.change_t", "pct" to Fmt.pct(ch, 1)),
            t("chart.x.change_b", "coin" to coin, "pct" to Fmt.pct(ch, 1), "n" to n, "unit" to unit),
            if (ch >= 0) R.drawable.ic_trend_up else R.drawable.ic_trend_down, if (ch >= 0) p.up else p.down)
    }
    val s50 = Ind.last(Ind.sma(close, 50))
    val s200 = Ind.last(Ind.sma(close, 200))
    if (s50 != null) {
        val above = close.last() > s50
        var text = t(if (above) "chart.x.trend_up_b" else "chart.x.trend_down_b", "coin" to coin, "unit" to unit)
        if (s200 != null) text += " " + t(if (s50 > s200) "chart.x.long_up" else "chart.x.long_down")
        Explain(t(if (above) "chart.x.trend_up_t" else "chart.x.trend_down_t"), text, R.drawable.ic_path, p.teal)
    }
    Ind.last(Ind.rsi(close))?.let { r ->
        val state = if (r >= 70) "hot" else if (r <= 30) "cold" else "calm"
        val color = when (state) { "hot" -> p.warn; "cold" -> p.info; else -> p.textMid }
        Explain(t("chart.x.rsi_${state}_t", "rsi" to Fmt.num(r, 0)), t("chart.x.rsi_${state}_b", "coin" to coin), R.drawable.ic_gauge, color)
    }
    if (expert) {
        Ind.last(Ind.macd(close).hist)?.let { h ->
            Explain(t(if (h > 0) "chart.x.macd_up_t" else "chart.x.macd_down_t"), t("chart.x.macd_b"), R.drawable.ic_pulse, p.violet)
        }
    }
    val rets = Ind.returns(close.copyOfRange(max(0, close.size - 61), close.size))
    if (rets.size > 5) {
        val typical = Ind.std(rets, 1) * 100
        Explain(t("chart.x.vol_t", "pct" to Fmt.num(typical, 1)),
            t("chart.x.vol_b", "coin" to coin, "unit" to t("chart.unit1.${interval.key}"), "pct" to Fmt.num(typical, 1)),
            R.drawable.ic_arrows_down_up, p.textMid)
    }
    if (cost != null && cost > 0) {
        val diff = (close.last() / cost - 1) * 100
        Explain(t("chart.x.cost_t"), t(if (diff >= 0) "chart.x.cost_above" else "chart.x.cost_below",
            "avg" to Fmt.money(cost, d.quote), "pct" to Fmt.num(abs(diff), 1)), R.drawable.ic_wallet, p.accent)
    }
}

/**
 * Candlesticks with averages, volume, the user's average price and levels;
 * RSI and MACD panes in expert mode. Touch and drag reads one candle; two
 * fingers zoom.
 */
@Composable
private fun CandleChart(
    all: List<Candle>, avgCost: Double?, levels: List<Double>, sma50On: Boolean, ema21On: Boolean, sma200On: Boolean,
    volOn: Boolean, expert: Boolean, interval: Interval,
) {
    val p = LocalPalette.current
    val measurer = rememberTextMeasurer()
    var visible by remember(all.size) { mutableIntStateOf(min(all.size, 90)) }
    var cursor by remember { mutableFloatStateOf(-1f) }
    val close = remember(all) { all.map { it.close }.toDoubleArray() }
    val sma50 = remember(all) { Ind.sma(close, 50) }
    val sma200 = remember(all) { Ind.sma(close, 200) }
    val ema21 = remember(all) { Ind.ema(close, 21) }
    val rsi = remember(all) { Ind.rsi(close) }
    val macd = remember(all) { Ind.macd(close) }
    val start = all.size - visible
    val view = all.subList(start, all.size)
    val axisW = 58.dp
    val labelStyle = TextStyle(fontFamily = Inter, fontSize = 10.sp, color = p.muted, fontFeatureSettings = "tnum")

    // Readout of the touched candle.
    val idx = if (cursor < 0) -1 else cursor.roundToInt().coerceIn(0, view.size - 1)
    val sel = view.getOrNull(idx) ?: view.last()
    val prev = view.getOrNull(idx - 1) ?: if (idx < 0) view.getOrNull(view.size - 2) else null
    val selCh = prev?.let { (sel.close / it.close - 1) * 100 }
    Column(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(dateLabel(sel.ts, interval), style = Type.small, color = p.textMid, modifier = Modifier.weight(1f))
            Text(Fmt.num(sel.close), style = Type.numSmall, color = p.text)
            Text("  " + Fmt.pct(selCh), style = Type.numSmall, color = p.change(selCh))
        }
        Text("O ${Fmt.num(sel.open)} · H ${Fmt.num(sel.high)} · L ${Fmt.num(sel.low)}",
            style = Type.small.copy(fontFeatureSettings = "tnum"), color = p.muted, maxLines = 1)
    }

    val gestures = Modifier.pointerInput(all.size) {
        awaitEachGesture {
            val down = awaitFirstDown()
            val plotW = size.width - axisW.toPx()
            fun toIndex(x: Float) = (x / plotW * visible - 0.5f).coerceIn(0f, (visible - 1).toFloat())
            cursor = toIndex(down.position.x)
            var lastSpan = 0f
            while (true) {
                val ev = awaitPointerEvent()
                val pressed = ev.changes.filter { it.pressed }
                if (pressed.isEmpty()) break
                if (pressed.size >= 2) {
                    val span = abs(pressed[0].position.x - pressed[1].position.x)
                    if (lastSpan > 0f && span > 0f) {
                        visible = (visible * lastSpan / span).roundToInt().coerceIn(min(20, all.size), all.size)
                    }
                    lastSpan = span
                    cursor = -1f
                } else {
                    lastSpan = 0f
                    cursor = toIndex(pressed[0].position.x)
                }
                ev.changes.forEach { if (it.positionChange() != Offset.Zero) it.consume() }
            }
            cursor = -1f
        }
    }

    Canvas(Modifier.fillMaxWidth().height(260.dp).then(gestures)) {
        val plotW = size.width - axisW.toPx()
        val volH = if (volOn) size.height * 0.18f else 0f
        val priceH = size.height - volH - 14.dp.toPx()
        var lo = view.minOf { it.low }
        var hi = view.maxOf { it.high }
        avgCost?.let { if (it > lo * 0.85 && it < hi * 1.15) { lo = min(lo, it); hi = max(hi, it) } }
        val padY = (hi - lo) * 0.06
        lo -= padY; hi += padY
        fun y(v: Double) = ((1 - (v - lo) / (hi - lo)) * priceH).toFloat()
        val step = plotW / view.size
        fun x(i: Int) = step * (i + 0.5f)

        // grid and price axis
        for (k in 0..3) {
            val v = lo + (hi - lo) * (k + 0.5) / 4
            val yy = y(v)
            drawLine(p.glassLine, Offset(0f, yy), Offset(plotW, yy), 1f)
            axisLabel(measurer, Fmt.num(v, if (abs(v) >= 1000) 0 else null), Offset(plotW + 6.dp.toPx(), yy - 7.dp.toPx()), labelStyle)
        }
        // date labels
        for (k in listOf(0.15f, 0.5f, 0.85f)) {
            val i = (view.size * k).toInt().coerceIn(0, view.size - 1)
            axisLabel(measurer, dateLabel(view[i].ts, interval, short = true), Offset(x(i) - 18.dp.toPx(), size.height - 12.dp.toPx()), labelStyle)
        }
        // volume
        if (volOn) {
            val vmax = view.maxOf { it.volume }.takeIf { it > 0 } ?: 1.0
            view.forEachIndexed { i, c ->
                val h = (c.volume / vmax * (volH - 4.dp.toPx())).toFloat()
                drawRect((if (c.close >= c.open) p.up else p.down).copy(alpha = 0.28f),
                    Offset(x(i) - step * 0.35f, priceH + volH - h), Size(step * 0.7f, h))
            }
        }
        // candles
        view.forEachIndexed { i, c ->
            val col = if (c.close >= c.open) p.up else p.down
            drawLine(col, Offset(x(i), y(c.high)), Offset(x(i), y(c.low)), max(1f, step * 0.12f))
            val top = y(max(c.open, c.close))
            val bot = y(min(c.open, c.close))
            drawRect(col, Offset(x(i) - step * 0.36f, top), Size(step * 0.72f, max(1f, bot - top)))
        }
        // averages
        fun line(arr: DoubleArray, color: Color) {
            val path = Path()
            var started = false
            for (i in view.indices) {
                val v = arr[start + i]
                if (v.isNaN()) continue
                if (!started) { path.moveTo(x(i), y(v)); started = true } else path.lineTo(x(i), y(v))
            }
            if (started) drawPath(path, color, style = Stroke(1.4.dp.toPx()))
        }
        if (sma50On) line(sma50, p.teal)
        if (sma200On) line(sma200, p.violet)
        if (ema21On) line(ema21, p.accent)
        // the user's average price and levels
        val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
        avgCost?.let { v ->
            if (v in lo..hi) {
                drawLine(p.accent, Offset(0f, y(v)), Offset(plotW, y(v)), 1.4.dp.toPx(), pathEffect = dash)
                axisLabel(measurer, t("chart.your_avg"), Offset(4.dp.toPx(), y(v) - 14.dp.toPx()), labelStyle.copy(color = p.accent))
            }
        }
        levels.forEach { v ->
            if (v in lo..hi) drawLine(p.warn, Offset(0f, y(v)), Offset(plotW, y(v)), 1.2.dp.toPx(), pathEffect = dash)
        }
        // last price marker
        val last = view.last().close
        drawLine(p.textMid.copy(alpha = 0.5f), Offset(0f, y(last)), Offset(plotW, y(last)), 1f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 4f)))
        // cursor
        if (idx >= 0) {
            drawLine(p.text.copy(alpha = 0.5f), Offset(x(idx), 0f), Offset(x(idx), priceH + volH), 1f)
        }
    }

    if (expert && all.size > 30) {
        Text("RSI 14", style = Type.small, color = p.muted, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
        Canvas(Modifier.fillMaxWidth().height(70.dp)) {
            val plotW = size.width - axisW.toPx()
            val step = plotW / view.size
            fun y(v: Double) = ((1 - v / 100.0) * size.height).toFloat()
            drawRect(p.glass, Offset(0f, y(70.0)), Size(plotW, y(30.0) - y(70.0)))
            listOf(30.0, 70.0).forEach {
                drawLine(p.glassLineHi, Offset(0f, y(it)), Offset(plotW, y(it)), 1f)
                axisLabel(measurer, it.toInt().toString(), Offset(plotW + 6.dp.toPx(), y(it) - 7.dp.toPx()), labelStyle)
            }
            val path = Path()
            var started = false
            for (i in view.indices) {
                val v = rsi[start + i]
                if (v.isNaN()) continue
                val xx = step * (i + 0.5f)
                if (!started) { path.moveTo(xx, y(v)); started = true } else path.lineTo(xx, y(v))
            }
            if (started) drawPath(path, p.violet, style = Stroke(1.4.dp.toPx()))
        }
        Text("MACD 12/26/9", style = Type.small, color = p.muted, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
        Canvas(Modifier.fillMaxWidth().height(70.dp)) {
            val plotW = size.width - axisW.toPx()
            val step = plotW / view.size
            val vals = (view.indices).flatMap { listOf(macd.hist[start + it], macd.macd[start + it], macd.signal[start + it]) }.filter { !it.isNaN() }
            if (vals.isEmpty()) return@Canvas
            val m = vals.maxOf { abs(it) }.takeIf { it > 0 } ?: 1.0
            fun y(v: Double) = ((0.5 - v / m / 2 * 0.9) * size.height).toFloat()
            drawLine(p.glassLineHi, Offset(0f, y(0.0)), Offset(plotW, y(0.0)), 1f)
            for (i in view.indices) {
                val h = macd.hist[start + i]
                if (h.isNaN()) continue
                val xx = step * (i + 0.5f)
                drawLine((if (h >= 0) p.up else p.down).copy(alpha = 0.6f), Offset(xx, y(0.0)), Offset(xx, y(h)), max(1f, step * 0.6f))
            }
            fun line(arr: DoubleArray, color: Color) {
                val path = Path()
                var started = false
                for (i in view.indices) {
                    val v = arr[start + i]
                    if (v.isNaN()) continue
                    val xx = step * (i + 0.5f)
                    if (!started) { path.moveTo(xx, y(v)); started = true } else path.lineTo(xx, y(v))
                }
                if (started) drawPath(path, color, style = Stroke(1.2.dp.toPx()))
            }
            line(macd.macd, p.teal)
            line(macd.signal, p.accent)
        }
    }
}

private fun DrawScope.axisLabel(m: TextMeasurer, text: String, at: Offset, style: TextStyle) {
    drawText(m, text, at, style, softWrap = false, maxLines = 1)
}

private val HOUR = DateTimeFormatter.ofPattern("dd MMM HH:mm")
private val DAY = DateTimeFormatter.ofPattern("dd MMM yyyy")
private val SHORT_HOUR = DateTimeFormatter.ofPattern("HH:mm")
private val SHORT_DAY = DateTimeFormatter.ofPattern("dd MMM")

private fun dateLabel(ts: Long, interval: Interval, short: Boolean = false): String {
    val z = Instant.ofEpochSecond(ts).atZone(ZoneId.systemDefault())
    val locale = java.util.Locale.forLanguageTag(com.mazecrypto.app.core.I18n.language)
    val hourly = interval == Interval.H1 || interval == Interval.H4
    val f = when {
        short && interval == Interval.H1 -> SHORT_HOUR
        short -> SHORT_DAY
        hourly -> HOUR
        else -> DAY
    }
    return f.withLocale(locale).format(z)
}

