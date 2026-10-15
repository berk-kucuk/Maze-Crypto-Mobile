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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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

@Composable
fun HomeScreen(nav: Nav) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val settings by Maze.settings.collectAsStateWithLifecycle()
    val snap by Maze.snapshot.collectAsStateWithLifecycle()
    val profile by Maze.profile.collectAsStateWithLifecycle()
    val tips by Maze.tips.collectAsStateWithLifecycle()
    val alerts by Maze.alertLog.collectAsStateWithLifecycle()
    var allTips by remember { mutableStateOf(false) }
    val prefs = remember { ctx.getSharedPreferences("ui", Context.MODE_PRIVATE) }
    var promo by remember { mutableStateOf(!prefs.getBoolean("widget_promo_done", false) && !Widgets.hasAny(ctx)) }
    val fiat = settings.fiat
    val sum = remember(snap, profile, settings.fiat) { Maze.summary() }

    TabScreen {
        item {
            PageHeader(greeting(), t("m.home.sub")) { TopActions(nav) }
        }

        // ── the four numbers ──────────────────────────────────────────────
        item {
            Gap(10)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(Modifier.weight(1f), R.drawable.ic_wallet, t("home.value"),
                    if (sum.holdings.isEmpty()) Fmt.DASH else Fmt.money(sum.valueFiat, fiat, if (sum.valueFiat >= 1000) 0 else 2),
                    if (sum.holdings.isEmpty()) t("watch.no_trades")
                    else "${Fmt.signedMoney(sum.pnlFiat, fiat, 0)} (${Fmt.pct(sum.pnlPct)})",
                    if (sum.holdings.isEmpty()) p.muted else p.change(sum.pnlPct)) { nav.tab(Route.Portfolio()) }
                val day = snap?.let { s -> valueChange24h(sum, s.rows.associate { it.coin.id to it.change24h }) }
                StatTile(Modifier.weight(1f), R.drawable.ic_clock, t("home.today"),
                    day?.let { Fmt.signedMoney(it.first, fiat, 0) } ?: Fmt.DASH,
                    day?.let { Fmt.pct(it.second) } ?: t("home.today_empty"),
                    if (day == null) p.muted else p.change(day.second), valueColor = day?.let { p.change(it.second) })
            }
            Gap(10)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val plan = profile.dcaPlan
                val cur = plan?.let { current(schedule(it, null, profile.targets), profile.dcaDone, LocalDate.now()) }
                StatTile(Modifier.weight(1f), R.drawable.ic_calendar_check, t("home.plan"),
                    if (cur != null) Maze.moneyFmt(fiat)(cur.first.total) else Fmt.DASH,
                    when {
                        plan == null -> t("watch.no_plan")
                        cur == null -> t("dca.finished")
                        cur.second == "upcoming" -> t("dca.in_days", "n" to com.mazecrypto.app.core.daysBetween(LocalDate.now(), cur.first.due))
                        else -> t("dca.state.${cur.second}")
                    },
                    if (cur?.second == "overdue") p.warn else if (cur?.second == "due") p.accent else p.muted) { nav.tab(Route.Plan) }
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
        item { SectionTitle(t("home.assistant")) }
        val shown = if (allTips) tips else tips.take(3)
        shown.forEach { tip -> item(key = tip.id) { TipCard(tip, nav); Gap(10) } }
        if (tips.size > 3) {
            item {
                LinkButton(if (allTips) t("m.home.fewer_tips") else t("m.home.more_tips", "n" to tips.size - 3), arrow = false) {
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

@Composable
private fun StatTile(
    modifier: Modifier, icon: Int, title: String, value: String, sub: String, subColor: Color,
    valueColor: Color? = null, onClick: (() -> Unit)? = null,
) {
    val p = LocalPalette.current
    Glass(modifier.height(118.dp), padding = 14.dp, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Ic(icon, tint = p.muted, size = 15.dp)
            Spacer(Modifier.width(6.dp))
            Text(title, style = Type.cardTitle, color = p.textMid, maxLines = 1)
        }
        Spacer(Modifier.weight(1f))
        Text(value, style = Type.value, color = valueColor ?: p.text, maxLines = 1)
        Text(sub, style = Type.small, color = subColor, maxLines = 2, modifier = Modifier.padding(top = 3.dp))
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
