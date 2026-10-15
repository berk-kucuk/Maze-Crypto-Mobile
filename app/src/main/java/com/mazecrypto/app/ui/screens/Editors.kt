package com.mazecrypto.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mazecrypto.app.R
import com.mazecrypto.app.core.Coin
import com.mazecrypto.app.core.Fmt
import com.mazecrypto.app.core.OTHER
import com.mazecrypto.app.core.Trade
import com.mazecrypto.app.core.t
import com.mazecrypto.app.core.validateTargets
import com.mazecrypto.app.data.Maze
import com.mazecrypto.app.ui.Nav
import com.mazecrypto.app.ui.components.Chip
import com.mazecrypto.app.ui.components.Confirm
import com.mazecrypto.app.ui.components.Field
import com.mazecrypto.app.ui.components.GhostButton
import com.mazecrypto.app.ui.components.Glass
import com.mazecrypto.app.ui.components.IconBtn
import com.mazecrypto.app.ui.components.Picker
import com.mazecrypto.app.ui.components.PrimaryButton
import com.mazecrypto.app.ui.components.Segmented
import com.mazecrypto.app.ui.components.ValueRow
import com.mazecrypto.app.ui.theme.LocalPalette
import com.mazecrypto.app.ui.theme.Type
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

val FIATS = listOf("USD", "EUR", "TRY", "GBP", "CHF", "JPY", "CAD", "AUD", "PLN", "SEK", "NOK", "BRL", "INR", "KRW")

/** Pick a coin from the ones the app already knows, or search CoinGecko. */
@Composable
fun CoinPicker(title: String, onDismiss: () -> Unit, onPick: (Coin) -> Unit) {
    var search by remember { mutableStateOf(false) }
    if (search) {
        CoinSearchSheet(onDismiss = onDismiss, closeOnPick = true) { onPick(it) }
        return
    }
    val coins = Maze.trackedCoins()
    Picker(title, coins.map { it to "${it.symbol} · ${it.name}" } + (null to t("trade.other_coin")), null, onDismiss = {
        if (!search) onDismiss()
    }) { c ->
        if (c == null) search = true else onPick(c)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(label: String, date: LocalDate, onChange: (LocalDate) -> Unit) {
    val p = LocalPalette.current
    var open by remember { mutableStateOf(false) }
    ValueRow(label, Fmt.date(date)) { open = true }
    if (open) {
        val state = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    open = false
                }) { Text(t("btn.ok"), color = p.accent) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(t("btn.cancel"), color = p.textMid) } },
            colors = DatePickerDefaults.colors(containerColor = p.surface),
        ) {
            DatePicker(state, colors = DatePickerDefaults.colors(
                containerColor = p.surface, selectedDayContainerColor = p.accent, selectedDayContentColor = p.onAccent,
                todayDateBorderColor = p.accent, todayContentColor = p.accent,
            ))
        }
    }
}

// ── trade ───────────────────────────────────────────────────────────────────

