package com.mazecrypto.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mazecrypto.app.R
import com.mazecrypto.app.core.Coin
import com.mazecrypto.app.core.DcaExtra
import com.mazecrypto.app.core.DcaPlan
import com.mazecrypto.app.core.Fmt
import com.mazecrypto.app.core.Frequency
import com.mazecrypto.app.core.Installment
import com.mazecrypto.app.core.Trade
import com.mazecrypto.app.core.current
import com.mazecrypto.app.core.daysBetween
import com.mazecrypto.app.core.progress
import com.mazecrypto.app.core.schedule
import com.mazecrypto.app.core.t
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
import com.mazecrypto.app.ui.components.Progress
import com.mazecrypto.app.ui.components.SectionTitle
import com.mazecrypto.app.ui.components.SwitchRow
import com.mazecrypto.app.ui.components.ValueRow
import com.mazecrypto.app.ui.theme.LocalPalette
import com.mazecrypto.app.ui.theme.Type
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@Composable
fun PlanScreen(nav: Nav) {
    val p = LocalPalette.current
    val settings by Maze.settings.collectAsStateWithLifecycle()
    val profile by Maze.profile.collectAsStateWithLifecycle()
    val snap by Maze.snapshot.collectAsStateWithLifecycle()
    val plan = profile.dcaPlan
    var mark by remember { mutableStateOf<Installment?>(null) }
    var askDelete by remember { mutableStateOf(false) }
    var showAll by remember { mutableStateOf(false) }

    TabScreen {
        item {
            PageHeader(t("dca.title"), t("dca.sub")) {
                TopActions(nav, settings = false)
                if (plan != null) IconBtn(R.drawable.ic_pencil_simple) { nav.go(Route.DcaEdit) }
            }
            Gap(12)
        }
        if (plan == null) {
            item {
                EmptyState(R.drawable.ic_calendar_check, t("dca.what_t"), t("dca.what_b")) {
                    PrimaryButton(t("dca.create"), icon = R.drawable.ic_plus) { nav.go(Route.DcaEdit) }
                }
                Gap(10)
                Text(t("dca.empty"), style = Type.small, color = p.muted)
            }
            return@TabScreen
        }
        val sum = Maze.summary()
        val values = sum.holdings.associate { it.position.coinId to (it.valueFiat ?: 0.0) }
        val sched = schedule(plan, values, profile.targets)
        val cur = current(sched, profile.dcaDone, LocalDate.now())
        val prog = progress(sched, profile.dcaDone)
        val money = Maze.moneyFmt(plan.fiat)

        item {
            // ── this period ───────────────────────────────────────────────
            if (cur == null) {
                Glass(Modifier.fillMaxWidth(), tint = p.up) {
                    Text(t("dca.finished"), style = Type.strong, color = p.up)
                }
            } else {
                val (inst, state) = cur
                val color = when (state) { "overdue" -> p.warn; "due" -> p.accent; else -> p.textMid }
                Glass(Modifier.fillMaxWidth(), tint = if (state == "upcoming") null else color) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t("dca.period_n", "n" to inst.index + 1, "total" to plan.periods), style = Type.cardTitle, color = p.textMid,
                            modifier = Modifier.weight(1f))
                        Badge(t("dca.state.$state"), color)
                    }
                    Text(money(inst.total), style = Type.big, color = p.text, modifier = Modifier.padding(top = 4.dp))
                    val days = daysBetween(LocalDate.now(), inst.due)
                    Text("${Fmt.date(inst.due)} · " + when {
                        days > 0 -> t("dca.in_days", "n" to days)
                        days == 0L -> t("dca.today")
                        else -> t("dca.ago", "n" to -days)
                    }, style = Type.bodySmall, color = p.textMid)
                    Gap(10)
                    inst.items.forEach { it ->
                        val price = snap?.row(it.coinId)?.priceFiat
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(it.symbol, style = Type.strong, color = p.text, modifier = Modifier.width(70.dp))
                            Text(if (price != null && plan.fiat == settings.fiat) "≈ ${Fmt.amount(it.amount / price)}" else "",
                                style = Type.numSmall, color = p.muted, modifier = Modifier.weight(1f))
                            Text(money(it.amount), style = Type.num, color = p.text)
                        }
                    }
                    Gap(10)
                    PrimaryButton(t("dca.mark_done"), Modifier.fillMaxWidth(), icon = R.drawable.ic_check) { mark = inst }
                }
            }
            Gap(12)
            // ── progress ─────────────────────────────────────────────────
            Glass(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t("dca.progress"), style = Type.cardTitle, color = p.textMid, modifier = Modifier.weight(1f))
                    Text("${prog.done} / ${prog.total}", style = Type.num, color = p.text)
                }
                Gap(8)
                Progress(if (prog.total == 0) 0f else prog.done.toFloat() / prog.total)
                Gap(10)
                Row {
                    KeyValue(t("dca.invested"), money(prog.invested), Modifier.weight(1f))
                    KeyValue(t("dca.remaining"), money(prog.remaining), Modifier.weight(1f))
                }
                Gap(6)
                Text("${plan.name} · ${t("dcaed.freq.${plan.frequency.key}")} · " +
                    t(if (plan.followTargets) "dca.mode_targets" else "dca.mode_fixed"), style = Type.small, color = p.muted)
            }
            SectionTitle(t("dca.schedule"))
        }
        item {
            val list = if (showAll) sched else sched.filter { it.index !in profile.dcaDone }.take(6).ifEmpty { sched.takeLast(3) }
            Glass(Modifier.fillMaxWidth(), padding = 0.dp) {
                list.forEachIndexed { i, inst ->
                    if (i > 0) Divider(Modifier.padding(horizontal = 14.dp))
                    val done = inst.index in profile.dcaDone
                    Row(Modifier.fillMaxWidth().clickable {
                        Maze.updateProfile { pr -> pr.copy(dcaDone = if (done) pr.dcaDone - inst.index else pr.dcaDone + inst.index) }
                    }.padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(done, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = p.accent,
                            checkmarkColor = p.onAccent, uncheckedColor = p.border2), modifier = Modifier.padding(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("#${inst.index + 1} · ${Fmt.date(inst.due)}", style = Type.label, color = if (done) p.muted else p.text)
                            Text(inst.items.joinToString(" · ") { it.symbol }, style = Type.small, color = p.muted, maxLines = 1)
                        }
                        Text(money(inst.total), style = Type.num, color = if (done) p.muted else p.text, modifier = Modifier.padding(end = 10.dp))
                    }
                }
            }
            com.mazecrypto.app.ui.components.LinkButton(if (showAll) t("m.hide") else t("m.plan.show_all", "n" to sched.size), arrow = false) {
                showAll = !showAll
            }
            Gap(10)
            GhostButton(t("dca.delete"), danger = true, icon = R.drawable.ic_trash) { askDelete = true }
        }
    }

    mark?.let { inst -> MarkDoneDialog(inst, plan!!, settings.fiat, onDismiss = { mark = null }) }
    if (askDelete) {
        Confirm(t("dca.delete"), t("dca.delete_q"), confirm = t("btn.delete"), danger = true, onDismiss = { askDelete = false }) {
            Maze.updateProfile { it.copy(dcaPlan = null, dcaDone = emptySet()) }
        }
    }
}

