package com.mazecrypto.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mazecrypto.app.R
import com.mazecrypto.app.core.t
import com.mazecrypto.app.data.EXCHANGE_INFO
import com.mazecrypto.app.data.Http
import com.mazecrypto.app.data.Maze
import com.mazecrypto.app.data.MarketService
import com.mazecrypto.app.ui.Nav
import com.mazecrypto.app.ui.Route
import com.mazecrypto.app.ui.components.Confirm
import com.mazecrypto.app.ui.components.Divider
import com.mazecrypto.app.ui.components.Field
import com.mazecrypto.app.ui.components.GhostButton
import com.mazecrypto.app.ui.components.Glass
import com.mazecrypto.app.ui.components.IconBtn
import com.mazecrypto.app.ui.components.Picker
import com.mazecrypto.app.ui.components.SectionTitle
import com.mazecrypto.app.ui.components.Segmented
import com.mazecrypto.app.ui.components.SwitchRow
import com.mazecrypto.app.ui.components.ValueRow
import com.mazecrypto.app.ui.theme.LocalPalette
import com.mazecrypto.app.ui.theme.Palette
import com.mazecrypto.app.ui.theme.Type
import kotlinx.coroutines.launch

val LANG_NAMES = mapOf("en" to "English", "tr" to "Türkçe")

