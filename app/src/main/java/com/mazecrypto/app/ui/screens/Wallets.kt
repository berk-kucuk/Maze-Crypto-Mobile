package com.mazecrypto.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mazecrypto.app.R
import com.mazecrypto.app.core.Address
import com.mazecrypto.app.core.Coin
import com.mazecrypto.app.core.Fmt
import com.mazecrypto.app.core.Hd
import com.mazecrypto.app.core.Transfer
import com.mazecrypto.app.core.Wallet
import com.mazecrypto.app.core.isSyntheticCoin
import com.mazecrypto.app.core.t
import com.mazecrypto.app.data.Chains
import com.mazecrypto.app.data.Maze
import com.mazecrypto.app.data.MoneroSync
import com.mazecrypto.app.monero.Ed
import com.mazecrypto.app.monero.ViewWallet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.mazecrypto.app.data.WalletStore
import com.mazecrypto.app.ui.Nav
import com.mazecrypto.app.ui.Route
import com.mazecrypto.app.ui.components.Badge
import com.mazecrypto.app.ui.components.Chip
import com.mazecrypto.app.ui.components.Confirm
import com.mazecrypto.app.ui.components.Divider
import com.mazecrypto.app.ui.components.EmptyState
import com.mazecrypto.app.ui.components.Field
import com.mazecrypto.app.ui.components.GhostButton
import com.mazecrypto.app.ui.components.Glass
import com.mazecrypto.app.ui.components.Ic
import com.mazecrypto.app.ui.components.IconBtn
import com.mazecrypto.app.ui.components.KeyValue
import com.mazecrypto.app.ui.components.LinkButton
import com.mazecrypto.app.ui.components.PrimaryButton
import com.mazecrypto.app.ui.components.SectionTitle
import com.mazecrypto.app.ui.components.Segmented
import com.mazecrypto.app.ui.components.SwitchRow
import com.mazecrypto.app.ui.components.ValueRow
import com.mazecrypto.app.ui.rememberNow
import com.mazecrypto.app.ui.theme.LocalPalette
import com.mazecrypto.app.ui.theme.Type

// ── shared bits ─────────────────────────────────────────────────────────────

private val Mono = Type.numSmall.copy(fontFamily = FontFamily.Monospace)

fun walletTitle(w: Wallet): String = w.label.ifEmpty {
    if (w.chain == "manual") w.symbol else Chains.of(w.chain)?.name ?: w.chain
}

/** Value of everything at the address, in the portfolio currency; null until priced. */
private fun walletValue(w: Wallet, st: Maze.WalletStatus?, prices: Map<String, Double>): Double? {
    if (w.manual) return w.amount?.let { a -> prices[w.coinId]?.let { a * it } }
    val assets = st?.balance?.assets ?: return null
    if (assets.isEmpty()) return 0.0
    var sum = 0.0
    var any = false
    for (a in assets) {
        val p = prices[a.coinId] ?: continue
        sum += a.amount * p
        any = true
    }
    return if (any || assets.all { it.amount == 0.0 }) sum else null
}

fun Context.openLink(url: String) {
    // Explorer links are built from data that came over the network: only plain https leaves the app.
    if (!url.startsWith("https://") || url.any { it.isWhitespace() || it.isISOControl() }) return
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { Toast.makeText(this, t("m.wal.no_browser"), Toast.LENGTH_SHORT).show() }
}

fun Context.copyText(label: String, text: String) {
    val cm = getSystemService(ClipboardManager::class.java) ?: return
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
    if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, t("m.wal.copied"), Toast.LENGTH_SHORT).show()
}

private fun Context.pasteText(): String =
    getSystemService(ClipboardManager::class.java)?.primaryClip?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)?.coerceToText(this)?.toString() ?: ""

/** A secret was pasted: take it off the clipboard too, so it cannot be pasted anywhere else by mistake. */
private fun Context.clearClipboardIf(secret: String) {
    val cm = getSystemService(ClipboardManager::class.java) ?: return
    if (pasteText().trim() != secret.trim()) return
    if (Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip()
    else cm.setPrimaryClip(ClipData.newPlainText("", "").apply {
        description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    })
}

@Composable
private fun ChainDot(chain: String) {
    val p = LocalPalette.current
    Badge(Chains.of(chain)?.let { if (it.id == "manual") t("m.wal.manual_short") else it.name } ?: chain, p.textMid)
}

/** Chain, plus what kind of wallet: an HD account, a Ledger, a view-only Monero wallet. */
@Composable
private fun WalletBadges(w: Wallet) {
    val p = LocalPalette.current
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        ChainDot(w.chain)
        if (w.device == "ledger") Badge("Ledger", p.accent)
        if (w.account) Badge(scriptName(w.script), p.textMid)
        if (w.moneroView) Badge(t("m.xmr.view_badge"), p.accent)
    }
}

/** What to show for a wallet's address in a row: accounts by their type, not a 111-character key. */
fun walletRef(w: Wallet): String = when {
    w.account -> t("m.wal.account_short") + (w.path.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: "")
    else -> Address.short(w.address)
}

// ── Portfolio → Wallets tab ────────────────────────────────────────────────