/** Tick a period off; optionally record its purchases as trades at today's prices. */
@Composable
private fun MarkDoneDialog(inst: Installment, plan: DcaPlan, fiat: String, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    var log by remember { mutableStateOf(plan.fiat == fiat) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = p.surface,
        title = { Text(t("dca.mark_q", "n" to inst.index + 1), style = Type.strong.copy(fontSize = 17.sp), color = p.text) },
        text = {
            Column {
                SwitchRow(t("dca.log_trades"), log, enabled = plan.fiat == fiat) { log = it }
                Text(t("m.plan.log_note"), style = Type.small, color = p.muted)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                Maze.updateProfile { it.copy(dcaDone = it.dcaDone + inst.index) }
                if (log) {
                    val snap = Maze.snapshot.value
                    val rate = snap?.fx?.get(fiat)
                    val ts = LocalDate.now().atTime(LocalTime.now()).atZone(ZoneId.systemDefault()).toEpochSecond()
                    inst.items.forEach { item ->
                        val price = snap?.row(item.coinId)?.priceFiat ?: return@forEach
                        Maze.saveTrade(Trade(ts, item.coinId, item.symbol, item.amount / price, item.amount, plan.fiat,
                            note = t("dca.trade_note", "n" to inst.index + 1), usdRate = rate, platform = ""))
                    }
                }
                onDismiss()
            }) { Text(t("dca.done"), style = Type.button, color = p.accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("btn.cancel"), style = Type.button, color = p.textMid) } },
    )
}