@Composable
fun SettingsScreen(nav: Nav) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by Maze.settings.collectAsStateWithLifecycle()
    var picker by remember { mutableStateOf<String?>(null) }
    var proxyUrl by remember { mutableStateOf(s.proxyUrl) }
    var proxyState by remember { mutableStateOf("") }
    var askRerun by remember { mutableStateOf(false) }

    fun minutes(m: Int) = if (m == 0) t("m.set.never") else if (m < 60) t("m.set.min", "n" to m) else t("m.set.hours", "n" to m / 60)
    fun seconds(x: Int) = if (x < 60) t("m.set.sec", "n" to x) else t("m.set.min", "n" to x / 60)

    SubScreen(nav, t("set.title")) {
        // ── widgets ───────────────────────────────────────────────────────
        SectionTitle(t("m.set.widgets"))
        Glass(Modifier.fillMaxWidth()) {
            Text(t("m.set.widgets_help"), style = Type.bodySmall, color = p.textMid)
            Gap(10)
            val kinds = listOf("favorites" to "widget.favorites", "grid" to "widget.grid", "coin" to "widget.coin",
                "movers" to "widget.movers", "portfolio" to "widget.portfolio", "holdings" to "widget.holdings", "plan" to "widget.plan")
            kinds.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    pair.forEach { (k, label) ->
                        GhostButton(t(label), Modifier.weight(1f), icon = R.drawable.ic_plus) {
                            if (!ctx.pinWidget(k)) Toast.makeText(ctx, t("m.set.pin_unsupported"), Toast.LENGTH_LONG).show()
                        }
                    }
                    if (pair.size == 1) androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                }
            }
            Gap(6)
            ValueRow(t("m.set.favorites"), t("m.set.n_favorites", "n" to s.widgetCoins().size)) { nav.tab(Route.Prices()) }
            Divider()
            Text(t("m.set.change"), style = Type.body, color = p.text, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
            Segmented(listOf("1h" to "1h", "24h" to "24h", "7d" to "7d"), s.widgetChange) { v -> Maze.updateSettings { it.copy(widgetChange = v) } }
            Gap(6)
            ValueRow(t("m.set.background"), minutes(s.backgroundMinutes), t("m.set.background_help")) { picker = "bg" }
            if (s.backgroundMinutes > 0) BackgroundStatus()
            SwitchRow(t("m.set.private"), s.widgetPrivate, t("m.set.private_help")) { v -> Maze.updateSettings { it.copy(widgetPrivate = v) } }
        }

        // ── general ───────────────────────────────────────────────────────
        SectionTitle(t("set.general"))
        Glass(Modifier.fillMaxWidth(), padding = 12.dp) {
            ValueRow(t("set.language"), LANG_NAMES[s.language] ?: s.language) { picker = "lang" }
            ValueRow(t("set.fiat"), s.fiat) { picker = "fiat" }
            ValueRow(t("set.refresh"), seconds(s.refreshSeconds), t("m.set.refresh_help")) { picker = "refresh" }
        }

        // ── experience ────────────────────────────────────────────────────
        SectionTitle(t("set.mode"))
        Glass(Modifier.fillMaxWidth()) {
            Segmented(listOf(false to t("mode.basic"), true to t("mode.expert")), s.expert) { v -> Maze.updateSettings { it.copy(expert = v) } }
            Text(t("set.mode_help") + " " + t("m.set.expert_unlocks"), style = Type.small, color = p.muted, modifier = Modifier.padding(top = 8.dp))
            Gap(12)
            Text(t("set.accent"), style = Type.body, color = p.text)
            Gap(8)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                listOf("champagne", "gold", "violet", "mono").forEach { a ->
                    val c = Palette.of(a).accent
                    Box(Modifier.size(36.dp).clip(CircleShape).background(c).clickable { Maze.updateSettings { it.copy(accent = a) } },
                        contentAlignment = Alignment.Center) {
                        if (s.accent == a) com.mazecrypto.app.ui.components.Ic(R.drawable.ic_check, tint = Palette.of(a).onAccent, size = 18.dp)
                    }
                }
            }
            Text(t("accent.${s.accent}"), style = Type.small, color = p.muted, modifier = Modifier.padding(top = 6.dp))
        }

        // ── price sources ─────────────────────────────────────────────────
        SectionTitle(t("set.exchanges"))
        Glass(Modifier.fillMaxWidth(), padding = 12.dp) {
            Text(t("m.set.exchanges_help"), style = Type.small, color = p.muted, modifier = Modifier.padding(bottom = 6.dp))
            val order = s.exchanges + EXCHANGE_INFO.map { it.id }.filter { it !in s.exchanges }
            order.forEach { id ->
                val ex = EXCHANGE_INFO.first { it.id == id }
                val on = id in s.exchanges
                val i = s.exchanges.indexOf(id)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        SwitchRow("${ex.name}", on, ex.country) { v ->
                            Maze.updateSettings { st -> st.copy(exchanges = if (v) st.exchanges + id else st.exchanges - id) }
                        }
                    }
                    if (s.expert && on) {
                        IconBtn(R.drawable.ic_caret_up, enabled = i > 0, size = 18.dp) {
                            Maze.updateSettings { st -> st.copy(exchanges = st.exchanges.toMutableList().also { l -> l[i] = l[i - 1].also { l[i - 1] = l[i] } }) }
                        }
                        IconBtn(R.drawable.ic_caret_down, enabled = i < s.exchanges.size - 1, size = 18.dp) {
                            Maze.updateSettings { st -> st.copy(exchanges = st.exchanges.toMutableList().also { l -> l[i] = l[i + 1].also { l[i + 1] = l[i] } }) }
                        }
                    }
                }
            }
            if (s.exchanges.isEmpty()) Text(t("m.set.no_exchange"), style = Type.small, color = p.warn)
        }

        // ── alerts ────────────────────────────────────────────────────────
        SectionTitle(t("nav.rules"))
        Glass(Modifier.fillMaxWidth(), padding = 12.dp) {
            SwitchRow(t("m.set.notify"), s.notifications) { v -> Maze.updateSettings { it.copy(notifications = v) } }
        }

        // ── network & privacy ─────────────────────────────────────────────
        if (s.expert) {
            SectionTitle(t("set.network"))
            Glass(Modifier.fillMaxWidth()) {
                SwitchRow(t("set.proxy_on"), s.proxyEnabled) { v ->
                    if (v && runCatching { Http.parseProxy(proxyUrl) }.isFailure) proxyState = t("set.invalid", "err" to proxyUrl)
                    else Maze.updateSettings { it.copy(proxyEnabled = v, proxyUrl = proxyUrl) }
                }
                Field(proxyUrl, { proxyUrl = it }, t("set.proxy_url"), supporting = t("m.set.proxy_help"))
                Gap(8)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton(t("set.test"), Modifier.weight(1f)) {
                        proxyState = t("set.testing")
                        scope.launch {
                            proxyState = try {
                                Http.parseProxy(proxyUrl)
                                MarketService(s.copy(proxyEnabled = true, proxyUrl = proxyUrl), Maze.cache).testConnection()
                                t("set.proxy_ok")
                            } catch (e: Exception) {
                                t("set.proxy_fail", "err" to (e.message ?: e.javaClass.simpleName))
                            }
                        }
                    }
                    GhostButton(t("btn.save"), Modifier.weight(1f)) {
                        if (runCatching { Http.parseProxy(proxyUrl) }.isSuccess) {
                            Maze.updateSettings { it.copy(proxyUrl = proxyUrl) }
                            proxyState = t("set.saved")
                        } else proxyState = t("set.invalid", "err" to proxyUrl)
                    }
                }
                if (proxyState.isNotEmpty()) Text(proxyState, style = Type.small, color = p.textMid, modifier = Modifier.padding(top = 8.dp))
            }
        }

        // ── data ──────────────────────────────────────────────────────────
        SectionTitle(t("set.data"))
        Glass(Modifier.fillMaxWidth(), padding = 12.dp) {
            ValueRow(t("set.clear_cache"), "") {
                Maze.cache.clear()
                Toast.makeText(ctx, t("set.cache_cleared"), Toast.LENGTH_SHORT).show()
            }
            ValueRow(t("set.rerun_wizard"), "") { askRerun = true }
            ValueRow(t("nav.about"), "") { nav.go(Route.About) }
        }
        Gap(16)
        Text(t("m.set.stored"), style = Type.small, color = p.muted)
    }

    when (picker) {
        "lang" -> Picker(t("set.language"), LANG_NAMES.toList(), s.language, { picker = null }) { v -> Maze.updateSettings { it.copy(language = v) } }
        "fiat" -> Picker(t("set.fiat"), FIATS.map { it to it }, s.fiat, { picker = null }) { v -> Maze.updateSettings { it.copy(fiat = v) } }
        "refresh" -> Picker(t("set.refresh"), listOf(30, 60, 120, 300).map { it to seconds(it) }, s.refreshSeconds, { picker = null }) { v ->
            Maze.updateSettings { it.copy(refreshSeconds = v) }
        }
        "bg" -> Picker(t("m.set.background"), listOf(15, 30, 60, 120, 0).map { it to minutes(it) }, s.backgroundMinutes, { picker = null }) { v ->
            Maze.updateSettings { it.copy(backgroundMinutes = v) }
        }
    }
    if (askRerun) {
        Confirm(t("set.rerun_wizard"), t("m.set.rerun_q"), onDismiss = { askRerun = false }) {
            Maze.updateSettings { it.copy(onboarded = false) }
        }
    }
}


