package com.mazecrypto.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mazecrypto.app.R
import com.mazecrypto.app.core.Coin
import com.mazecrypto.app.core.Fmt
import com.mazecrypto.app.core.I18n
import com.mazecrypto.app.core.Ind
import com.mazecrypto.app.core.Interval
import com.mazecrypto.app.core.OTHER
import com.mazecrypto.app.core.STABLECOINS
import com.mazecrypto.app.core.TechState
import com.mazecrypto.app.core.drift
import com.mazecrypto.app.core.t
import com.mazecrypto.app.core.techState
import com.mazecrypto.app.data.Maze
import com.mazecrypto.app.ui.Nav
import com.mazecrypto.app.ui.components.Divider
import com.mazecrypto.app.ui.components.GhostButton
import com.mazecrypto.app.ui.components.Glass
import com.mazecrypto.app.ui.components.KeyValue
import com.mazecrypto.app.ui.components.SectionTitle
import com.mazecrypto.app.ui.theme.LocalPalette
import com.mazecrypto.app.ui.theme.Type
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** A few plain words for one coin, e.g. "Uptrend, momentum slowing". */
private fun summaryText(st: TechState): String {
    val parts = mutableListOf<String>()
    when (st.trend) {
        "above_both" -> parts += t("ins.sum.up")
        "below_both" -> parts += t("ins.sum.down")
        "mixed" -> parts += t("ins.sum.mixed")
    }
    when {
        st.rsiState == "overbought" -> parts += t("ins.sum.hot")
        st.rsiState == "oversold" -> parts += t("ins.sum.cold")
        st.macdState.isNotEmpty() -> parts += t(if (st.macdState == "bullish") "ins.sum.mom_up" else "ins.sum.mom_down")
    }
    val text = parts.joinToString(", ")
    return if (text.isNotEmpty()) I18n.upperFirst(text) else t("ins.short")
}