// ── plan editor ─────────────────────────────────────────────────────────────

@Composable
fun DcaEditor(nav: Nav) {
    val p = LocalPalette.current
    val settings = Maze.settings.value
    val old = remember { Maze.profile.value.dcaPlan }
    data class Alloc(val id: String, val sym: String, val pct: String)
    data class Extra(val period: String, val id: String, val sym: String, val amount: String)
    var name by remember { mutableStateOf(old?.name ?: "DCA") }
    var start by remember { mutableStateOf(old?.start ?: LocalDate.now()) }
    var freq by remember { mutableStateOf(old?.frequency ?: Frequency.WEEKLY) }
    var periods by remember { mutableStateOf((old?.periods ?: 12).toString()) }
    var amount by remember { mutableStateOf(old?.amount?.let { plain(it) } ?: "") }
    var follow by remember { mutableStateOf(old?.followTargets ?: false) }
    val alloc = remember {
        mutableStateListOf<Alloc>().apply {
            if (old != null) old.allocation.forEach { (k, v) -> add(Alloc(k, old.symbols[k] ?: Maze.coinName(k), plain(v))) }
            else { add(Alloc("bitcoin", "BTC", "70")); add(Alloc("ethereum", "ETH", "30")) }
        }
    }
    val extras = remember {
        mutableStateListOf<Extra>().apply { old?.extras?.forEach { add(Extra((it.period + 1).toString(), it.coinId, it.symbol, plain(it.amount))) } }
    }
    var pickFreq by remember { mutableStateOf(false) }
    var pickFor by remember { mutableStateOf<String?>(null) }    // "alloc" or "extra"
    var error by remember { mutableStateOf<String?>(null) }
    val hasTargets = Maze.profile.value.targets.isNotEmpty()
    val total = alloc.sumOf { Fmt.parseOrNull(it.pct) ?: 0.0 }

    SubScreen(nav, t("dcaed.title"), footer = {
        PrimaryButton(t("btn.save"), Modifier.fillMaxWidth(), icon = R.drawable.ic_check) {
            try {
                val n = periods.trim().toIntOrNull() ?: throw IllegalArgumentException(t("trade.err_number"))
                val a = Fmt.parseOrNull(amount) ?: throw IllegalArgumentException(t("trade.err_number"))
                val plan = DcaPlan(
                    name.trim().ifEmpty { "DCA" }, start, freq, n, a, old?.fiat ?: settings.fiat,
                    alloc.associate { it.id to (Fmt.parseOrNull(it.pct) ?: throw IllegalArgumentException(t("trade.err_number"))) },
                    alloc.associate { it.id to it.sym },
                    extras.map {
                        DcaExtra((it.period.toIntOrNull() ?: 0) - 1, it.id, it.sym,
                            Fmt.parseOrNull(it.amount) ?: throw IllegalArgumentException(t("trade.err_number")))
                    },
                    follow && hasTargets,
                )
                plan.validate()
                Maze.updateProfile { pr ->
                    // A different schedule makes old check-offs meaningless.
                    val keep = old != null && old.start == plan.start && old.frequency == plan.frequency
                    pr.copy(dcaPlan = plan, dcaDone = if (keep) pr.dcaDone.filter { it < plan.periods }.toSet() else emptySet())
                }
                nav.back()
            } catch (e: Exception) {
                error = t("dcaed.err", "err" to (e.message ?: ""))
            }
        }
    }) {
        Gap(6)
        Field(name, { name = it }, t("dcaed.name"))
        Gap(8)
        Glass(Modifier.fillMaxWidth(), padding = 12.dp) {
            DateField(t("dcaed.start"), start) { start = it }
            ValueRow(t("dcaed.freq"), t("dcaed.freq.${freq.key}")) { pickFreq = true }
        }
        Gap(8)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(amount, { amount = it }, t("dcaed.amount", "fiat" to (old?.fiat ?: settings.fiat)), Modifier.weight(1.4f), number = true)
            Field(periods, { periods = it }, t("dcaed.periods"), Modifier.weight(1f), number = true)
        }
        val a = Fmt.parseOrNull(amount)
        val n = periods.toIntOrNull()
        if (a != null && n != null) {
            Text(t("dcaed.total", "total" to Fmt.money(a * n, old?.fiat ?: settings.fiat, 0)), style = Type.small, color = p.muted,
                modifier = Modifier.padding(top = 6.dp))
        }
        SectionTitle(t("dcaed.alloc"))
        if (hasTargets) SwitchRow(t("dcaed.follow"), follow) { follow = it }
        if (follow && hasTargets) {
            Text(t("dcaed.follow_note"), style = Type.bodySmall, color = p.textMid)
        } else {
            alloc.forEachIndexed { i, r ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                    Text(r.sym, style = Type.strong, color = p.text, modifier = Modifier.width(70.dp))
                    Field(r.pct, { alloc[i] = r.copy(pct = it) }, "%", Modifier.weight(1f), number = true)
                    IconBtn(R.drawable.ic_x) { alloc.removeAt(i) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                GhostButton(t("dcaed.add_coin"), icon = R.drawable.ic_plus) { pickFor = "alloc" }
                Spacer(Modifier.weight(1f))
                Text("${Fmt.num(total, 1)}%", style = Type.strong, color = if (kotlin.math.abs(total - 100) > 0.01) p.warn else p.up)
            }
        }
        SectionTitle(t("dcaed.extras"))
        Text(t("dcaed.extras_help"), style = Type.small, color = p.muted)
        extras.forEachIndexed { i, e ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Field(e.period, { extras[i] = e.copy(period = it) }, "#", Modifier.width(70.dp), number = true)
                Text(e.sym, style = Type.strong, color = p.text, modifier = Modifier.width(54.dp))
                Field(e.amount, { extras[i] = e.copy(amount = it) }, t("dca.amount"), Modifier.weight(1f), number = true)
                IconBtn(R.drawable.ic_x) { extras.removeAt(i) }
            }
        }
        GhostButton(t("dcaed.add_extra"), icon = R.drawable.ic_plus) { pickFor = "extra" }
        error?.let { Text(it, style = Type.body, color = p.down, modifier = Modifier.padding(top = 10.dp)) }
    }

    if (pickFreq) Picker(t("dcaed.freq"), Frequency.entries.map { it to t("dcaed.freq.${it.key}") }, freq, { pickFreq = false }) { freq = it }
    pickFor?.let { target ->
        CoinPicker(t("trade.coin"), { pickFor = null }) { c: Coin ->
            if (target == "alloc") { if (alloc.none { it.id == c.id }) alloc.add(Alloc(c.id, c.symbol, "")) }
            else extras.add(Extra("1", c.id, c.symbol, ""))
            pickFor = null
        }
    }
}
