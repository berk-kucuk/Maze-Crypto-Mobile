package com.mazecrypto.app.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextOverflow
import com.mazecrypto.app.core.portfolioTrend
import com.mazecrypto.app.data.MoneroSync
import com.mazecrypto.app.ui.components.Badge
import com.mazecrypto.app.ui.components.ChangePill
import com.mazecrypto.app.ui.components.Sparkline
import com.mazecrypto.app.ui.components.Progress
import com.mazecrypto.app.R
import com.mazecrypto.app.core.Fmt
import com.mazecrypto.app.core.Tip
import com.mazecrypto.app.core.current
import com.mazecrypto.app.core.schedule
import com.mazecrypto.app.core.t
import com.mazecrypto.app.core.valueChange24h
import com.mazecrypto.app.data.Maze
import com.mazecrypto.app.ui.Nav
import com.mazecrypto.app.ui.Route
import com.mazecrypto.app.ui.components.Divider
import com.mazecrypto.app.ui.components.Glass
import com.mazecrypto.app.ui.components.GhostButton
import com.mazecrypto.app.ui.components.Ic
import com.mazecrypto.app.ui.components.LinkButton
import com.mazecrypto.app.ui.components.PageHeader
import com.mazecrypto.app.ui.components.PrimaryButton
import com.mazecrypto.app.ui.components.SectionTitle
import com.mazecrypto.app.ui.theme.LocalPalette
import com.mazecrypto.app.ui.theme.Type
import com.mazecrypto.app.widget.Widgets
import java.time.LocalDate

/** Hidden-amounts mask, used everywhere money is shown on the dashboard. */
const val MASK = "••••••"