@Composable
fun WalletsTab(nav: Nav, fiat: String) {
    val p = LocalPalette.current
    val wallets by Maze.wallets.collectAsStateWithLifecycle()
    val data by Maze.walletData.collectAsStateWithLifecycle()
    val snap by Maze.snapshot.collectAsStateWithLifecycle()
    val prices = remember(snap, data) { Maze.prices().first }

    if (wallets.isEmpty()) {
        EmptyState(R.drawable.ic_wallet, t("m.wal.empty_t"), t("m.wal.empty_b")) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                PrimaryButton(t("m.wal.add"), icon = R.drawable.ic_plus) { nav.go(Route.WalletEdit()) }
                Gap(8)
                GhostButton(t("m.ledger.connect"), icon = R.drawable.ic_usb) { nav.go(Route.Ledger) }
            }
        }
        Gap(12)
        SecurityNote()
        return
    }

    if (wallets.any { it.moneroView && data[it.id]?.torMissing == true }) {
        TorRequired(nav)
        Gap(12)
    }
    val counted = wallets.filter { it.inPortfolio }
    val total = counted.sumOf { walletValue(it, data[it.id], prices) ?: 0.0 }
    Glass(Modifier.fillMaxWidth()) {
        Text(t("m.wal.total"), style = Type.cardTitle, color = p.textMid)
        Text(if (Maze.settings.value.hideAmounts) MASK else Fmt.money(total, fiat, if (total >= 1000) 0 else 2), style = Type.big, color = p.text, maxLines = 1)
        val failing = wallets.count { data[it.id]?.error != null }
        Text(
            listOfNotNull(
                t("m.wal.n_addresses", "n" to wallets.size),
                if (counted.size < wallets.size) t("m.wal.n_watch_only", "n" to wallets.size - counted.size) else null,
                if (failing > 0) t("m.wal.n_failing", "n" to failing) else null,
            ).joinToString(" · "),
            style = Type.small, color = if (failing > 0) p.warn else p.muted, modifier = Modifier.padding(top = 4.dp),
        )
    }
    Gap(12)
    Glass(Modifier.fillMaxWidth(), padding = 0.dp) {
        wallets.forEachIndexed { i, w ->
            if (i > 0) Divider(Modifier.padding(horizontal = 14.dp))
            WalletRow(w, data[w.id], prices, fiat) { nav.go(Route.WalletDetail(w.id)) }
        }
    }
    Gap(10)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GhostButton(t("m.wal.add"), Modifier.weight(1f), icon = R.drawable.ic_plus) { nav.go(Route.WalletEdit()) }
        GhostButton("Ledger", Modifier.weight(1f), icon = R.drawable.ic_usb) { nav.go(Route.Ledger) }
    }

    SectionTitle(t("m.wal.activity"))
    ActivityBlock(nav, fiat, wallets.map { it.id }, limit = 20)
    Gap(12)
    SecurityNote()
}

@Composable
private fun WalletRow(w: Wallet, st: Maze.WalletStatus?, prices: Map<String, Double>, fiat: String, onClick: () -> Unit) {
    val p = LocalPalette.current
    val now = rememberNow(30_000)
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(walletTitle(w), style = Type.strong, color = p.text, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                if (walletTitle(w) != Chains.of(w.chain)?.name) { Spacer(Modifier.width(6.dp)); ChainDot(w.chain) }
                if (w.device == "ledger") { Spacer(Modifier.width(4.dp)); Badge("Ledger", p.accent) }
            }
            val sub = when {
                w.manual -> listOfNotNull(Fmt.amount(w.amount) + " " + w.symbol, w.address.takeIf { it.isNotEmpty() }?.let { Address.short(it) })
                    .joinToString(" · ")
                else -> {
                    val assets = st?.balance?.assets?.filter { it.amount > 0 } ?: emptyList()
                    val main = assets.firstOrNull()?.let { Fmt.amount(it.amount) + " " + it.symbol }
                    val more = if (assets.size > 1) t("m.wal.more_assets", "n" to assets.size - 1) else null
                    listOfNotNull(walletRef(w), main, more).joinToString(" · ")
                }
            }
            Text(sub, style = Type.small, color = p.muted, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(Fmt.money(walletValue(w, st, prices), fiat), style = Type.num, color = if (w.inPortfolio) p.text else p.textMid, maxLines = 1)
            val xmr = MoneroSync.progress.collectAsStateWithLifecycle().value[w.id]
            val state = when {
                st?.torMissing == true -> t("m.xmr.tor_needed") to p.warn
                xmr != null && xmr.phase == "back" -> t("m.xmr.history_to", "date" to Fmt.date(xmr.ts)) to p.accent
                xmr != null -> t("m.xmr.scanning", "pct" to (xmr.fraction * 100).toInt().toString()) to p.accent
                st?.loading == true -> t("status.updating") to p.accent
                st?.error != null && st.balance == null -> t("m.wal.failed") to p.down
                st?.error != null -> t("m.wal.stale", "age" to Fmt.age((now - st.balance!!.ts) / 1000.0)) to p.warn
                !w.inPortfolio -> t("m.wal.watch_only") to p.muted
                w.manual -> t("m.wal.manual_short") to p.muted
                st?.balance != null -> Fmt.age((now - st.balance.ts) / 1000.0) to p.muted
                else -> t("status.updating") to p.muted
            }
            Text(state.first, style = Type.small, color = state.second, maxLines = 1)
        }
    }
}