@Composable
fun InsightsScreen(nav: Nav) {
    val p = LocalPalette.current
    val settings by Maze.settings.collectAsStateWithLifecycle()
    val profile by Maze.profile.collectAsStateWithLifecycle()
    val snap by Maze.snapshot.collectAsStateWithLifecycle()
    var data by remember { mutableStateOf<List<Pair<Coin, List<Double>>>?>(null) }
    var status by remember { mutableStateOf("") }
    var run by remember { mutableStateOf(0) }

    LaunchedEffect(run, settings.watchlist) {
        val coins = settings.watchlist
        status = t("ins.loading", "n" to coins.size)
        val ref = snap?.rows?.associate { it.coin.id to it.priceUsd } ?: emptyMap()
        val sem = Semaphore(3)
        data = coroutineScope {
            coins.map { c ->
                async {
                    sem.withPermit {
                        c to runCatching { Maze.service().candles(c, "usd", Interval.D1, 300, ref[c.id]).candles.map { it.close } }.getOrElse { emptyList() }
                    }
                }
            }.awaitAll()
        }
        status = ""
    }

    SubScreen(nav, t("ins.title")) {
        Text(t("ins.sub"), style = Type.bodySmall, color = p.textMid, modifier = Modifier.padding(vertical = 4.dp))
        // ── portfolio in four numbers ─────────────────────────────────────
        val sum = remember(snap, profile) { Maze.summary() }
        Gap(8)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val top = sum.holdings.firstOrNull()
            Glass(Modifier.weight(1f)) {
                KeyValue(t("ins.top"), top?.let { Fmt.pct(sum.weights[it.position.coinId], 1, false) } ?: Fmt.DASH)
                Text(top?.let { t("ins.top_sub", "coin" to it.position.symbol) } ?: "", style = Type.small, color = p.muted)
            }
            val stable = sum.weights.filterKeys { id -> sum.holdings.any { it.position.coinId == id && it.position.symbol in STABLECOINS } }.values.sum()
            Glass(Modifier.weight(1f)) {
                KeyValue(t("ins.stable"), if (sum.holdings.isEmpty()) Fmt.DASH else Fmt.pct(stable, 1, false))
                Text(t("ins.stable_sub"), style = Type.small, color = p.muted)
            }
        }
        Gap(10)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val d = drift(sum.weights, profile.targets, sum.valueFiat).firstOrNull()
            Glass(Modifier.weight(1f)) {
                KeyValue(t("ins.max_drift"), d?.let { (if (it.diffPct > 0) "+" else "") + Fmt.num(it.diffPct, 1) + " pp" } ?: Fmt.DASH)
                val name = d?.bucket?.let { if (it == OTHER) t("targets.other") else profile.targetSymbols[it] ?: Maze.coinName(it) } ?: ""
                Text(if (d != null) t("ins.drift_sub", "coin" to name) else t("hold.no_targets"), style = Type.small, color = p.muted)
            }
            val corr = remember(data) {
                val series = data?.filter { it.second.size >= 31 && !it.first.isStable }?.map { it.second.takeLast(31).toDoubleArray() } ?: emptyList()
                val vals = mutableListOf<Double>()
                for (i in series.indices) for (j in i + 1 until series.size) Ind.correlation(series[i], series[j])?.let { vals += it }
                if (vals.isEmpty()) null else vals.average()
            }
            Glass(Modifier.weight(1f)) {
                KeyValue(t("ins.avg_corr"), corr?.let { Fmt.num(it, 2) } ?: Fmt.DASH)
                Text(t("ins.avg_corr_sub"), style = Type.small, color = p.muted)
            }
        }

        // ── each coin's daily chart in words ──────────────────────────────
        SectionTitle(t("ins.tech")) { GhostButton(t("ins.recompute"), icon = R.drawable.ic_arrows_clockwise) { run++ } }
        Text(t("m.ins.tech_help"), style = Type.small, color = p.muted, modifier = Modifier.padding(bottom = 8.dp))
        if (status.isNotEmpty()) Text(status, style = Type.body, color = p.textMid)
        Glass(Modifier.fillMaxWidth(), padding = 0.dp) {
            data?.forEachIndexed { i, (coin, cl) ->
                if (i > 0) Divider(Modifier.padding(horizontal = 14.dp))
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(coin.symbol, style = Type.strong, color = p.text, modifier = Modifier.width(64.dp))
                        val st = if (cl.size > 2 && !coin.isStable) techState(cl) else null
                        Text(when {
                            coin.isStable -> t("ins.sum.stable")
                            st == null -> t("ins.short")
                            else -> summaryText(st)
                        }, style = Type.body, color = p.text, modifier = Modifier.weight(1f))
                    }
                    if (!coin.isStable && cl.size > 2) {
                        val st = techState(cl)
                        Gap(8)
                        Row {
                            val rsiColor = when (st.rsiState) { "overbought" -> p.warn; "oversold" -> p.info; else -> p.textMid }
                            KeyValue("RSI 14", st.rsi?.let { r -> Fmt.num(r, 0) + if (st.rsiState.isNotEmpty() && st.rsiState != "neutral") " " + t("ins.rsi.${st.rsiState}") else "" } ?: Fmt.DASH,
                                Modifier.weight(1f), rsiColor)
                            KeyValue(t("ins.trend"), if (st.trend.isNotEmpty()) t("ins.trend.${st.trend}") else t("ins.short"), Modifier.weight(1.2f),
                                when (st.trend) { "above_both" -> p.up; "below_both" -> p.down; else -> p.textMid })
                            KeyValue("MACD", if (st.macdState.isNotEmpty()) t("ins.macd.${st.macdState}") else Fmt.DASH, Modifier.weight(1.2f),
                                if (st.macdState == "bullish") p.up else if (st.macdState == "bearish") p.down else p.textMid)
                        }
                        Gap(6)
                        Row {
                            KeyValue(t("ins.vol"), Fmt.pct(st.volatility, 0, false), Modifier.weight(1f))
                            val dd = Ind.maxDrawdown(cl.takeLast(90).toDoubleArray())
                            KeyValue(t("ins.dd"), Fmt.pct(dd, 1), Modifier.weight(1.2f))
                            val ch = if (cl.size > 91) (cl.last() / cl[cl.size - 91] - 1) * 100 else null
                            KeyValue(t("ins.90d"), Fmt.pct(ch, 1), Modifier.weight(1.2f), p.change(ch))
                        }
                    }
                }
            }
        }

        // ── correlation heatmap ───────────────────────────────────────────
        val series = data?.filter { it.second.size >= 31 && !it.first.isStable }?.take(8) ?: emptyList()
        if (series.size > 1) {
            SectionTitle(t("ins.corr"))
            Glass(Modifier.fillMaxWidth(), padding = 10.dp) {
                Row {
                    Box(Modifier.width(44.dp))
                    series.forEach { (c, _) -> Text(c.symbol, style = Type.small.copy(fontSize = 9.sp), color = p.muted, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, maxLines = 1) }
                }
                series.forEach { (a, ca) ->
                    Row(Modifier.padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(a.symbol, style = Type.small, color = p.muted, modifier = Modifier.width(44.dp), maxLines = 1)
                        series.forEach { (b, cb) ->
                            val r = if (a.id == b.id) 1.0 else Ind.correlation(ca.takeLast(31).toDoubleArray(), cb.takeLast(31).toDoubleArray())
                            val k = ((r ?: 0.0).coerceIn(-1.0, 1.0) + 1) / 2
                            Box(Modifier.weight(1f).height(28.dp).padding(1.dp).clip(RoundedCornerShape(4.dp))
                                .background(p.accent.copy(alpha = (0.06 + 0.6 * k * k).toFloat())), contentAlignment = Alignment.Center) {
                                Text(r?.let { Fmt.num(it, 2) } ?: "", style = Type.small.copy(fontSize = 9.sp, fontFeatureSettings = "tnum"),
                                    color = if (k > 0.75) p.onAccent else p.text, maxLines = 1)
                            }
                        }
                    }
                }
                Text(t("ins.corr_note"), style = Type.small, color = p.muted, modifier = Modifier.padding(top = 8.dp))
            }
        }

        SectionTitle(t("ins.g.title"))
        Glass(Modifier.fillMaxWidth()) {
            listOf("rsi", "trend", "macd", "vol", "dd", "corr").forEach { k ->
                Text(t("ins.g.${k}_t"), style = Type.strong, color = p.text, modifier = Modifier.padding(top = 6.dp))
                Text(t("ins.g.${k}_b"), style = Type.bodySmall, color = p.textMid, modifier = Modifier.padding(top = 2.dp, bottom = 4.dp))
            }
        }
        Gap(10)
        Text(t("ins.disclaimer"), style = Type.small, color = p.muted)
    }
}