@Composable
fun HomeScreen(nav: Nav) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val settings by Maze.settings.collectAsStateWithLifecycle()
    val snap by Maze.snapshot.collectAsStateWithLifecycle()
    val profile by Maze.profile.collectAsStateWithLifecycle()
    val tips by Maze.tips.collectAsStateWithLifecycle()
    val alerts by Maze.alertLog.collectAsStateWithLifecycle()
    val wallets by Maze.wallets.collectAsStateWithLifecycle()
    val walletData by Maze.walletData.collectAsStateWithLifecycle()
    val xmr by MoneroSync.progress.collectAsStateWithLifecycle()
    var allTips by remember { mutableStateOf(false) }
    val prefs = remember { ctx.getSharedPreferences("ui", Context.MODE_PRIVATE) }
    var promo by remember { mutableStateOf(!prefs.getBoolean("widget_promo_done", false) && !Widgets.hasAny(ctx)) }
    val fiat = settings.fiat
    val hide = settings.hideAmounts
    val sum = remember(snap, profile, settings.fiat, walletData) { Maze.summary() }
    val trend = remember(sum, snap) { snap?.let { s -> portfolioTrend(sum, s.rows.associate { it.coin.id to it.spark }) } ?: emptyList() }
    fun money(v: Double?, d: Int? = null) = if (hide) MASK else Fmt.money(v, fiat, d)
    fun signed(v: Double, d: Int = 0) = if (hide) MASK else Fmt.signedMoney(v, fiat, d)

    TabScreen {
        item {
            PageHeader(greeting(), today()) { TopActions(nav) }
        }

        // ── hero: what it is worth, and how it moved ─────────────────────
        item {
            Gap(8)
            if (sum.holdings.isEmpty()) StartHero(nav)
            else Glass(Modifier.fillMaxWidth(), padding = 0.dp, onClick = { nav.tab(Route.Portfolio()) }) {
                Column(Modifier.padding(start = 18.dp, end = 10.dp, top = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t("home.value"), style = Type.cardTitle, color = p.textMid, modifier = Modifier.weight(1f))
                        Box(Modifier.size(34.dp).clip(CircleShape).clickable { Maze.updateSettings { it.copy(hideAmounts = !it.hideAmounts) } },
                            contentAlignment = Alignment.Center) {
                            Ic(if (hide) R.drawable.ic_eye_slash else R.drawable.ic_eye, tint = p.textMid, size = 18.dp)
                        }
                    }
                    Text(money(sum.valueFiat, if (sum.valueFiat >= 1000) 0 else 2), style = Type.big.copy(fontSize = 36.sp), color = p.text,
                        maxLines = 1, modifier = Modifier.padding(top = 2.dp))
                    val day = snap?.let { s -> valueChange24h(sum, s.rows.associate { it.coin.id to it.change24h }) }
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (day != null) {
                            ChangePill(day.second)
                            Spacer(Modifier.width(8.dp))
                            Text(signed(day.first) + "  " + t("m.home.today_short"), style = Type.numSmall, color = p.change(day.second))
                        } else Text(t("home.today_empty"), style = Type.small, color = p.muted)
                    }
                }
                if (trend.size > 1) {
                    Gap(10)
                    Sparkline(trend, Modifier.fillMaxWidth().height(86.dp))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp)) {
                        Text(t("m.home.trend_7d"), style = Type.small, color = p.muted, modifier = Modifier.weight(1f))
                        val ch = (trend.last() / trend.first() - 1) * 100
                        Text(Fmt.pct(ch), style = Type.small, color = p.change(ch))
                    }
                } else Gap(16)
                Divider()
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp)) {
                    HeroStat(Modifier.weight(1f), t("m.home.pnl"),
                        if (sum.costFiat <= 0) Fmt.DASH else signed(sum.pnlFiat),
                        if (sum.costFiat <= 0) (if (sum.unknownCost.isNotEmpty()) t("m.hold.from_wallets") else t("watch.no_trades")) else Fmt.pct(sum.pnlPct),
                        if (sum.costFiat <= 0) p.muted else p.change(sum.pnlPct))
                    HeroStat(Modifier.weight(1f), t("m.home.assets"), sum.holdings.size.toString(),
                        sum.holdings.sortedByDescending { it.valueFiat ?: 0.0 }.take(3).joinToString(" · ") { it.position.symbol }, p.muted)
                }
            }
        }

        // ── quick actions (the empty-state hero already offers them) ──────
        if (sum.holdings.isNotEmpty()) item {
            Gap(14)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                QuickAction(R.drawable.ic_wallet, t("m.home.qa_wallet")) { nav.go(Route.WalletEdit()) }
                QuickAction(R.drawable.ic_usb, "Ledger") { nav.go(Route.Ledger) }
                QuickAction(R.drawable.ic_receipt, t("m.home.qa_trade")) { nav.go(Route.Trade()) }
                QuickAction(R.drawable.ic_bell, t("m.home.qa_alert")) { nav.go(Route.RuleEdit()) }
            }
        }

        // ── allocation ────────────────────────────────────────────────────
        if (sum.holdings.size > 1) {
            item {
                SectionTitle(t("m.home.allocation")) { LinkButton(t("home.see_all")) { nav.tab(Route.Portfolio("alloc")) } }
                Glass(Modifier.fillMaxWidth()) {
                    val parts = sum.holdings.mapNotNull { h -> sum.weights[h.position.coinId]?.let { h.position.symbol to it } }
                        .sortedByDescending { it.second }
                    val top = parts.take(5)
                    val rest = parts.drop(5).sumOf { it.second }
                    val shown = top + if (rest > 0.05) listOf(t("m.home.other") to rest) else emptyList()
                    val colors = listOf(p.accent, p.violet, p.info, p.up, p.warn, p.textMid)
                    AllocationBar(shown.map { it.second }, colors)
                    Gap(12)
                    shown.chunked(2).forEach { pair ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            pair.forEach { (sym, w) ->
                                val c = colors[shown.indexOfFirst { it.first == sym }.coerceIn(0, colors.size - 1)]
                                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(8.dp).clip(CircleShape).background(c))
                                    Spacer(Modifier.width(8.dp))
                                    Text(sym, style = Type.strong, color = p.text, maxLines = 1, modifier = Modifier.weight(1f))
                                    Text(Fmt.pct(w, 1, signed = false), style = Type.numSmall, color = p.textMid)
                                    Spacer(Modifier.width(14.dp))
                                }
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        // ── wallets ───────────────────────────────────────────────────────
        if (wallets.isNotEmpty()) {
            item {
                SectionTitle(t("m.home.wallets")) { LinkButton(t("home.see_all")) { nav.tab(Route.Portfolio("wallets")) } }
                Glass(Modifier.fillMaxWidth(), onClick = { nav.tab(Route.Portfolio("wallets")) }) {
                    val prices = remember(snap, walletData) { Maze.prices().first }
                    val ledger = wallets.count { it.device == "ledger" }
                    val failing = wallets.count { walletData[it.id]?.error != null }
                    val total = wallets.filter { it.inPortfolio }.sumOf { w ->
                        if (w.manual) (w.amount ?: 0.0) * (prices[w.coinId] ?: 0.0)
                        else walletData[w.id]?.balance?.assets?.sumOf { a -> a.amount * (prices[a.coinId] ?: 0.0) } ?: 0.0
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(p.accent.copy(alpha = 0.13f)), contentAlignment = Alignment.Center) {
                            Ic(R.drawable.ic_wallet, tint = p.accent, size = 19.dp)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(money(total, 0), style = Type.value, color = p.text, maxLines = 1)
                            Text(listOfNotNull(t("m.wal.n_addresses", "n" to wallets.size), if (ledger > 0) "Ledger $ledger" else null)
                                .joinToString(" · "), style = Type.small, color = p.muted)
                        }
                        when {
                            failing > 0 -> Badge(t("m.wal.n_failing", "n" to failing), p.warn)
                            walletData.values.any { it.loading } -> Badge(t("status.updating"), p.accent)
                            else -> Badge(t("m.home.synced"), p.up)
                        }
                    }
                    xmr.values.firstOrNull()?.let { pr ->
                        Gap(10)
                        if (pr.phase == "back") {
                            Text(t("m.xmr.history_to", "date" to Fmt.date(pr.ts)) + " · Monero", style = Type.small, color = p.accent)
                        } else {
                            Text(t("m.xmr.scanning", "pct" to (pr.fraction * 100).toInt().toString()) + " · Monero", style = Type.small, color = p.accent)
                            Progress(pr.fraction, Modifier.fillMaxWidth().padding(top = 6.dp))
                        }
                    }
                }
            }
        }

        // ── market movers (watchlist, 24h) ────────────────────────────────
        val movers = snap?.rows?.filter { it.change24h != null && !it.coin.isStable }?.sortedByDescending { kotlin.math.abs(it.change24h!!) }?.take(8)
        if (!movers.isNullOrEmpty()) {
            item {
                SectionTitle(t("m.home.movers")) { LinkButton(t("home.see_all")) { nav.tab(Route.Prices()) } }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    movers.forEach { r ->
                        Glass(Modifier.width(132.dp), padding = 12.dp, onClick = { nav.go(Route.Chart(r.coin.id)) }) {
                            Text(r.coin.symbol, style = Type.strong, color = p.text, maxLines = 1)
                            Text(Fmt.money(r.priceFiat, fiat), style = Type.numSmall, color = p.textMid, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Gap(6)
                            Sparkline(r.spark.takeLast(25), Modifier.fillMaxWidth().height(30.dp), fill = false, color = p.change(r.change24h))
                            Gap(6)
                            ChangePill(r.change24h)
                        }
                    }
                }
            }
        }

        // ── plan & alerts ─────────────────────────────────────────────────
        item {
            Gap(14)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val plan = profile.dcaPlan
                val cur = plan?.let { current(schedule(it, null, profile.targets), profile.dcaDone, LocalDate.now()) }
                StatTile(Modifier.weight(1f), R.drawable.ic_calendar_check, t("home.plan"),
                    if (cur != null) (if (hide) MASK else Maze.moneyFmt(fiat)(cur.first.total)) else t("m.home.set_up"),
                    when {
                        plan == null -> t("watch.no_plan")
                        cur == null -> t("dca.finished")
                        cur.second == "upcoming" -> t("dca.in_days", "n" to com.mazecrypto.app.core.daysBetween(LocalDate.now(), cur.first.due))
                        else -> t("dca.state.${cur.second}")
                    },
                    if (cur?.second == "overdue") p.warn else if (cur?.second == "due") p.accent else p.muted,
                    valueColor = if (cur == null) p.textMid else null) { nav.tab(Route.Plan) }
                val active = profile.rules.count { it.enabled }
                StatTile(Modifier.weight(1f), R.drawable.ic_bell, t("home.alerts"), active.toString(),
                    when {
                        profile.rules.isEmpty() -> t("home.alerts_none")
                        alerts.isEmpty() -> t("home.alerts_quiet")
                        else -> t("home.last_alert", "name" to alerts.first().name)
                    }, p.muted) { nav.tab(Route.Alerts) }
            }
        }

        // ── widgets: the reason to have this on a phone ───────────────────
        if (promo) {
            item {
                Gap(14)
                Glass(Modifier.fillMaxWidth(), tint = p.accent) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(p.accent.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center) { Ic(R.drawable.ic_squares_four, tint = p.accent, size = 18.dp) }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(t("m.home.widget_t"), style = Type.strong, color = p.text)
                            Text(t("m.home.widget_b"), style = Type.bodySmall, color = p.textMid, modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                    Gap(12)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrimaryButton(t("m.widget.add"), Modifier.weight(1f), icon = R.drawable.ic_plus) {
                            if (!ctx.pinWidget("favorites")) nav.go(Route.Settings)
                        }
                        GhostButton(t("tip.close")) {
                            prefs.edit().putBoolean("widget_promo_done", true).apply()
                            promo = false
                        }
                    }
                }
            }
        }

        // ── assistant ─────────────────────────────────────────────────────
        if (tips.isNotEmpty()) item { SectionTitle(t("home.assistant")) }
        val shown = if (allTips) tips else tips.take(2)
        shown.forEach { tip -> item(key = tip.id) { TipCard(tip, nav); Gap(10) } }
        if (tips.size > 2) {
            item {
                LinkButton(if (allTips) t("m.home.fewer_tips") else t("m.home.more_tips", "n" to tips.size - 2), arrow = false) {
                    allTips = !allTips
                }
            }
        }

        // ── favorites ─────────────────────────────────────────────────────
        item {
            SectionTitle(t("m.home.favorites")) { LinkButton(t("home.see_all")) { nav.tab(Route.Prices()) } }
            val rows = settings.widgetCoins().mapNotNull { c -> snap?.row(c.id) }
            Glass(Modifier.fillMaxWidth(), padding = 14.dp) {
                if (rows.isEmpty()) {
                    Text(if (settings.watchlist.isEmpty()) t("tip.start_watch.b") else t("status.updating"),
                        style = Type.body, color = p.textMid)
                }
                rows.forEachIndexed { i, r ->
                    if (i > 0) Divider()
                    CoinLine(r, fiat, settings.widgetChange) { nav.go(Route.Chart(r.coin.id)) }
                }
            }
        }

        if (settings.expert) {
            item {
                Gap(12)
                Glass(Modifier.fillMaxWidth(), onClick = { nav.go(Route.Insights) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Ic(R.drawable.ic_pulse, tint = p.violet, size = 20.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(t("ins.title"), style = Type.strong, color = p.text)
                            Text(t("ins.sub"), style = Type.bodySmall, color = p.textMid)
                        }
                        Ic(R.drawable.ic_caret_right, tint = p.muted, size = 16.dp)
                    }
                }
            }
        }

        item {
            Gap(16)
            Text(t("home.disclaimer"), style = Type.small, color = p.muted)
        }
    }
}