@Composable
private fun SecurityNote() {
    val p = LocalPalette.current
    Glass(Modifier.fillMaxWidth(), tint = p.up) {
        Row {
            Ic(R.drawable.ic_shield_check, tint = p.up, size = 18.dp)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(t("m.wal.safe_t"), style = Type.strong, color = p.text)
                Text(t("m.wal.safe_b"), style = Type.bodySmall, color = p.textMid, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

// ── activity ──────────────────────────────────────────────────────────────

/** Loads history for [ids] when shown and whenever the balances change. */
@Composable
private fun ActivityBlock(nav: Nav, fiat: String, ids: List<String>, limit: Int) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val data by Maze.walletData.collectAsStateWithLifecycle()
    var list by remember { mutableStateOf<List<Transfer>?>(null) }
    var errors by remember { mutableStateOf<List<String>>(emptyList()) }
    val stamp = ids.joinToString(",") + "|" + ids.mapNotNull { data[it]?.balance?.ts }.joinToString(",")
    LaunchedEffect(stamp) {
        val (l, e) = Maze.walletActivity(ids)
        list = l
        errors = e
    }
    val wallets by Maze.wallets.collectAsStateWithLifecycle()
    val readable = wallets.filter { it.id in ids && !it.manual && Chains.of(it.chain)?.activity == true }
    val l = list
    when {
        readable.isEmpty() -> Glass(Modifier.fillMaxWidth()) {
            Text(t("m.wal.no_history_chain"), style = Type.bodySmall, color = p.textMid)
        }
        l == null -> Glass(Modifier.fillMaxWidth()) { Text(t("status.updating"), style = Type.body, color = p.muted) }
        l.isEmpty() -> Glass(Modifier.fillMaxWidth()) {
            Text(if (errors.isNotEmpty()) t("m.wal.history_failed") else t("m.wal.no_history"), style = Type.body, color = p.textMid)
        }
        else -> {
            val (pricesFiat, _) = remember(l) { Maze.prices() }
            Glass(Modifier.fillMaxWidth(), padding = 0.dp) {
                l.take(limit).forEachIndexed { i, tr ->
                    if (i > 0) Divider(Modifier.padding(horizontal = 14.dp))
                    TransferRow(tr, fiat, pricesFiat, wallets) { Chains.txUrl(tr.chain, tr.txid)?.let { ctx.openLink(it) } }
                }
            }
            Text(t("m.wal.history_note"), style = Type.small, color = p.muted, modifier = Modifier.padding(top = 6.dp, start = 2.dp))
            if (l.take(limit).any { isDust(it) }) {
                Text(t("m.wal.dust_note"), style = Type.small, color = p.warn, modifier = Modifier.padding(top = 4.dp, start = 2.dp))
            }
        }
    }
    if (errors.isNotEmpty() && !l.isNullOrEmpty()) {
        Text(t("m.wal.history_partial"), style = Type.small, color = p.warn, modifier = Modifier.padding(top = 4.dp, start = 2.dp))
    }
}

@Composable
private fun TransferRow(tr: Transfer, fiat: String, prices: Map<String, Double>, wallets: List<Wallet>, onClick: () -> Unit) {
    val p = LocalPalette.current
    val dir = tr.direction
    val (icon, color, title) = when (dir) {
        "in" -> Triple(R.drawable.ic_arrow_down_left, p.up, t("m.wal.received"))
        "out" -> Triple(R.drawable.ic_arrow_up_right, p.down, t("m.wal.sent"))
        "swap" -> Triple(R.drawable.ic_arrows_down_up, p.accent, t("m.wal.swap"))
        else -> Triple(R.drawable.ic_arrows_down_up, p.textMid, t("m.wal.self"))
    }
    val names = tr.walletIds.mapNotNull { id -> wallets.firstOrNull { it.id == id }?.let { walletTitle(it) } }
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Ic(icon, tint = color, size = 16.dp)
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = Type.strong, color = p.text, maxLines = 1)
                if (tr.status != "ok") {
                    Spacer(Modifier.width(6.dp))
                    Badge(t(if (tr.status == "pending") "m.wal.pending" else "m.wal.failed_tx"), if (tr.status == "pending") p.warn else p.down)
                }
                if (isDust(tr)) {
                    Spacer(Modifier.width(6.dp))
                    Badge(t("m.wal.dust"), p.warn)
                }
            }
            val cp = tr.counterparty.takeIf { it.isNotEmpty() && dir != "self" }?.let {
                t(if (dir == "in") "m.wal.from" else "m.wal.to", "who" to Address.short(it))
            }
            val time = if (tr.ts > 0) Fmt.date(tr.ts) + " " + Fmt.clock(tr.ts * 1000) else t("m.wal.unconfirmed")
            Text(listOfNotNull(time, cp, names.joinToString(", ").ifEmpty { null }).joinToString(" · "),
                style = Type.small, color = p.muted, maxLines = 2, modifier = Modifier.padding(top = 2.dp))
            if (tr.fee != null && tr.fee > 0 && !tr.feeIncluded) {
                Text(t("m.wal.fee", "fee" to Fmt.amount(tr.fee) + " " + tr.feeSymbol), style = Type.small, color = p.muted)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            if (tr.legs.isEmpty()) {
                Text(tr.fee?.let { "−" + Fmt.amount(it) + " " + tr.feeSymbol } ?: Fmt.DASH, style = Type.numSmall, color = p.textMid, maxLines = 1)
            }
            tr.legs.take(3).forEach { l ->
                Text((if (l.amount > 0) "+" else "−") + Fmt.amount(kotlin.math.abs(l.amount)) + " " + l.symbol,
                    style = Type.num, color = if (l.amount > 0) p.up else p.text, maxLines = 1)
            }
            val value = tr.legs.sumOf { l -> prices[l.coinId]?.let { l.amount * it } ?: 0.0 }
            if (tr.legs.any { prices[it.coinId] != null } && kotlin.math.abs(value) >= 0.005) {
                Text(Fmt.signedMoney(value, fiat, if (kotlin.math.abs(value) >= 100) 0 else 2), style = Type.small, color = p.muted, maxLines = 1)
            }
        }
    }
}

/**
 * A tiny unsolicited receipt on a UTXO chain: the classic dust attack. Spending
 * it together with other coins links the addresses, so it is flagged.
 */
private fun isDust(tr: Transfer): Boolean =
    (tr.chain == "bitcoin" || tr.chain == "litecoin") && tr.direction == "in" &&
        tr.legs.size == 1 && tr.legs[0].amount in 0.0..0.00001

// ── one wallet ────────────────────────────────────────────────────────────

