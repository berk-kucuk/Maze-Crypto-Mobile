package com.mazecrypto.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mazecrypto.app.R
import com.mazecrypto.app.core.Coin
import com.mazecrypto.app.core.Condition
import com.mazecrypto.app.core.I18n
import com.mazecrypto.app.core.OTHER
import com.mazecrypto.app.core.Rule
import com.mazecrypto.app.core.t
import com.mazecrypto.app.data.EXCHANGE_INFO
import com.mazecrypto.app.data.Maze
import com.mazecrypto.app.ui.components.AppMark
import com.mazecrypto.app.ui.components.Chip
import com.mazecrypto.app.ui.components.Disclaimer
import com.mazecrypto.app.ui.components.GhostButton
import com.mazecrypto.app.ui.components.Glass
import com.mazecrypto.app.ui.components.Ic
import com.mazecrypto.app.ui.components.PrimaryButton
import com.mazecrypto.app.ui.components.Progress
import com.mazecrypto.app.ui.components.SectionTitle
import com.mazecrypto.app.ui.components.Segmented
import com.mazecrypto.app.ui.components.SwitchRow
import com.mazecrypto.app.ui.theme.LocalPalette
import com.mazecrypto.app.ui.theme.Type

private val BTC = Coin("bitcoin", "BTC", "Bitcoin")
private val ETH = Coin("ethereum", "ETH", "Ethereum")
private val SOL = Coin("solana", "SOL", "Solana")
private val USDT = Coin("tether", "USDT", "Tether")
private val PRESETS = mapOf(
    "majors" to listOf(BTC, ETH, SOL, Coin("binancecoin", "BNB", "BNB"), Coin("ripple", "XRP", "XRP"),
        Coin("cardano", "ADA", "Cardano"), Coin("dogecoin", "DOGE", "Dogecoin"), Coin("chainlink", "LINK", "Chainlink"),
        Coin("avalanche-2", "AVAX", "Avalanche"), USDT),
    "minimal" to listOf(BTC, ETH, USDT),
    "empty" to emptyList(),
)
private val DEFAULT_EXCHANGES = mapOf("TRY" to listOf("btcturk", "binance"), "EUR" to listOf("kraken", "coinbase"),
    "USD" to listOf("coinbase", "kraken"), "GBP" to listOf("coinbase", "kraken"))