private fun today(): String {
    val loc = java.util.Locale.forLanguageTag(Maze.settings.value.language)
    return LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("EEEE, d MMMM", loc))
        .replaceFirstChar { it.titlecase(loc) }
}

/** No holdings yet: what to do first, instead of a wall of dashes. */
@Composable
private fun StartHero(nav: Nav) {
    val p = LocalPalette.current
    Glass(Modifier.fillMaxWidth(), tint = p.accent) {
        Text(t("m.home.start_t"), style = Type.value, color = p.text)
        Text(t("m.home.start_b"), style = Type.bodySmall, color = p.textMid, modifier = Modifier.padding(top = 4.dp))
        Gap(14)
        PrimaryButton(t("m.home.qa_wallet"), Modifier.fillMaxWidth(), icon = R.drawable.ic_wallet) { nav.go(Route.WalletEdit()) }
        Gap(8)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton("Ledger", Modifier.weight(1f), icon = R.drawable.ic_usb) { nav.go(Route.Ledger) }
            GhostButton(t("m.home.qa_trade"), Modifier.weight(1f), icon = R.drawable.ic_receipt) { nav.go(Route.Trade()) }
        }
    }
}

@Composable
private fun HeroStat(modifier: Modifier, title: String, value: String, sub: String, subColor: Color) {
    val p = LocalPalette.current
    Column(modifier) {
        Text(title, style = Type.small, color = p.muted)
        Text(value, style = Type.num, color = p.text, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
        Text(sub, style = Type.small, color = subColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun QuickAction(icon: Int, label: String, onClick: () -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.width(76.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(50.dp).clip(RoundedCornerShape(16.dp)).background(p.glassHi), contentAlignment = Alignment.Center) {
            Ic(icon, tint = p.accent, size = 22.dp)
        }
        Text(label, style = Type.small, color = p.textMid, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun AllocationBar(weights: List<Double>, colors: List<Color>) {
    val total = weights.sum().takeIf { it > 0 } ?: return
    Canvas(Modifier.fillMaxWidth().height(10.dp)) {
        var x = 0f
        val gap = 3.dp.toPx()
        weights.forEachIndexed { i, w ->
            val wd = (w / total * size.width).toFloat() - if (i < weights.size - 1) gap else 0f
            if (wd > 0) drawRoundRect(colors[i.coerceAtMost(colors.size - 1)], Offset(x, 0f), Size(wd, size.height), CornerRadius(size.height / 2))
            x += wd + gap
        }
    }
}

@Composable
private fun StatTile(
    modifier: Modifier, icon: Int, title: String, value: String, sub: String, subColor: Color,
    valueColor: Color? = null, onClick: (() -> Unit)? = null,
) {
    val p = LocalPalette.current
    Glass(modifier, padding = 14.dp, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Ic(icon, tint = p.muted, size = 15.dp)
            Spacer(Modifier.width(6.dp))
            Text(title, style = Type.cardTitle, color = p.textMid, maxLines = 1)
        }
        Text(value, style = Type.value, color = valueColor ?: p.text, maxLines = 1, modifier = Modifier.padding(top = 10.dp))
        Text(sub, style = Type.small, color = subColor, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
fun TipCard(tip: Tip, nav: Nav) {
    val p = LocalPalette.current
    val (icon, color) = when {
        tip.level == "action" -> R.drawable.ic_lightning to p.accent
        tip.level == "warn" -> R.drawable.ic_warning to p.warn
        tip.level == "good" -> R.drawable.ic_check_circle to p.up
        tip.id.startsWith("learn.") -> R.drawable.ic_lightbulb to p.info
        else -> R.drawable.ic_info to p.textMid
    }
    Glass(Modifier.fillMaxWidth(), tint = if (tip.level == "warn") p.warn else if (tip.level == "action") p.accent else null) {
        Row {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.13f)), contentAlignment = Alignment.Center) {
                Ic(icon, tint = color, size = 18.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(tip.titleText(), style = Type.strong, color = p.text)
                Text(tip.bodyText(), style = Type.bodySmall, color = p.textMid, modifier = Modifier.padding(top = 3.dp))
                if (tip.action.isNotEmpty() && tip.actionLabel.isNotEmpty()) {
                    Box(Modifier.padding(top = 4.dp)) { LinkButton(t(tip.actionLabel)) { nav.action(tip.action) } }
                }
            }
        }
    }
}
