package com.mazecrypto.app.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mazecrypto.app.R
import com.mazecrypto.app.core.Fmt
import com.mazecrypto.app.core.OTHER
import com.mazecrypto.app.core.drift
import com.mazecrypto.app.core.splitContribution
import com.mazecrypto.app.core.t
import com.mazecrypto.app.data.Export
import com.mazecrypto.app.data.Maze
import com.mazecrypto.app.ui.Nav
import com.mazecrypto.app.ui.Route
import com.mazecrypto.app.ui.components.Badge
import com.mazecrypto.app.ui.components.Confirm
import com.mazecrypto.app.ui.components.Divider
import com.mazecrypto.app.ui.components.EmptyState
import com.mazecrypto.app.ui.components.Field
import com.mazecrypto.app.ui.components.GhostButton
import com.mazecrypto.app.ui.components.Glass
import com.mazecrypto.app.ui.components.IconBtn
import com.mazecrypto.app.ui.components.KeyValue
import com.mazecrypto.app.ui.components.PageHeader
import com.mazecrypto.app.ui.components.Picker
import com.mazecrypto.app.ui.components.PrimaryButton
import com.mazecrypto.app.ui.components.SectionTitle
import com.mazecrypto.app.ui.components.Segmented
import com.mazecrypto.app.ui.theme.LocalPalette
import com.mazecrypto.app.ui.theme.Type
import java.time.LocalDate
import kotlin.math.abs