@Composable
fun TradeEditor(nav: Nav, id: Long, presetCoin: String?, presetSide: String) {
    val p = LocalPalette.current
    val settings by Maze.settings.collectAsStateWithLifecycle()
    val snap by Maze.snapshot.collectAsStateWithLifecycle()
    val existing = remember(id) { Maze.profile.value.trades.firstOrNull { it.id == id && id != 0L } }
    var side by remember { mutableStateOf(existing?.side ?: presetSide) }
    var coin by remember {
        mutableStateOf(existing?.let { Coin(it.coinId, it.symbol, it.symbol) }
            ?: presetCoin?.let { c -> Maze.trackedCoins().firstOrNull { it.id == c } })
    }
    val zone = ZoneId.systemDefault()
    var date by remember { mutableStateOf(existing?.let { Instant.ofEpochSecond(it.ts).atZone(zone).toLocalDate() } ?: LocalDate.now()) }
    var amount by remember { mutableStateOf(existing?.let { plain(it.amount) } ?: "") }
    var cost by remember { mutableStateOf(existing?.cost?.let { plain(it) } ?: "") }
    var fee by remember { mutableStateOf(existing?.fee?.takeIf { it > 0 }?.let { plain(it) } ?: "") }
    var fiat by remember { mutableStateOf(existing?.fiat ?: settings.fiat) }
    var rate by remember {
        mutableStateOf(existing?.usdRate?.let { plain(it) } ?: snap?.fx?.get(existing?.fiat ?: settings.fiat)?.let { plain(it) } ?: "")
    }
    var platform by remember { mutableStateOf(existing?.platform ?: "") }
    var note by remember { mutableStateOf(existing?.note ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    var pickCoin by remember { mutableStateOf(false) }
    var pickFiat by remember { mutableStateOf(false) }
    var askDelete by remember { mutableStateOf(false) }

    val amt = Fmt.parseOrNull(amount)
    val cst = Fmt.parseOrNull(cost)

    SubScreen(nav, if (existing == null) t("trade.new") else t("trade.edit"), actions = {
        if (existing != null) IconBtn(R.drawable.ic_trash, tint = p.down) { askDelete = true }
    }, footer = {
        PrimaryButton(t("btn.save"), Modifier.fillMaxWidth(), icon = R.drawable.ic_check) {
            val c = coin
            val f = Fmt.parseOrNull(fee.ifBlank { "0" })
            val r = if (rate.isBlank()) null else Fmt.parseOrNull(rate)
            error = when {
                c == null -> t("trade.err_coin")
                amt == null || amt <= 0 || cst == null || cst < 0 || f == null || f < 0 || (rate.isNotBlank() && (r == null || r <= 0)) ->
                    t("trade.err_number")
                else -> {
                    // Keep the original time of day when only other fields changed; noon for new dates.
                    val time = existing?.let { Instant.ofEpochSecond(it.ts).atZone(zone) }?.takeIf { it.toLocalDate() == date }?.toLocalTime()
                        ?: LocalTime.NOON
                    Maze.saveTrade(Trade(
                        ts = date.atTime(time).atZone(zone).toEpochSecond(), coinId = c.id, symbol = c.symbol,
                        amount = amt, cost = cst, fiat = fiat, fee = f, side = side, platform = platform, note = note,
                        usdRate = r, id = existing?.id ?: 0,
                    ))
                }
            }
            if (error == null) nav.back()
        }
    }) {
        Gap(6)
        Segmented(listOf("buy" to t("trade.buy"), "sell" to t("trade.sell")), side) { side = it }
        Gap(10)
        Glass(Modifier.fillMaxWidth(), padding = 12.dp) {
            ValueRow(t("trade.coin"), coin?.let { "${it.symbol} · ${it.name}" } ?: t("search.pick")) { pickCoin = true }
            DateField(t("trade.date"), date) { date = it }
            ValueRow(t("trade.currency"), fiat) { pickFiat = true }
        }
        Gap(12)
        Field(amount, { amount = it }, t("trade.amount"), number = true)
        Gap(8)
        Field(cost, { cost = it }, t("trade.cost"), placeholder = t("trade.cost_ph"), number = true,
            supporting = if (amt != null && amt > 0 && cst != null) t("trade.unit", "price" to Fmt.money(cst / amt, fiat)) else null)
        Gap(8)
        Field(fee, { fee = it }, t("trade.fee"), number = true)
        Gap(8)
        Field(rate, { rate = it }, t("trade.rate"), placeholder = t("trade.rate_ph", "fiat" to fiat), number = true,
            supporting = t("trade.rate_hint"))
        Gap(8)
        Field(platform, { platform = it }, t("trade.platform"))
        Gap(8)
        Field(note, { note = it }, t("trade.note"), placeholder = t("trade.note_ph"), singleLine = false)
        error?.let { Text(it, style = Type.body, color = p.down, modifier = Modifier.padding(top = 10.dp)) }
    }

    if (pickCoin) CoinPicker(t("trade.coin"), { pickCoin = false }) { coin = it; pickCoin = false }
    if (pickFiat) Picker(t("trade.currency"), FIATS.map { it to it }, fiat, { pickFiat = false }) {
        fiat = it
        Maze.snapshot.value?.fx?.get(it)?.let { r -> rate = plain(r) }
    }
    if (askDelete && existing != null) {
        Confirm(t("trade.delete"), t("trade.delete_q", "sym" to existing.symbol, "date" to Fmt.date(existing.ts)),
            confirm = t("btn.delete"), danger = true, onDismiss = { askDelete = false }) {
            val err = Maze.deleteTrade(existing.id)
            if (err == null) nav.back() else error = err
        }
    }
}

/** A number as the user would type it in the current language, without grouping. */
fun plain(v: Double): String {
    val s = java.math.BigDecimal(v).round(java.math.MathContext(12)).stripTrailingZeros().toPlainString()
    return if (com.mazecrypto.app.core.I18n.language == "tr") s.replace('.', ',') else s
}

// ── targets ─────────────────────────────────────────────────────────────────

@Composable
fun TargetsEditor(nav: Nav) {
    val p = LocalPalette.current
    val profile = remember { Maze.profile.value }
    data class Row2(val id: String, val sym: String, val pct: String)
    val rows = remember {
        mutableStateListOf<Row2>().apply {
            profile.targets.filterKeys { it != OTHER }.forEach { (k, v) -> add(Row2(k, profile.targetSymbols[k] ?: Maze.coinName(k), plain(v))) }
        }
    }
    var other by remember { mutableStateOf(profile.targets[OTHER]?.let { plain(it) } ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    var pick by remember { mutableStateOf(false) }
    val total = rows.sumOf { Fmt.parseOrNull(it.pct) ?: 0.0 } + (Fmt.parseOrNull(other) ?: 0.0)

    fun template(name: String) {
        val btc = "bitcoin" to "BTC"
        val eth = "ethereum" to "ETH"
        val sol = "solana" to "SOL"
        val usdt = "tether" to "USDT"
        val tpl = when (name) {
            "btc_heavy" -> listOf(btc to 60.0, eth to 20.0, usdt to 10.0) to 10.0
            "balanced" -> listOf(btc to 40.0, eth to 25.0, sol to 10.0, usdt to 10.0) to 15.0
            else -> emptyList<Pair<Pair<String, String>, Double>>() to 0.0
        }
        rows.clear()
        tpl.first.forEach { (c, v) -> rows.add(Row2(c.first, c.second, plain(v))) }
        other = if (tpl.second > 0) plain(tpl.second) else ""
    }

    SubScreen(nav, t("targets.title"), footer = {
        PrimaryButton(t("btn.save"), Modifier.fillMaxWidth(), icon = R.drawable.ic_check) {
            try {
                val map = LinkedHashMap<String, Double>()
                rows.forEach { r -> map[r.id] = Fmt.parseOrNull(r.pct) ?: throw IllegalArgumentException(t("trade.err_number")) }
                if (other.isNotBlank()) map[OTHER] = Fmt.parseOrNull(other) ?: throw IllegalArgumentException(t("trade.err_number"))
                val clean = try { validateTargets(map) } catch (e: Exception) { throw IllegalArgumentException(t("targets.err_sum")) }
                Maze.updateProfile { it.copy(targets = clean, targetSymbols = rows.associate { r -> r.id to r.sym }) }
                nav.back()
            } catch (e: IllegalArgumentException) {
                error = e.message
            }
        }
    }) {
        Text(t("targets.help"), style = Type.bodySmall, color = p.textMid, modifier = Modifier.padding(vertical = 6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("btc_heavy", "balanced", "empty").forEach { k -> Chip(t("targets.tpl.$k")) { template(k) } }
        }
        Gap(12)
        rows.forEachIndexed { i, r ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                Text(r.sym, style = Type.strong, color = p.text, modifier = Modifier.width(72.dp))
                Field(r.pct, { rows[i] = r.copy(pct = it) }, "%", modifier = Modifier.weight(1f), number = true)
                IconBtn(R.drawable.ic_x) { rows.removeAt(i) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
            Column(Modifier.width(72.dp)) {
                Text(t("targets.other"), style = Type.strong, color = p.text)
            }
            Field(other, { other = it }, "%", modifier = Modifier.weight(1f), number = true, supporting = t("targets.other_tip"))
            IconBtn(R.drawable.ic_x, enabled = false) {}
        }
        Gap(6)
        GhostButton(t("targets.add_coin"), icon = R.drawable.ic_plus) { pick = true }
        Gap(10)
        Text(t("targets.total", "total" to Fmt.num(total, 1)), style = Type.strong, color = if (total > 100.0001) p.down else p.textMid)
        error?.let { Text(it, style = Type.body, color = p.down, modifier = Modifier.padding(top = 8.dp)) }
    }
    if (pick) CoinPicker(t("targets.add_coin"), { pick = false }) { c ->
        if (rows.none { it.id == c.id }) rows.add(Row2(c.id, c.symbol, ""))
        pick = false
    }
}