/**
 * When the background refresh last ran, and what is holding it back if it
 * does not: battery optimisation or a restricted standby bucket.
 */
@Composable
private fun BackgroundStatus() {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val now = com.mazecrypto.app.ui.rememberNow(30_000)
    val (lastRun, lastOk, ok) = remember(now) { com.mazecrypto.app.work.BackgroundLog.status(ctx) }
    val unrestricted = remember(now) { com.mazecrypto.app.work.BackgroundLog.unrestricted(ctx) }
    val bucket = remember(now) { com.mazecrypto.app.work.BackgroundLog.bucket(ctx) }
    val limit = Maze.settings.value.backgroundMinutes * 60_000L
    val late = lastOk == 0L || now - lastOk > maxOf(limit * 3, 90 * 60_000L)
    val text = when {
        lastRun == 0L -> t("m.set.bg_never")
        ok -> t("m.set.bg_last", "age" to com.mazecrypto.app.core.Fmt.age((now - lastRun) / 1000.0))
        else -> t("m.set.bg_last_failed", "age" to com.mazecrypto.app.core.Fmt.age((now - lastRun) / 1000.0))
    }
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(text, style = Type.small, color = if (late) p.warn else p.muted)
        // 40 = rare, 45 = restricted: periodic work then runs a few times a day at most.
        val held = !unrestricted && (late || (bucket != null && bucket >= 40))
        if (held) {
            Text(t("m.set.bg_held"), style = Type.small, color = p.textMid, modifier = Modifier.padding(top = 4.dp))
            Gap(6)
            GhostButton(t("m.set.bg_battery"), Modifier.fillMaxWidth()) {
                runCatching {
                    ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:" + ctx.packageName)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        }
    }
}
