package com.mazecrypto.app.ui.screens

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mazecrypto.app.R
import com.mazecrypto.app.core.Coin
import com.mazecrypto.app.core.Condition
import com.mazecrypto.app.core.Fmt
import com.mazecrypto.app.core.Rule
import com.mazecrypto.app.core.t
import com.mazecrypto.app.data.Maze
import com.mazecrypto.app.ui.Nav
import com.mazecrypto.app.ui.Route
import com.mazecrypto.app.ui.components.Chip
import com.mazecrypto.app.ui.components.Confirm
import com.mazecrypto.app.ui.components.Divider
import com.mazecrypto.app.ui.components.EmptyState
import com.mazecrypto.app.ui.components.Field
import com.mazecrypto.app.ui.components.GhostButton
import com.mazecrypto.app.ui.components.Glass
import com.mazecrypto.app.ui.components.Ic
import com.mazecrypto.app.ui.components.IconBtn
import com.mazecrypto.app.ui.components.LinkButton
import com.mazecrypto.app.ui.components.PageHeader
import com.mazecrypto.app.ui.components.Picker
import com.mazecrypto.app.ui.components.PrimaryButton
import com.mazecrypto.app.ui.components.SectionTitle
import com.mazecrypto.app.ui.components.SwitchRow
import com.mazecrypto.app.ui.components.ValueRow
import com.mazecrypto.app.ui.theme.LocalPalette
import com.mazecrypto.app.ui.theme.Type
import com.mazecrypto.app.work.AlertNotifier
import kotlin.math.abs

/** One-line human description of a condition (desktop rule_editor.describe). */
fun describe(c: Condition, fiat: String): String {
    val sym = c.symbol.ifEmpty { Maze.coinName(c.coinId) }
    return when (c.kind) {
        "price" -> t("rule.desc.price_${c.op}", "coin" to sym, "value" to Fmt.money(c.value, if (c.currency == "usd") "USD" else fiat))
        "cost_change" -> t(if (c.pct < 0) "rule.desc.cost_drop" else "rule.desc.cost_gain", "coin" to sym, "pct" to Fmt.num(abs(c.pct), 1))
        "drift" -> t("rule.desc.drift", "what" to (if (c.coinId.isNotEmpty()) Maze.coinName(c.coinId) else t("rule.any_bucket")),
            "pct" to Fmt.num(c.pct, 1))
        "rsi" -> t("rule.desc.rsi_${c.op}", "coin" to sym, "iv" to c.interval.uppercase(), "value" to Fmt.num(c.value, 0), "period" to c.period)
        "sma_cross" -> t("rule.desc.sma_${c.direction}", "coin" to sym, "iv" to c.interval.uppercase(), "fast" to c.fast, "slow" to c.slow)
        "move" -> t("rule.desc.move_${c.direction}", "coin" to sym, "pct" to Fmt.num(c.pct, 1), "hours" to c.hours)
        else -> c.kind
    }
}

private fun templates(coin: Coin, reserve: Coin): Map<String, List<Rule>> {
    val c = coin.symbol
    val r = reserve.symbol
    return mapOf(
        "dip_reserve" to listOf(
            Rule(t("tpl.cost_drop.name", "coin" to c, "pct" to 15), Condition("cost_change", coin.id, c, pct = -15.0), t("tpl.dip_half.msg", "reserve" to r)),
            Rule(t("tpl.cost_drop.name", "coin" to c, "pct" to 25), Condition("cost_change", coin.id, c, pct = -25.0), t("tpl.dip_rest.msg", "reserve" to r)),
        ),
        "rebalance" to listOf(Rule(t("tpl.drift.name", "pct" to 5), Condition("drift", pct = 5.0), t("tpl.drift.msg"))),
        "oversold" to listOf(Rule(t("tpl.rsi.name", "coin" to c), Condition("rsi", coin.id, c, op = "below", value = 30.0, interval = "1d"), "")),
        "big_move" to listOf(Rule(t("tpl.move.name", "coin" to c), Condition("move", coin.id, c, hours = 24, pct = 10.0), "")),
    )
}