@Composable
fun WalletDetailScreen(nav: Nav, id: String) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val settings by Maze.settings.collectAsStateWithLifecycle()
    val wallets by Maze.wallets.collectAsStateWithLifecycle()
    val data by Maze.walletData.collectAsStateWithLifecycle()
    val snap by Maze.snapshot.collectAsStateWithLifecycle()
    val w = wallets.firstOrNull { it.id == id }
    var askDelete by remember { mutableStateOf(false) }
    if (w == null) {
        SubScreen(nav, t("m.wal.title")) { Gap(12); Text(t("m.wal.gone"), style = Type.body, color = p.textMid) }
        return
    }
    val st = data[id]
    val fiat = settings.fiat
    val prices = remember(snap, data) { Maze.prices().first }
    val info = Chains.of(w.chain)
    val now = rememberNow(10_000)

    SubScreen(nav, walletTitle(w), actions = {
        if (!w.manual) IconBtn(R.drawable.ic_arrows_clockwise, enabled = st?.loading != true) { Maze.refreshWalletAsync(id) }
        IconBtn(R.drawable.ic_pencil_simple) { nav.go(Route.WalletEdit(id = id)) }
        IconBtn(R.drawable.ic_trash, tint = p.down) { askDelete = true }
    }) {
        Gap(6)
        Glass(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                WalletBadges(w)
                if (!w.inPortfolio) { Spacer(Modifier.width(6.dp)); Badge(t("m.wal.watch_only"), p.muted) }
                Spacer(Modifier.weight(1f))
                if (w.manual) Text(t("m.wal.manual_short"), style = Type.small, color = p.muted)
                else Text(when {
                    st?.loading == true -> t("status.updating")
                    st?.balance != null -> t("m.wal.updated", "age" to Fmt.age((now - st.balance.ts) / 1000.0))
                    else -> ""
                }, style = Type.small, color = if (st?.error != null) p.warn else p.muted)
            }
            Gap(8)
            Text(t("home.value"), style = Type.cardTitle, color = p.textMid)
            Text(Fmt.money(walletValue(w, st, prices), fiat), style = Type.big, color = p.text, maxLines = 1)
            if (w.moneroView && st?.torMissing == true) { Gap(10); TorRequired(nav) }
            if (w.moneroView) MoneroStatus(w)
            if (w.account) AccountInfo(w)
            if (w.address.isNotEmpty()) {
                Gap(10)
                Text(if (w.account) t("m.wal.account_key") else t("m.wal.address"), style = Type.small, color = p.muted)
                SelectionContainer { Text(w.address, style = Mono, color = p.text, modifier = Modifier.padding(top = 2.dp)) }
                Gap(8)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton(t("m.wal.copy"), Modifier.weight(1f), icon = R.drawable.ic_copy) { ctx.copyText(t("m.wal.address"), w.address) }
                    Chains.explorerUrl(w.chain, w.address)?.takeIf { !w.account }?.let { url ->
                        GhostButton(t("m.wal.explorer"), Modifier.weight(1f), icon = R.drawable.ic_arrow_square_out) { ctx.openLink(url) }
                    }
                }
            }
            st?.error?.let { e ->
                Text(if (st.balance != null) t("m.wal.err_stale", "err" to e) else t("m.wal.err_fetch", "err" to e),
                    style = Type.small, color = p.warn, modifier = Modifier.padding(top = 10.dp))
            }
        }

        SectionTitle(t("m.wal.balances"))
        Glass(Modifier.fillMaxWidth(), padding = 0.dp) {
            val rows = if (w.manual) listOf(Triple(w.coinId, w.symbol, w.amount ?: 0.0))
                else st?.balance?.assets?.map { Triple(it.coinId, it.symbol, it.amount) } ?: emptyList()
            if (rows.isEmpty()) Text(if (st?.error != null) t("m.wal.failed") else t("status.updating"),
                style = Type.body, color = p.muted, modifier = Modifier.padding(14.dp))
            rows.forEachIndexed { i, (cid, sym, amount) ->
                if (i > 0) Divider(Modifier.padding(horizontal = 14.dp))
                val asset = st?.balance?.assets?.firstOrNull { it.coinId == cid }
                Row(Modifier.fillMaxWidth().clickable(enabled = !isSyntheticCoin(cid)) { nav.go(Route.Chart(cid)) }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(sym, style = Type.strong, color = p.text)
                        val sub = listOfNotNull(
                            asset?.name?.takeIf { it.isNotEmpty() && it != sym },
                            asset?.contract?.takeIf { it.isNotEmpty() }?.let { t("m.wal.token", "c" to Address.short(it)) },
                            asset?.pending?.takeIf { it != 0.0 }?.let { t("m.wal.pending_amt", "a" to (if (it > 0) "+" else "") + Fmt.amount(it)) },
                        ).joinToString(" · ")
                        if (sub.isNotEmpty()) Text(sub, style = Type.small, color = p.muted, maxLines = 1)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(Fmt.amount(amount), style = Type.num, color = p.text, maxLines = 1)
                        Text(Fmt.money(prices[cid]?.let { amount * it }, fiat), style = Type.numSmall, color = p.muted, maxLines = 1)
                    }
                }
            }
        }
        st?.balance?.ignored?.takeIf { it > 0 }?.let {
            Text(t("m.wal.ignored", "n" to it), style = Type.small, color = p.muted, modifier = Modifier.padding(top = 6.dp, start = 2.dp))
        }
        if (st?.balance?.assets?.any { isSyntheticCoin(it.coinId) } == true) {
            Text(t("m.wal.indexer_price"), style = Type.small, color = p.muted, modifier = Modifier.padding(top = 4.dp, start = 2.dp))
        }

        SectionTitle(t("m.wal.activity"))
        when {
            w.chain == "monero" && w.manual -> Glass(Modifier.fillMaxWidth()) { Text(t("m.wal.xmr_note"), style = Type.bodySmall, color = p.textMid) }
            w.manual -> Glass(Modifier.fillMaxWidth()) { Text(t("m.wal.manual_note"), style = Type.bodySmall, color = p.textMid) }
            info?.activity != true -> Glass(Modifier.fillMaxWidth()) {
                Text(t("m.wal.no_history_chain_one", "chain" to (info?.name ?: w.chain)), style = Type.bodySmall, color = p.textMid)
                Chains.explorerUrl(w.chain, w.address)?.let { url -> LinkButton(t("m.wal.explorer")) { ctx.openLink(url) } }
            }
            else -> ActivityBlock(nav, fiat, listOf(id), limit = 50)
        }
    }

    if (askDelete) {
        Confirm(t("m.wal.delete_q"), t("m.wal.delete_b"), confirm = t("btn.delete"), danger = true, onDismiss = { askDelete = false }) {
            Maze.deleteWallet(id)
            nav.back()
        }
    }
}