@Composable
fun PortfolioScreen(nav: Nav, startTab: String) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val settings by Maze.settings.collectAsStateWithLifecycle()
    val snap by Maze.snapshot.collectAsStateWithLifecycle()
    val profile by Maze.profile.collectAsStateWithLifecycle()
    var tab by remember(startTab) { mutableStateOf(startTab) }
    var menu by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<List<com.mazecrypto.app.core.Trade>?>(null) }
    var exportText by remember { mutableStateOf("") }
    var yearPick by remember { mutableStateOf(false) }
    val fiat = settings.fiat
    val sum = remember(snap, profile, fiat) { Maze.summary() }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/*")) { uri ->
        if (uri != null) runCatching {
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(exportText.toByteArray()) }
        }.onSuccess { Toast.makeText(ctx, t("set.saved"), Toast.LENGTH_SHORT).show() }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.getOrNull() ?: ""
        try {
            pendingImport = if (text.trimStart().startsWith("{")) Export.fromJson(text) else Export.fromCsv(text)
        } catch (e: Exception) {
            Toast.makeText(ctx, t("hold.import_err", "err" to (e.message ?: "")), Toast.LENGTH_LONG).show()
        }
    }

    TabScreen {
        item {
            PageHeader(t("hold.title"), t("hold.sub")) {
                TopActions(nav, settings = false)
                if (settings.expert) IconBtn(R.drawable.ic_upload_simple) { menu = true }
                IconBtn(R.drawable.ic_plus, tint = p.accent) { nav.go(Route.Trade()) }
            }
            Gap(12)
            // Summary — only once there is something to sum up.
            if (profile.trades.isNotEmpty()) {
            Glass(Modifier.fillMaxWidth()) {
                Text(t("home.value"), style = Type.cardTitle, color = p.textMid)
                Text(if (sum.holdings.isEmpty()) Fmt.DASH else Fmt.money(sum.valueFiat, fiat), style = Type.big, color = p.text)
                Gap(10)
                Row {
                    KeyValue(t("hold.cost"), Fmt.money(sum.costFiat, fiat, 0), Modifier.weight(1f))
                    KeyValue(t("hold.pnl"), "${Fmt.signedMoney(sum.pnlFiat, fiat, 0)} · ${Fmt.pct(sum.pnlPct, 1)}", Modifier.weight(1.4f), p.change(sum.pnlPct))
                }
                Gap(8)
                Row {
                    KeyValue(t("hold.realized"), Fmt.signedMoney(sum.realizedFiat, fiat, 0), Modifier.weight(1f), p.change(sum.realizedFiat))
                    if (settings.expert) KeyValue(t("hold.pnl_usd"), Fmt.signedMoney(sum.pnlUsd, "USD", 0), Modifier.weight(1.4f), p.change(sum.pnlUsd))
                    else Spacer(Modifier.weight(1.4f))
                }
            }
            }
            Gap(12)
            Segmented(listOf("positions" to t("hold.tab_positions"), "trades" to t("hold.tab_trades"), "alloc" to t("hold.tab_alloc")), tab) { tab = it }
            Gap(12)
        }

        when (tab) {
            "positions" -> item {
                if (sum.holdings.isEmpty()) {
                    EmptyState(R.drawable.ic_wallet, t("tip.start_trades.t"), t("tip.start_trades.b")) {
                        PrimaryButton(t("hold.add"), icon = R.drawable.ic_plus) { nav.go(Route.Trade()) }
                    }
                } else Glass(Modifier.fillMaxWidth(), padding = 0.dp) {
                    sum.holdings.forEachIndexed { i, h ->
                        if (i > 0) Divider(Modifier.padding(horizontal = 14.dp))
                        Column(Modifier.fillMaxWidth().clickable { nav.go(Route.Chart(h.position.coinId)) }.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(h.position.symbol, style = Type.strong, color = p.text)
                                    Text(Fmt.amount(h.position.amount), style = Type.numSmall, color = p.muted)
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(Fmt.money(h.valueFiat, fiat), style = Type.num, color = p.text)
                                    Text("${Fmt.signedMoney(h.pnlFiat, fiat, 0)} · ${Fmt.pct(h.pnlPct, 1)}", style = Type.numSmall, color = p.change(h.pnlPct))
                                }
                            }
                            Gap(8)
                            Row {
                                KeyValue(t("hold.avg"), Fmt.money(h.position.avgCostFiat, fiat), Modifier.weight(1f))
                                KeyValue(t("hold.price"), Fmt.money(h.priceFiat, fiat), Modifier.weight(1f))
                                KeyValue(t("hold.weight"), Fmt.pct(sum.weights[h.position.coinId], 1, signed = false), Modifier.weight(0.7f))
                            }
                        }
                    }
                }
            }

            "trades" -> item {
                val trades = profile.trades.sortedWith(compareByDescending<com.mazecrypto.app.core.Trade> { it.ts }.thenByDescending { it.id })
                if (trades.isEmpty()) {
                    EmptyState(R.drawable.ic_wallet, t("tip.start_trades.t"), t("tip.start_trades.b")) {
                        PrimaryButton(t("hold.add"), icon = R.drawable.ic_plus) { nav.go(Route.Trade()) }
                    }
                } else {
                    Text(t("hold.n_trades", "n" to trades.size), style = Type.small, color = p.muted, modifier = Modifier.padding(bottom = 6.dp))
                    Glass(Modifier.fillMaxWidth(), padding = 0.dp) {
                        trades.forEachIndexed { i, tr ->
                            if (i > 0) Divider(Modifier.padding(horizontal = 14.dp))
                            Row(Modifier.fillMaxWidth().clickable { nav.go(Route.Trade(tr.id)) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(tr.symbol, style = Type.strong, color = p.text)
                                        Spacer(Modifier.width(8.dp))
                                        Badge(t(if (tr.side == "buy") "trade.buy" else "trade.sell"), if (tr.side == "buy") p.up else p.down)
                                    }
                                    Text(listOf(Fmt.date(tr.ts), tr.platform).filter { it.isNotEmpty() }.joinToString(" · "),
                                        style = Type.small, color = p.muted, modifier = Modifier.padding(top = 2.dp))
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(Fmt.money(tr.totalCost, tr.fiat), style = Type.num, color = p.text)
                                    Text(Fmt.amount(tr.amount) + " · " + Fmt.money(tr.cost / tr.amount, tr.fiat), style = Type.numSmall, color = p.muted)
                                }
                            }
                        }
                    }
                }
            }

            else -> item { Allocation(nav, fiat) }
        }
    }

    if (menu) {
        Picker(t("hold.io"), listOf(
            "csv" to t("hold.export_csv"), "json" to t("hold.export_json"), "import" to t("hold.import"), "tax" to t("hold.export_tax"),
        ), null, onDismiss = { menu = false }) { a ->
            when (a) {
                "csv" -> { exportText = Export.toCsv(profile.trades); save.launch("maze-crypto-trades.csv") }
                "json" -> { exportText = Export.toJson(profile.trades); save.launch("maze-crypto-trades.json") }
                "import" -> open.launch(arrayOf("text/*", "application/json", "text/csv", "text/comma-separated-values"))
                "tax" -> yearPick = true
            }
        }
    }
    if (yearPick) {
        val years = (profile.trades.map { java.time.Instant.ofEpochSecond(it.ts).atZone(java.time.ZoneOffset.UTC).year } + LocalDate.now().year)
            .distinct().sortedDescending()
        Picker(t("hold.tax_year"), years.map { it to it.toString() }, null, onDismiss = { yearPick = false }) { y ->
            exportText = Export.reportCsv(Export.yearlyReport(profile.trades, y, fiat), fiat)
            save.launch("maze-crypto-realised-$y-$fiat.csv")
        }
    }
    pendingImport?.let { list ->
        Confirm(t("hold.import_q", "n" to list.size), onDismiss = { pendingImport = null }) {
            Maze.importTrades(list)?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show() }
        }
    }
}

@Composable
private fun Allocation(nav: Nav, fiat: String) {
    val p = LocalPalette.current
    val profile by Maze.profile.collectAsStateWithLifecycle()
    val snap by Maze.snapshot.collectAsStateWithLifecycle()
    val sum = remember(snap, profile, fiat) { Maze.summary() }
    var amount by remember { mutableStateOf("") }
    val targets = profile.targets
    fun name(k: String) = if (k == OTHER) t("targets.other") else profile.targetSymbols[k] ?: Maze.coinName(k)

    Column {
        if (targets.isEmpty()) {
            EmptyState(R.drawable.ic_scales, t("tip.start_targets.t"), t("hold.no_targets")) {
                PrimaryButton(t("hold.targets"), icon = R.drawable.ic_scales) { nav.go(Route.Targets) }
            }
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t("m.hold.alloc_hint"), style = Type.small, color = p.muted, modifier = Modifier.weight(1f))
            GhostButton(t("hold.targets"), icon = R.drawable.ic_pencil_simple) { nav.go(Route.Targets) }
        }
        Gap(10)
        Glass(Modifier.fillMaxWidth()) {
            drift(sum.weights, targets, sum.valueFiat).forEachIndexed { i, d ->
                if (i > 0) Gap(14)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name(d.bucket), style = Type.strong, color = p.text, modifier = Modifier.weight(1f))
                    Text("${Fmt.num(d.actualPct, 1)}% / ${Fmt.num(d.targetPct, 1)}%", style = Type.numSmall, color = p.textMid)
                }
                Gap(6)
                WeightBar(d.actualPct, d.targetPct, if (abs(d.diffPct) > 5) p.warn else p.accent)
                Gap(4)
                val r = d.rebalanceFiat
                Text(if (abs(r) < 0.5) Fmt.DASH else (if (r > 0) "+" else "") + Fmt.money(r, fiat, 0),
                    style = Type.numSmall, color = if (r > 0) p.up else if (r < 0) p.down else p.muted)
            }
        }
        SectionTitle(t("hold.split_title"))
        Glass(Modifier.fillMaxWidth()) {
            Field(amount, { amount = it }, t("hold.split_ph", "fiat" to fiat), number = true)
            val a = Fmt.parseOrNull(amount)
            if (a != null && a > 0) {
                val values = sum.holdings.associate { it.position.coinId to (it.valueFiat ?: 0.0) }
                Gap(10)
                splitContribution(a, values, targets).entries.sortedByDescending { it.value }.forEach { (k, v) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(name(k), style = Type.body, color = p.text, modifier = Modifier.weight(1f))
                        Text(Fmt.money(v, fiat, if (v >= 100) 0 else 2), style = Type.num, color = p.text)
                    }
                }
            }
            Text(t("hold.split_note"), style = Type.small, color = p.muted, modifier = Modifier.padding(top = 10.dp))
        }
    }
}

/** Current weight as a bar, the target as a white tick. */
@Composable
private fun WeightBar(actual: Double, target: Double, color: androidx.compose.ui.graphics.Color) {
    val p = LocalPalette.current
    BoxWithConstraints(Modifier.fillMaxWidth().height(10.dp)) {
        val w = maxWidth
        Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(p.glassHi))
        Box(Modifier.width(w * (actual / 100.0).coerceIn(0.0, 1.0).toFloat()).height(10.dp).clip(RoundedCornerShape(5.dp)).background(color))
        Box(Modifier.offset(x = w * (target / 100.0).coerceIn(0.0, 1.0).toFloat() - 1.dp).width(2.dp).height(10.dp).background(p.text))
    }
}