@Composable
fun AlertsScreen(nav: Nav) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val settings by Maze.settings.collectAsStateWithLifecycle()
    val profile by Maze.profile.collectAsStateWithLifecycle()
    val log by Maze.alertLog.collectAsStateWithLifecycle()
    var canPost by remember { mutableStateOf(AlertNotifier.canPost(ctx)) }
    var tplCoin by remember { mutableStateOf(Coin("bitcoin", "BTC", "Bitcoin")) }
    var pickTplCoin by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { canPost = AlertNotifier.canPost(ctx) }

    TabScreen {
        item {
            PageHeader(t("rules.title"), t("m.rules.sub")) {
                TopActions(nav, settings = false)
                IconBtn(R.drawable.ic_plus, tint = p.accent) { nav.go(Route.RuleEdit()) }
            }
            Gap(12)
            // Notifications
            Glass(Modifier.fillMaxWidth(), tint = if (canPost) null else p.warn) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Ic(if (canPost) R.drawable.ic_bell else R.drawable.ic_warning, tint = if (canPost) p.up else p.warn, size = 18.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(t(if (canPost && settings.notifications) "m.rules.notify_on" else "m.rules.notify_off"), style = Type.bodySmall,
                        color = p.textMid, modifier = Modifier.weight(1f))
                }
                Gap(10)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!canPost) {
                        PrimaryButton(t("m.rules.enable"), Modifier.weight(1f)) {
                            if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                ask.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                ctx.startActivity(Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        }
                    } else {
                        GhostButton(t("rules.test"), Modifier.weight(1f)) {
                            AlertNotifier.send(ctx, "Maze Crypto", t("rules.test_body"), "test")
                            Toast.makeText(ctx, t("rules.test_ok"), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                Text(if (settings.backgroundMinutes > 0) t("m.rules.bg", "n" to settings.backgroundMinutes) else t("m.rules.bg_off"),
                    style = Type.small, color = p.muted, modifier = Modifier.padding(top = 8.dp))
            }
            Gap(12)
        }
        if (profile.rules.isEmpty()) {
            item { EmptyState(R.drawable.ic_bell, t("rules.what_t"), t("rules.what_b")) {
                PrimaryButton(t("rules.add"), icon = R.drawable.ic_plus) { nav.go(Route.RuleEdit()) }
            } }
        } else {
            item {
                Glass(Modifier.fillMaxWidth(), padding = 0.dp) {
                    profile.rules.forEachIndexed { i, r ->
                        if (i > 0) Divider(Modifier.padding(horizontal = 14.dp))
                        val st = profile.ruleState[r.id]
                        Row(Modifier.fillMaxWidth().clickable { nav.go(Route.RuleEdit(r.id)) }.padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(r.name, style = Type.strong, color = if (r.enabled) p.text else p.muted)
                                Text(describe(r.condition, settings.fiat), style = Type.small, color = p.textMid, modifier = Modifier.padding(top = 2.dp))
                                if (st != null && st.lastFired > 0) {
                                    Text("${t("rules.last")}: ${Fmt.date(st.lastFired.toLong())} ${Fmt.clock((st.lastFired * 1000).toLong())}",
                                        style = Type.small, color = p.muted, modifier = Modifier.padding(top = 2.dp))
                                }
                                if (r.message.isNotEmpty()) Text(r.message, style = Type.small, color = p.accent, modifier = Modifier.padding(top = 2.dp))
                            }
                            Switch(r.enabled, onCheckedChange = { on ->
                                Maze.saveRules(profile.rules.map { if (it.id == r.id) it.copy(enabled = on) else it })
                            }, colors = SwitchDefaults.colors(checkedThumbColor = p.onAccent, checkedTrackColor = p.accent,
                                uncheckedThumbColor = p.textMid, uncheckedTrackColor = p.glassHi, uncheckedBorderColor = p.glassLineHi))
                        }
                    }
                }
                Text(t("rule.edge_note"), style = Type.small, color = p.muted, modifier = Modifier.padding(top = 8.dp))
            }
        }
        item {
            SectionTitle(t("rules.templates"))
            Glass(Modifier.fillMaxWidth()) {
                ValueRow(t("rules.pick_coin"), tplCoin.symbol) { pickTplCoin = true }
                Gap(6)
                val tpls = templates(tplCoin, Coin("tether", "USDT", "Tether"))
                val keys = if (settings.expert) tpls.keys.toList() else listOf("dip_reserve", "big_move")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    keys.forEach { k ->
                        Chip(t("rules.tpl.$k")) {
                            Maze.saveRules(profile.rules + tpls.getValue(k))
                            Toast.makeText(ctx, t("set.saved"), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            SectionTitle(t("m.rules.history")) { if (log.isNotEmpty()) LinkButton(t("m.clear"), arrow = false) { Maze.clearAlertLog() } }
            Glass(Modifier.fillMaxWidth()) {
                if (log.isEmpty()) Text(t("rules.no_history"), style = Type.body, color = p.muted)
                log.take(30).forEachIndexed { i, a ->
                    if (i > 0) Divider(Modifier.padding(vertical = 8.dp))
                    Text(a.name, style = Type.label, color = p.text)
                    Text("${Fmt.date(a.ts.toLong())} ${Fmt.clock((a.ts * 1000).toLong())} · ${a.text}", style = Type.small, color = p.textMid)
                }
            }
        }
    }
    if (pickTplCoin) CoinPicker(t("rules.pick_coin"), { pickTplCoin = false }) { tplCoin = it; pickTplCoin = false }
}

// ── rule editor ─────────────────────────────────────────────────────────────

@Composable
fun RuleEditor(nav: Nav, id: String?) {
    val p = LocalPalette.current
    val settings = Maze.settings.value
    val old = remember(id) { Maze.profile.value.rules.firstOrNull { it.id == id } }
    var cond by remember { mutableStateOf(old?.condition ?: Condition.defaults("price")) }
    var coin by remember { mutableStateOf(old?.condition?.takeIf { it.coinId.isNotEmpty() }?.let { Coin(it.coinId, it.symbol.ifEmpty { Maze.coinName(it.coinId) }, "") }) }
    var name by remember { mutableStateOf(old?.name ?: "") }
    var message by remember { mutableStateOf(old?.message ?: "") }
    var enabled by remember { mutableStateOf(old?.enabled ?: true) }
    var cooldown by remember { mutableStateOf(plain(old?.cooldownHours ?: 24.0)) }
    // Numeric fields as text while editing.
    var value by remember { mutableStateOf(if (cond.value != 0.0) plain(cond.value) else "") }
    var pct by remember { mutableStateOf(plain(cond.pct)) }
    var hours by remember { mutableStateOf(cond.hours.toString()) }
    var period by remember { mutableStateOf(cond.period.toString()) }
    var fast by remember { mutableStateOf(cond.fast.toString()) }
    var slow by remember { mutableStateOf(cond.slow.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    var picker by remember { mutableStateOf<String?>(null) }
    var askDelete by remember { mutableStateOf(false) }
    val kinds = if (settings.expert) Condition.KINDS else Condition.BASIC_KINDS

    fun setKind(k: String) {
        val d = Condition.defaults(k, coin?.id ?: "", coin?.symbol ?: "")
        cond = d
        value = if (d.value != 0.0) plain(d.value) else ""
        pct = plain(d.pct); hours = d.hours.toString(); period = d.period.toString(); fast = d.fast.toString(); slow = d.slow.toString()
    }

    val needsCoin = cond.kind != "drift"
    SubScreen(nav, if (old == null) t("rule.new") else t("rule.edit"), actions = {
        if (old != null) IconBtn(R.drawable.ic_trash, tint = p.down) { askDelete = true }
    }, footer = {
        PrimaryButton(t("btn.save"), Modifier.fillMaxWidth(), icon = R.drawable.ic_check) {
            try {
                val c = coin
                if (needsCoin && c == null) throw IllegalArgumentException(t("trade.err_coin"))
                fun num(s: String) = Fmt.parseOrNull(s) ?: throw IllegalArgumentException(t("trade.err_number"))
                fun int(s: String, lo: Int, hi: Int) = s.trim().toIntOrNull()?.takeIf { it in lo..hi } ?: throw IllegalArgumentException(t("trade.err_number"))
                val built = when (cond.kind) {
                    "price" -> cond.copy(value = num(value).also { if (it <= 0) throw IllegalArgumentException(t("trade.err_number")) })
                    "cost_change" -> cond.copy(pct = num(pct).also { if (it == 0.0) throw IllegalArgumentException(t("rule.err_zero")); if (it < -99 || it > 1000) throw IllegalArgumentException(t("trade.err_number")) })
                    "drift" -> cond.copy(pct = num(pct).coerceIn(0.5, 100.0))
                    "rsi" -> cond.copy(value = num(value).coerceIn(1.0, 99.0), period = int(period, 2, 100))
                    "sma_cross" -> {
                        val f = int(fast, 2, 400); val s = int(slow, 3, 500)
                        if (f >= s) throw IllegalArgumentException(t("rule.err_sma"))
                        cond.copy(fast = f, slow = s)
                    }
                    "move" -> cond.copy(hours = int(hours, 1, 168), pct = num(pct).coerceIn(0.5, 100.0))
                    else -> cond
                }.let { if (needsCoin) it.copy(coinId = c!!.id, symbol = c.symbol) else it }
                val cd = num(cooldown).coerceIn(0.0, 24.0 * 30)
                val fresh = Rule(name.trim().ifEmpty { describe(built, settings.fiat) }.take(80), built, message.trim().take(200), enabled, cd)
                val rule = if (old != null) fresh.copy(id = old.id) else fresh
                val rules = Maze.profile.value.rules
                Maze.saveRules(if (old == null) rules + rule else rules.map { if (it.id == old.id) rule else it })
                nav.back()
            } catch (e: IllegalArgumentException) {
                error = e.message
            }
        }
    }) {
        Gap(6)
        Glass(Modifier.fillMaxWidth(), padding = 12.dp) {
            ValueRow(t("rule.type"), t("rule.kind.${cond.kind}")) { picker = "kind" }
            if (needsCoin) ValueRow(t("rule.f.coin_id"), coin?.symbol ?: t("search.pick")) { picker = "coin" }
            when (cond.kind) {
                "price" -> {
                    ValueRow(t("rule.f.op"), t("rule.opt.${cond.op}")) { picker = "op" }
                    ValueRow(t("rule.f.currency"), if (cond.currency == "usd") "USD" else settings.fiat) { picker = "currency" }
                }
                "rsi" -> {
                    ValueRow(t("rule.f.interval"), t("rule.opt.${cond.interval}")) { picker = "interval" }
                    ValueRow(t("rule.f.op"), t("rule.opt.${cond.op}")) { picker = "op" }
                }
                "sma_cross" -> {
                    ValueRow(t("rule.f.interval"), t("rule.opt.${cond.interval}")) { picker = "interval" }
                    ValueRow(t("rule.f.direction"), t("rule.opt.${cond.direction}")) { picker = "direction" }
                }
                "move" -> ValueRow(t("rule.f.direction"), t("rule.opt.${cond.direction}")) { picker = "direction" }
            }
        }
        Gap(10)
        when (cond.kind) {
            "price" -> Field(value, { value = it }, t("rule.f.value"), number = true)
            "cost_change", "drift" -> Field(pct, { pct = it }, t("rule.f.pct"), number = true)
            "rsi" -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(value, { value = it }, t("rule.f.value"), Modifier.weight(1f), number = true)
                Field(period, { period = it }, t("rule.f.period"), Modifier.weight(1f), number = true)
            }
            "sma_cross" -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(fast, { fast = it }, t("rule.f.fast"), Modifier.weight(1f), number = true)
                Field(slow, { slow = it }, t("rule.f.slow"), Modifier.weight(1f), number = true)
            }
            "move" -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(pct, { pct = it }, t("rule.f.pct"), Modifier.weight(1f), number = true)
                Field(hours, { hours = it }, t("rule.f.hours"), Modifier.weight(1f), number = true)
            }
        }
        Text(t("rule.help.${cond.kind}"), style = Type.small, color = p.muted, modifier = Modifier.padding(top = 6.dp))
        Gap(12)
        Field(name, { name = it }, t("rule.name"), placeholder = describe(cond.copy(coinId = coin?.id ?: "", symbol = coin?.symbol ?: ""), settings.fiat))
        Gap(8)
        Field(message, { message = it }, t("rule.message"), placeholder = t("rule.message_ph"))
        Gap(8)
        Field(cooldown, { cooldown = it }, t("rule.cooldown") + " (h)", number = true)
        SwitchRow(t("rule.enabled"), enabled) { enabled = it }
        Text(t("rule.edge_note"), style = Type.small, color = p.muted)
        error?.let { Text(it, style = Type.body, color = p.down, modifier = Modifier.padding(top = 10.dp)) }
    }

    when (picker) {
        "kind" -> Picker(t("rule.type"), kinds.map { it to t("rule.kind.$it") }, cond.kind, { picker = null }) { setKind(it) }
        "coin" -> CoinPicker(t("rule.f.coin_id"), { picker = null }) { coin = it; picker = null }
        "op" -> Picker(t("rule.f.op"), listOf("below", "above").map { it to t("rule.opt.$it") }, cond.op, { picker = null }) { cond = cond.copy(op = it) }
        "currency" -> Picker(t("rule.f.currency"), listOf("fiat" to settings.fiat, "usd" to "USD"), cond.currency, { picker = null }) { cond = cond.copy(currency = it) }
        "interval" -> Picker(t("rule.f.interval"), listOf("1h", "4h", "1d", "1w").map { it to t("rule.opt.$it") }, cond.interval, { picker = null }) { cond = cond.copy(interval = it) }
        "direction" -> {
            val opts = if (cond.kind == "sma_cross") listOf("golden", "death") else listOf("any", "up", "down")
            Picker(t("rule.f.direction"), opts.map { it to t("rule.opt.$it") }, cond.direction, { picker = null }) { cond = cond.copy(direction = it) }
        }
    }
    if (askDelete && old != null) {
        Confirm(t("rules.delete"), t("rules.delete_q", "name" to old.name), confirm = t("btn.delete"), danger = true, onDismiss = { askDelete = false }) {
            Maze.saveRules(Maze.profile.value.rules.filter { it.id != old.id })
            nav.back()
        }
    }
}