/** First start: language and notice, currency and sources, watchlist and favorites, optional templates. */
@Composable
fun OnboardingScreen() {
    val p = LocalPalette.current
    val start = remember { Maze.settings.value }
    var step by remember { mutableIntStateOf(0) }
    var lang by remember { mutableStateOf(start.language) }
    var accepted by remember { mutableStateOf(false) }
    var fiat by remember { mutableStateOf(start.fiat.takeIf { start.watchlist.isNotEmpty() } ?: guessFiat()) }
    val exchanges = remember { mutableStateListOf<String>().apply { addAll(DEFAULT_EXCHANGES[fiat] ?: listOf("binance")) } }
    var preset by remember { mutableStateOf(if (start.watchlist.isEmpty()) "majors" else "") }
    val picked = remember { mutableStateListOf<Coin>().apply { addAll(start.watchlist.ifEmpty { PRESETS.getValue("majors") }) } }
    val stars = remember { mutableStateListOf<String>().apply { addAll(start.favorites.ifEmpty { listOf("bitcoin", "ethereum", "solana", "binancecoin") }) } }
    var target by remember { mutableStateOf("none") }
    var rebalanceRule by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf(false) }
    val steps = 4

    fun finish() {
        Maze.updateSettings {
            it.copy(language = lang, fiat = fiat, exchanges = exchanges.toList(), watchlist = picked.toList(),
                favorites = stars.filter { id -> picked.any { c -> c.id == id } }, onboarded = true)
        }
        if (target != "none" || rebalanceRule) {
            Maze.updateProfile { pr ->
                var out = pr
                if (target != "none" && pr.targets.isEmpty()) {
                    val tg = if (target == "btc_heavy") mapOf(BTC.id to 60.0, ETH.id to 20.0, USDT.id to 10.0, OTHER to 10.0)
                    else mapOf(BTC.id to 40.0, ETH.id to 25.0, SOL.id to 10.0, USDT.id to 10.0, OTHER to 15.0)
                    out = out.copy(targets = tg, targetSymbols = listOf(BTC, ETH, SOL, USDT).filter { it.id in tg }.associate { c -> c.id to c.symbol })
                }
                if (rebalanceRule) {
                    out = out.copy(rules = out.rules + Rule(t("tpl.drift.name", "pct" to 5), Condition("drift", pct = 5.0), t("tpl.drift.msg")))
                }
                out
            }
        }
        Maze.refreshAsync()
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Gap(24)
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppMark(44)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Maze Crypto", style = Type.strong.copy(fontSize = 18.sp), color = p.text)
                    Text(t("app.tagline"), style = Type.small, color = p.muted)
                }
            }
            Gap(18)
            Progress((step + 1f) / steps)
            Gap(18)
            when (step) {
                0 -> {
                    Text(t("wiz.welcome"), style = Type.pageTitle, color = p.text)
                    Text(t("m.wiz.welcome_sub"), style = Type.body, color = p.textMid, modifier = Modifier.padding(top = 6.dp))
                    SectionTitle(t("set.language"))
                    Segmented(LANG_NAMES.toList(), lang) {
                        lang = it
                        I18n.setLanguage(it)
                    }
                    Gap(18)
                    Disclaimer(t("about.disclaimer"))
                    Row(Modifier.fillMaxWidth().clickable { accepted = !accepted }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(accepted, { accepted = it }, colors = CheckboxDefaults.colors(checkedColor = p.accent, checkmarkColor = p.onAccent, uncheckedColor = p.border2))
                        Text(t("wiz.understand"), style = Type.body, color = p.text)
                    }
                }
                1 -> {
                    Text(t("wiz.currency"), style = Type.pageTitle, color = p.text)
                    Text(t("wiz.currency_sub"), style = Type.body, color = p.textMid, modifier = Modifier.padding(top = 6.dp))
                    Gap(12)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FIATS.forEach { f ->
                            Chip(f, fiat == f) {
                                fiat = f
                                DEFAULT_EXCHANGES[f]?.let { exchanges.clear(); exchanges.addAll(it) }
                            }
                        }
                    }
                    Text(t("wiz.currency_note"), style = Type.small, color = p.muted, modifier = Modifier.padding(top = 8.dp))
                    SectionTitle(t("wiz.sources"))
                    Glass(Modifier.fillMaxWidth(), padding = 12.dp) {
                        EXCHANGE_INFO.forEach { ex ->
                            SwitchRow(ex.name, ex.id in exchanges, ex.country) { v -> if (v) exchanges.add(ex.id) else exchanges.remove(ex.id) }
                        }
                    }
                    Text(t("set.exchanges_help"), style = Type.small, color = p.muted, modifier = Modifier.padding(top = 8.dp))
                }
                2 -> {
                    Text(t("wiz.watch"), style = Type.pageTitle, color = p.text)
                    Text(t("m.wiz.watch_sub"), style = Type.body, color = p.textMid, modifier = Modifier.padding(top = 6.dp))
                    Gap(12)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PRESETS.keys.forEach { k ->
                            Chip(t("wiz.preset.$k"), preset == k) {
                                preset = k
                                picked.clear(); picked.addAll(PRESETS.getValue(k))
                            }
                        }
                    }
                    Gap(10)
                    Glass(Modifier.fillMaxWidth(), padding = 8.dp) {
                        picked.forEach { c ->
                            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                val on = c.id in stars
                                Box(Modifier.size(40.dp).clickable { if (on) stars.remove(c.id) else stars.add(c.id) }, contentAlignment = Alignment.Center) {
                                    Ic(if (on) R.drawable.ic_star_fill else R.drawable.ic_star, tint = if (on) p.accent else p.dim, size = 18.dp)
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(c.symbol, style = Type.strong, color = p.text)
                                    Text(c.name, style = Type.small, color = p.muted)
                                }
                                Box(Modifier.size(40.dp).clickable { picked.remove(c); stars.remove(c.id) }, contentAlignment = Alignment.Center) {
                                    Ic(R.drawable.ic_x, tint = p.muted, size = 16.dp)
                                }
                            }
                        }
                        GhostButton(t("watch.add"), Modifier.fillMaxWidth().padding(6.dp), icon = R.drawable.ic_plus) { search = true }
                    }
                    Text(t("m.wiz.star_note"), style = Type.small, color = p.muted, modifier = Modifier.padding(top = 8.dp))
                }
                else -> {
                    Text(t("wiz.templates"), style = Type.pageTitle, color = p.text)
                    Gap(12)
                    Glass(Modifier.fillMaxWidth(), tint = p.accent) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Ic(R.drawable.ic_squares_four, tint = p.accent, size = 20.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(t("m.home.widget_t"), style = Type.strong, color = p.text)
                        }
                        Text(t("m.wiz.widgets_b"), style = Type.bodySmall, color = p.textMid, modifier = Modifier.padding(top = 6.dp))
                    }
                    SectionTitle(t("targets.title"))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip(t("wiz.tpl_none"), target == "none") { target = "none" }
                        Chip(t("targets.tpl.btc_heavy"), target == "btc_heavy") { target = "btc_heavy" }
                        Chip(t("targets.tpl.balanced"), target == "balanced") { target = "balanced" }
                    }
                    Text(t("targets.help"), style = Type.small, color = p.muted, modifier = Modifier.padding(top = 8.dp))
                    Gap(8)
                    SwitchRow(t("wiz.rule_drift"), rebalanceRule) { rebalanceRule = it }
                }
            }
            error?.let { Text(it, style = Type.body, color = p.down, modifier = Modifier.padding(top = 10.dp)) }
            Gap(20)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (step > 0) GhostButton(t("btn.back"), Modifier.weight(1f)) { error = null; step-- }
            PrimaryButton(if (step == steps - 1) t("btn.finish") else t("btn.next"), Modifier.weight(1f)) {
                error = when {
                    step == 0 && !accepted -> t("wiz.need_accept")
                    step == 1 && exchanges.isEmpty() -> t("wiz.need_exchange")
                    else -> null
                }
                if (error == null) {
                    if (step == steps - 1) finish() else step++
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
    if (search) {
        CoinSearchSheet(onDismiss = { search = false }, inList = picked.map { it.id }.toSet()) { c ->
            if (picked.none { it.id == c.id }) picked.add(c)
        }
    }
}

/** A sensible first currency from the phone's region; the user picks anyway. */
private fun guessFiat(): String = when (java.util.Locale.getDefault().country) {
    "TR" -> "TRY"
    "GB" -> "GBP"
    "DE", "FR", "IT", "ES", "NL", "AT", "BE", "FI", "IE", "PT", "GR", "SK", "SI", "EE", "LV", "LT", "LU", "MT", "CY", "HR" -> "EUR"
    "CH" -> "CHF"
    "JP" -> "JPY"
    "CA" -> "CAD"
    "AU" -> "AUD"
    "PL" -> "PLN"
    "BR" -> "BRL"
    "IN" -> "INR"
    "KR" -> "KRW"
    else -> "USD"
}