@Composable
private fun AccountInfo(w: Wallet) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val data by Maze.walletData.collectAsStateWithLifecycle()
    val info = remember(data[w.id]?.balance?.ts) { Maze.service().wallets.accountInfo(w) }
    Gap(10)
    if (w.path.isNotEmpty()) KeyValue(t("m.wal.path"), w.path)
    info?.let { (next, used) ->
        KeyValue(t("m.wal.used_addresses"), used.toString(), Modifier.padding(top = 4.dp))
        if (next.isNotEmpty()) {
            Gap(8)
            Text(t("m.wal.next_address"), style = Type.small, color = p.muted)
            SelectionContainer { Text(next, style = Mono, color = p.text, modifier = Modifier.padding(top = 2.dp)) }
            GhostButton(t("m.wal.copy"), Modifier.padding(top = 6.dp), icon = R.drawable.ic_copy) { ctx.copyText(t("m.wal.address"), next) }
            if (w.device == "ledger") Text(t("m.wal.verify_hint"), style = Type.small, color = p.muted, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun MoneroStatus(w: Wallet) {
    val p = LocalPalette.current
    val prog = MoneroSync.progress.collectAsStateWithLifecycle().value[w.id]
    val data by Maze.walletData.collectAsStateWithLifecycle()
    var tick by remember { mutableStateOf(0) }
    val st = remember(data[w.id]?.balance?.ts, prog?.height, tick) { MoneroSync.state(w.id) }
    Gap(10)
    when {
        prog != null && prog.phase == "back" ->
            Text(t("m.xmr.history_running", "date" to Fmt.date(prog.ts)), style = Type.small, color = p.accent)
        prog != null -> {
            Text(t("m.xmr.scanning_long", "h" to prog.height.toString(), "tip" to prog.tip.toString()), style = Type.small, color = p.accent)
            com.mazecrypto.app.ui.components.Progress(prog.fraction, Modifier.fillMaxWidth().padding(vertical = 6.dp))
        }
        st != null && st.next > 0 -> KeyValue(t("m.xmr.scanned_to"), st.next.toString())
    }
    prog?.let { Text(t("m.xmr.via", "node" to Address.short(it.node, 10, 6)), style = Type.small, color = p.muted, modifier = Modifier.padding(top = 2.dp)) }
    if (w.restoreHeight > 0) {
        KeyValue(t("m.xmr.restore_height"), w.restoreHeight.toString(), Modifier.padding(top = 4.dp))
    } else if (st != null && st.low > 0) {
        // No start date: history is read backwards from the day the wallet was added.
        val found = st.outputs.size
        Text(
            when {
                st.backDone && st.floor > 1 -> t("m.xmr.history_stopped", "date" to Fmt.date(st.lowTs), "n" to found)
                st.backDone -> t("m.xmr.history_done", "n" to found)
                else -> t("m.xmr.history_so_far", "date" to Fmt.date(st.lowTs), "n" to found)
            },
            style = Type.small, color = p.textMid, modifier = Modifier.padding(top = 6.dp),
        )
        Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!st.backDone) LinkButton(t("m.xmr.history_stop"), arrow = false) { MoneroSync.setHistory(w.id, resume = false); tick++ }
            else if (st.floor > 1) LinkButton(t("m.xmr.history_resume"), arrow = false) {
                MoneroSync.setHistory(w.id, resume = true); tick++; Maze.syncMoneroAsync(force = true)
            }
        }
        if (!st.backDone && !Maze.settings.value.moneroMobileData) {
            Text(t("m.xmr.history_wifi"), style = Type.small, color = p.muted)
        }
    }
    Text(t("m.xmr.view_b"), style = Type.small, color = p.muted, modifier = Modifier.padding(top = 6.dp))
}

// ── add / edit ────────────────────────────────────────────────────────────

/** "Native SegWit", "Taproot" … for an HD account's script. */
fun scriptName(script: String): String = t("m.wal.script_" + script.replace("-", "_"))

@Composable
fun WalletEditor(nav: Nav, id: String?, presetCoin: String?) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val existing = remember(id) { id?.let { i -> Maze.wallets.value.firstOrNull { it.id == i } } }
    val preset = remember(presetCoin) { presetCoin?.let { c -> Maze.trackedCoins().firstOrNull { it.id == c } } }
    val presetChains = remember(presetCoin) { presetCoin?.let { Chains.forCoin(it) } }
    val startMode = when {
        existing?.moneroView == true -> "address"
        existing?.manual == true -> "manual"
        presetCoin == "monero" -> "monero"
        presetChains?.all { it == "manual" } == true -> "manual"
        else -> "address"
    }

    var mode by remember { mutableStateOf(startMode) }
    var input by remember { mutableStateOf(existing?.address ?: "") }
    var label by remember { mutableStateOf(existing?.label ?: "") }
    var chosen by remember { mutableStateOf(existing?.let { setOf(it.chain) } ?: emptySet()) }
    var script by remember { mutableStateOf<String?>(null) }
    var coin by remember {
        mutableStateOf(existing?.takeIf { it.manual && it.coinId.isNotEmpty() }?.let { Coin(it.coinId, it.symbol, it.name.ifEmpty { it.symbol }) }
            ?: preset?.takeIf { startMode == "manual" })
    }
    var amount by remember { mutableStateOf(existing?.amount?.let { plain(it) } ?: "") }
    var inPortfolio by remember { mutableStateOf(existing?.inPortfolio ?: true) }
    var error by remember { mutableStateOf<String?>(null) }
    var secret by remember { mutableStateOf<String?>(null) }
    var pickCoin by remember { mutableStateOf(false) }
    // Monero view-only.
    var xmrAddr by remember { mutableStateOf("") }
    var viewKey by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var restore by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    val parsed = remember(input) { if (input.isBlank()) null else Address.parse(input) }
    // Which networks the address can be on, narrowed to the coin when the editor was opened from one.
    val rawChains = when (val r = parsed) {
        is Address.Result.Ok -> r.chains
        is Address.Result.Account -> r.chains
        else -> null
    }
    val options = rawChains?.let { cs ->
        val forCoin = presetChains?.let { pc -> cs.filter { it in pc } }
        if (forCoin.isNullOrEmpty()) cs else forCoin
    } ?: emptyList()
    val multi = options.size > 1 && options.all { it in Address.EVM_CHAINS }
    val account = parsed as? Address.Result.Account
    val accountChain = chosen.firstOrNull { it in options } ?: options.firstOrNull()
    val scriptOptions = when {
        account == null -> emptyList()
        account.script != null -> listOf(account.script)
        accountChain == "litecoin" -> listOf("pkh")
        else -> listOf("pkh", "tr")
    }
    val firstAddress = remember(account, accountChain, script) {
        val k = account?.let { Hd.parse(it.xpub) }
        val sc = script
        if (k != null && accountChain != null && sc != null) runCatching { Hd.address(k, accountChain, sc, 0, 0) }.getOrNull() else null
    }

    // Keep the selection valid for what was pasted; a Monero address belongs in the Monero form.
    LaunchedEffect(parsed) {
        when (val r = parsed) {
            is Address.Result.Secret -> {
                ctx.clearClipboardIf(input)
                input = ""
                secret = r.kind
            }
            is Address.Result.Ok, is Address.Result.Account -> {
                secret = null
                if (existing == null) {
                    if (r is Address.Result.Ok && r.chains == listOf("monero")) {
                        xmrAddr = r.address
                        input = ""
                        mode = "monero"
                    } else if (chosen.none { it in options }) {
                        chosen = setOf(options.first())
                    }
                    if (r is Address.Result.Account) script = r.script ?: script?.takeIf { it in scriptOptions } ?: scriptOptions.firstOrNull()
                }
            }
            else -> {}
        }
    }

    fun reason(r: Address.Result.Bad) = t("m.wal.bad_${r.reason}")

    /**
     * After a secret was caught, the keyboard may still be typing the rest of the
     * phrase. Words (letters only, or anything with a space) cannot be an address:
     * drop them until something address-like arrives.
     */
    fun accept(v: String) {
        val text = v.take(400)
        if (secret != null && (text.any { it.isWhitespace() } || text.all { it.isLetter() })) {
            input = ""
            return
        }
        input = text
        error = null
    }

    fun restoreHeight(): Long? {
        val r = restore.trim()
        if (r.isEmpty()) return null
        r.toLongOrNull()?.let { return it.takeIf { h -> h in 1..100_000_000 } }
        return runCatching {
            MoneroSync.heightAt(java.time.LocalDate.parse(r).atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond())
        }.getOrNull()
    }

    fun saveMonero() {
        val addr = Address.clean(xmrAddr)
        if ((Address.parse(addr) as? Address.Result.Ok)?.chains != listOf("monero")) { error = t("m.wal.bad_not_xmr"); return }
        // Empty = unknown: read history backwards from today instead.
        val h = if (restore.isBlank()) 0L else restoreHeight() ?: run { error = t("m.xmr.err_restore"); return }
        val key = viewKey.trim().lowercase()
        busy = true
        scope.launch {
            val problem = withContext(Dispatchers.Default) { ViewWallet.check(addr, key) }
            busy = false
            if (problem == "spend_key") {
                ctx.clearClipboardIf(viewKey)
                viewKey = ""
                secret = "spend_key"
                return@launch
            }
            if (problem != null) { error = t("m.xmr.key_$problem"); return@launch }
            ctx.clearClipboardIf(viewKey)
            val w = Wallet(WalletStore.newId(), "monero", addr, label.trim().filter { !it.isISOControl() }.take(40), "monero", "XMR", "Monero",
                inPortfolio = inPortfolio, created = System.currentTimeMillis() / 1000, view = true, restoreHeight = h)
            withContext(Dispatchers.IO) { MoneroSync.saveViewKey(w.id, Ed.hex(key)) }
            viewKey = ""
            error = Maze.addWallets(listOf(w))
            if (error == null) nav.back() else MoneroSync.forget(w.id)
        }
    }

    SubScreen(nav, if (existing == null) t("m.wal.add") else t("m.wal.edit"), footer = {
        PrimaryButton(if (busy) t("m.xmr.checking") else t("btn.save"), Modifier.fillMaxWidth(), icon = R.drawable.ic_check, enabled = !busy) {
            error = null
            val now = System.currentTimeMillis() / 1000
            val lbl = label.trim().filter { !it.isISOControl() }.take(40)
            if (existing != null && !existing.manual) {
                Maze.updateWallet(existing.copy(label = lbl, inPortfolio = inPortfolio))
                nav.back()
                return@PrimaryButton
            }
            if (mode == "monero") { saveMonero(); return@PrimaryButton }
            if (mode == "manual") {
                val c = coin ?: run { error = t("trade.err_coin"); return@PrimaryButton }
                val a = Fmt.parseOrNull(amount)
                if (a == null || a < 0) { error = t("trade.err_number"); return@PrimaryButton }
                // The reference field is optional, but it is never a place for a secret.
                var ref = input.trim()
                if (ref.isNotEmpty()) {
                    when (val r = Address.parse(ref)) {
                        is Address.Result.Secret -> { ctx.clearClipboardIf(ref); input = ""; secret = r.kind; return@PrimaryButton }
                        is Address.Result.Ok -> ref = r.address
                        is Address.Result.Account -> ref = r.xpub
                        is Address.Result.Bad -> if (c.id == "monero") { error = reason(r); return@PrimaryButton } else ref = ref.take(120)
                    }
                    if (c.id == "monero" && (Address.parse(ref) as? Address.Result.Ok)?.chains != listOf("monero")) {
                        error = t("m.wal.bad_not_xmr"); return@PrimaryButton
                    }
                }
                val chain = if (c.id == "monero") "monero" else "manual"
                val w = (existing ?: Wallet(WalletStore.newId(), chain, "", created = now)).copy(
                    chain = chain, address = ref, label = lbl, coinId = c.id, symbol = c.symbol, name = c.name, amount = a, inPortfolio = inPortfolio)
                if (existing != null) {
                    Maze.updateWallet(w)
                    nav.back()
                } else {
                    error = Maze.addWallets(listOf(w))
                    if (error == null) nav.back()
                }
                return@PrimaryButton
            }
            when (val r = parsed) {
                null -> error = t("m.wal.bad_empty")
                is Address.Result.Secret -> secret = r.kind
                is Address.Result.Bad -> error = reason(r)
                is Address.Result.Account -> {
                    val ch = accountChain ?: run { error = t("m.wal.pick_network"); return@PrimaryButton }
                    val sc = script ?: run { error = t("m.wal.pick_script"); return@PrimaryButton }
                    error = Maze.addWallets(listOf(Wallet(WalletStore.newId(), ch, r.xpub, lbl, inPortfolio = inPortfolio, created = now, script = sc)))
                    if (error == null) nav.back()
                }
                is Address.Result.Ok -> {
                    val chains = chosen.filter { it in options }
                    if (chains.isEmpty()) { error = t("m.wal.pick_network"); return@PrimaryButton }
                    val list = chains.map { ch -> Wallet(WalletStore.newId(), ch, r.address, lbl, inPortfolio = inPortfolio, created = now) }
                    error = Maze.addWallets(list)
                    if (error == null) nav.back()
                }
            }
        }
    }) {
        Gap(6)
        if (existing == null) {
            Segmented(listOf("address" to t("m.wal.mode_address"), "monero" to "Monero", "manual" to t("m.wal.mode_manual")), mode) { mode = it; error = null }
            Gap(10)
            if (mode == "address") {
                Glass(Modifier.fillMaxWidth(), onClick = { nav.go(Route.Ledger) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Ic(R.drawable.ic_usb, tint = p.accent, size = 20.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(t("m.ledger.cta_t"), style = Type.strong, color = p.text)
                            Text(t("m.ledger.cta_b"), style = Type.small, color = p.textMid)
                        }
                        Ic(R.drawable.ic_caret_right, tint = p.muted, size = 16.dp)
                    }
                }
                Gap(10)
            }
        }

        secret?.let { kind ->
            Glass(Modifier.fillMaxWidth(), tint = p.down) {
                Row {
                    Ic(R.drawable.ic_warning, tint = p.down, size = 18.dp)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(t("m.wal.secret_t"), style = Type.strong, color = p.down)
                        Text(t("m.wal.secret_$kind"), style = Type.bodySmall, color = p.text, modifier = Modifier.padding(top = 3.dp))
                        Text(t("m.wal.secret_b"), style = Type.bodySmall, color = p.textMid, modifier = Modifier.padding(top = 3.dp))
                    }
                }
            }
            Gap(10)
        }

        val addrKeyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
            keyboardType = KeyboardType.Uri)
        when (mode) {
            "address" -> if (existing != null) {
                Glass(Modifier.fillMaxWidth()) {
                    ChainDot(existing.chain)
                    Gap(6)
                    SelectionContainer { Text(existing.address, style = Mono, color = p.text) }
                    Text(t("m.wal.address_fixed"), style = Type.small, color = p.muted, modifier = Modifier.padding(top = 6.dp))
                }
            } else {
                Field(input, { accept(it) }, t("m.wal.address"), placeholder = t("m.wal.address_ph"),
                    keyboard = addrKeyboard, textStyle = Mono, error = parsed is Address.Result.Bad,
                    supporting = when (val r = parsed) {
                        is Address.Result.Bad -> reason(r)
                        is Address.Result.Ok -> t("m.wal.valid", "a" to Address.short(r.address, 8, 6))
                        is Address.Result.Account -> t("m.wal.valid_account")
                        else -> t("m.wal.address_help")
                    },
                    trailing = { IconBtn(R.drawable.ic_clipboard_text) { secret = null; input = ctx.pasteText().take(400) } })
                if (options.isNotEmpty()) {
                    Gap(8)
                    Text(if (multi) t("m.wal.networks_multi") else t("m.wal.network"), style = Type.small, color = p.muted)
                    Gap(6)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        options.forEach { ch ->
                            Chip(Chains.of(ch)?.name ?: ch, ch in chosen) {
                                chosen = if (multi) { if (ch in chosen) chosen - ch else chosen + ch } else setOf(ch)
                            }
                        }
                    }
                    val sel = chosen.filter { it in options }
                    if (account == null && sel.any { Chains.of(it)?.activity == false }) {
                        Text(t("m.wal.l2_note"), style = Type.small, color = p.muted, modifier = Modifier.padding(top = 6.dp))
                    }
                }
                if (account != null) {
                    Gap(10)
                    Text(t("m.wal.script"), style = Type.small, color = p.muted)
                    Gap(6)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        scriptOptions.forEach { sc -> Chip(scriptName(sc), sc == script) { script = sc } }
                    }
                    Gap(8)
                    Glass(Modifier.fillMaxWidth()) {
                        Text(t("m.wal.account_t"), style = Type.strong, color = p.text)
                        Text(t("m.wal.account_b"), style = Type.bodySmall, color = p.textMid, modifier = Modifier.padding(top = 2.dp))
                        firstAddress?.let {
                            Gap(8)
                            Text(t("m.wal.first_address"), style = Type.small, color = p.muted)
                            Text(it, style = Mono, color = p.text, modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                }
            }
            "monero" -> {
                SecureScreen()
                TorLive(nav)
                Gap(10)
                Field(xmrAddr, { xmrAddr = it.take(120); error = null }, t("m.xmr.address"), placeholder = t("m.wal.xmr_ph"),
                    keyboard = addrKeyboard, textStyle = Mono, supporting = t("m.xmr.address_help"),
                    trailing = { IconBtn(R.drawable.ic_clipboard_text) { xmrAddr = Address.clean(ctx.pasteText()).take(120) } })
                Gap(8)
                Field(viewKey, { v -> viewKey = v.filter { !it.isWhitespace() }.take(64); error = null; secret = null }, t("m.xmr.view_key"),
                    keyboard = addrKeyboard, textStyle = Mono, supporting = t("m.xmr.view_key_help"),
                    visual = if (showKey) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    trailing = {
                        Row {
                            IconBtn(R.drawable.ic_eye_slash) { showKey = !showKey }
                            IconBtn(R.drawable.ic_clipboard_text) { viewKey = ctx.pasteText().filter { !it.isWhitespace() }.take(64); secret = null }
                        }
                    })
                Gap(8)
                RestoreChooser(restore) { restore = it; error = null }
                Gap(10)
                Glass(Modifier.fillMaxWidth()) {
                    Text(t("m.xmr.view_t"), style = Type.strong, color = p.text)
                    Text(t("m.xmr.view_b"), style = Type.bodySmall, color = p.textMid, modifier = Modifier.padding(top = 2.dp))
                }
            }
            else -> {
                Glass(Modifier.fillMaxWidth(), padding = 12.dp) {
                    ValueRow(t("trade.coin"), coin?.let { "${it.symbol} · ${it.name}" } ?: t("search.pick")) { pickCoin = true }
                }
                Gap(10)
                Field(amount, { amount = it; error = null }, t("trade.amount"), number = true,
                    supporting = coin?.let { c -> Fmt.parseOrNull(amount)?.let { a -> Maze.prices().first[c.id]?.let { Fmt.money(a * it, Maze.settings.value.fiat) } } })
                Gap(8)
                Field(input, { accept(it) }, t(if (coin?.id == "monero") "m.wal.address" else "m.wal.reference"),
                    placeholder = if (coin?.id == "monero") t("m.wal.xmr_ph") else t("m.wal.reference_ph"), keyboard = addrKeyboard,
                    textStyle = Mono, supporting = t("m.wal.reference_help"))
                Gap(8)
                Glass(Modifier.fillMaxWidth()) {
                    Text(if (coin?.id == "monero") t("m.wal.xmr_note") else t("m.wal.manual_note"), style = Type.bodySmall, color = p.textMid)
                }
            }
        }
        Gap(10)
        Field(label, { label = it.take(40) }, t("m.wal.label"), placeholder = t("m.wal.label_ph"))
        Gap(4)
        SwitchRow(t("m.wal.in_portfolio"), inPortfolio, t("m.wal.in_portfolio_help")) { inPortfolio = it }
        error?.let { Text(it, style = Type.body, color = p.down, modifier = Modifier.padding(top = 8.dp)) }
        Gap(12)
        SecurityNote()
        if (mode == "address") {
            Gap(10)
            Glass(Modifier.fillMaxWidth()) {
                Text(t("m.wal.privacy_t"), style = Type.strong, color = p.text)
                Text(t("m.wal.privacy_b"), style = Type.bodySmall, color = p.textMid, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
    if (pickCoin) CoinPicker(t("trade.coin"), { pickCoin = false }) { coin = it; pickCoin = false }
}

// ── on a coin's screen ────────────────────────────────────────────────────

/** "Your wallets" on a coin's chart screen: where the user holds it, and a way to add another address. */
@Composable
fun CoinWallets(nav: Nav, coinId: String, fiat: String) {
    val p = LocalPalette.current
    val wallets by Maze.wallets.collectAsStateWithLifecycle()
    val data by Maze.walletData.collectAsStateWithLifecycle()
    val holding = wallets.mapNotNull { w ->
        val amt = if (w.manual) (if (w.coinId == coinId) w.amount else null)
            else data[w.id]?.balance?.assets?.firstOrNull { it.coinId == coinId }?.amount
        amt?.let { w to it }
    }
    SectionTitle(t("m.wal.coin_section")) {
        LinkButton(t("m.wal.add_short"), arrow = false) { nav.go(Route.WalletEdit(coinId = coinId)) }
    }
    Glass(Modifier.fillMaxWidth(), padding = if (holding.isEmpty()) 16.dp else 0.dp) {
        if (holding.isEmpty()) {
            Text(t("m.wal.coin_none"), style = Type.bodySmall, color = p.textMid)
        }
        val price = Maze.prices().first[coinId]
        holding.forEachIndexed { i, (w, amt) ->
            if (i > 0) Divider(Modifier.padding(horizontal = 14.dp))
            Row(Modifier.fillMaxWidth().clickable { nav.go(Route.WalletDetail(w.id)) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(walletTitle(w), style = Type.strong, color = p.text, maxLines = 1)
                    Text(listOfNotNull(Chains.of(w.chain)?.name, w.address.takeIf { it.isNotEmpty() }?.let { Address.short(it) }).joinToString(" · "),
                        style = Type.small, color = p.muted, maxLines = 1)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(Fmt.amount(amt), style = Type.num, color = p.text)
                    Text(Fmt.money(price?.let { it * amt }, fiat), style = Type.numSmall, color = p.muted)
                }
            }
        }
    }
    if (holding.size > 1) {
        val total = holding.sumOf { it.second }
        KeyValue(t("m.wal.coin_total"), Fmt.amount(total), Modifier.padding(top = 8.dp, start = 2.dp))
    }
}

/**
 * When a Monero wallet started: optional. Empty = unknown, history is read
 * backwards from today. A date (or height) makes the first scan much shorter.
 */
@Composable
fun RestoreChooser(value: String, onChange: (String) -> Unit) {
    val p = LocalPalette.current
    val today = java.time.LocalDate.now(java.time.ZoneOffset.UTC)
    Text(t("m.xmr.restore_q"), style = Type.small, color = p.muted)
    Gap(6)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip(t("m.xmr.restore_unknown"), value.isBlank()) { onChange("") }
        Chip(t("m.xmr.new_wallet"), value == today.toString()) { onChange(today.toString()) }
        listOf(1L to "m.xmr.restore_1m", 6L to "m.xmr.restore_6m", 12L to "m.xmr.restore_1y").forEach { (months, key) ->
            val d = today.minusMonths(months).toString()
            Chip(t(key), value == d) { onChange(d) }
        }
    }
    Gap(8)
    val h = value.trim().takeIf { it.isNotEmpty() }?.let { r ->
        r.toLongOrNull()?.takeIf { it in 1..100_000_000 }
            ?: runCatching { MoneroSync.heightAt(java.time.LocalDate.parse(r).atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond()) }.getOrNull()
    }
    Field(value, { onChange(it.take(12)) }, t("m.xmr.restore"), placeholder = t("m.xmr.restore_unknown_ph"),
        error = value.isNotBlank() && h == null,
        supporting = when {
            value.isBlank() -> t("m.xmr.restore_auto")
            h == null -> t("m.xmr.err_restore")
            else -> t("m.xmr.restore_from", "h" to h.toString())
        })
}

/** Monero only syncs over Tor: what to do when Orbot is not running. */
@Composable
fun TorRequired(nav: Nav) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val s = Maze.settings.value
    Glass(Modifier.fillMaxWidth(), tint = p.warn) {
        Row {
            Ic(R.drawable.ic_warning, tint = p.warn, size = 18.dp)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(t("m.xmr.tor_required_t"), style = Type.strong, color = p.text)
                Text(t("m.xmr.tor_required_b", "host" to s.torHost, "port" to s.torPort.toString()), style = Type.bodySmall,
                    color = p.textMid, modifier = Modifier.padding(top = 2.dp))
            }
        }
        Gap(10)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton(t("m.xmr.open_orbot"), Modifier.weight(1f)) { ctx.openOrbot() }
            GhostButton(t("set.title"), Modifier.weight(1f), icon = R.drawable.ic_gear_six) { nav.go(Route.Settings) }
        }
    }
}

/** Orbot if it is installed, else its download page. */
fun Context.openOrbot() {
    val launch = packageManager.getLaunchIntentForPackage("org.torproject.android")
    if (launch != null) runCatching { startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    else openLink("https://orbot.app/")
}

/** The Monero form: is Tor up right now? Checked every few seconds while the form is open. */
@Composable
private fun TorLive(nav: Nav) {
    val p = LocalPalette.current
    val s = Maze.settings.value
    var up by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            up = MoneroSync.torReachable(Maze.settings.value)
            kotlinx.coroutines.delay(3_000)
        }
    }
    when (up) {
        false -> TorRequired(nav)
        else -> Glass(Modifier.fillMaxWidth(), tint = if (up == true) p.up else p.accent) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Ic(R.drawable.ic_shield_check, tint = if (up == true) p.up else p.accent, size = 18.dp)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(if (up == true) t("m.xmr.tor_ok", "host" to s.torHost, "port" to s.torPort.toString()) else t("m.xmr.tor_t"),
                        style = Type.strong, color = p.text)
                    Text(t("m.xmr.tor_b", "host" to s.torHost, "port" to s.torPort.toString()), style = Type.bodySmall,
                        color = p.textMid, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}
