package com.mazecrypto.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.mazecrypto.app.R
import com.mazecrypto.app.core.Address
import com.mazecrypto.app.core.Fmt
import com.mazecrypto.app.core.Wallet
import com.mazecrypto.app.core.t
import com.mazecrypto.app.data.Chains
import com.mazecrypto.app.data.Maze
import com.mazecrypto.app.data.MoneroSync
import com.mazecrypto.app.data.WalletStore
import com.mazecrypto.app.hw.Ledger
import com.mazecrypto.app.hw.LedgerAccount
import com.mazecrypto.app.hw.LedgerBle
import com.mazecrypto.app.hw.LedgerDiscovery
import com.mazecrypto.app.hw.LedgerError
import com.mazecrypto.app.hw.LedgerNetworks
import com.mazecrypto.app.hw.LedgerUsb
import com.mazecrypto.app.hw.Transport
import com.mazecrypto.app.ui.Nav
import com.mazecrypto.app.ui.components.Badge
import com.mazecrypto.app.ui.components.Chip
import com.mazecrypto.app.ui.components.Divider
import com.mazecrypto.app.ui.components.Field
import com.mazecrypto.app.ui.components.GhostButton
import com.mazecrypto.app.ui.components.LinkButton
import com.mazecrypto.app.ui.components.Glass
import com.mazecrypto.app.ui.components.Ic
import com.mazecrypto.app.ui.components.PrimaryButton
import com.mazecrypto.app.ui.components.SectionTitle
import com.mazecrypto.app.ui.components.Segmented
import com.mazecrypto.app.ui.theme.LocalPalette
import com.mazecrypto.app.ui.theme.Type
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val Mono = Type.numSmall.copy(fontFamily = FontFamily.Monospace)

/**
 * Add accounts from a Ledger in one session: connect once, pick every coin
 * wanted, and the app opens each coin's app on the device in turn (the user
 * only confirms "open"), reading Ledger Live's accounts of each. Read-only:
 * no command that signs anything exists in the app.
 */
@Composable
fun LedgerScreen(nav: Nav) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var nets by remember { mutableStateOf(setOf("bitcoin", "ethereum")) }
    var link by remember { mutableStateOf("usb") }
    var phase by remember { mutableStateOf("setup") }          // setup, ble, working, results
    var status by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var skipped by remember { mutableStateOf(listOf<String>()) }
    var bleFound by remember { mutableStateOf(listOf<LedgerBle.Companion.Found>()) }
    var bleDevice by remember { mutableStateOf<LedgerBle.Companion.Found?>(null) }
    var accounts by remember { mutableStateOf(listOf<LedgerAccount>()) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var evmNets by remember { mutableStateOf(setOf("ethereum")) }
    var restore by remember { mutableStateOf("") }
    var ledger by remember { mutableStateOf<Ledger?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var verified by remember { mutableStateOf(setOf<String>()) }

    DisposableEffect(Unit) { onDispose { job?.cancel(); ledger?.close() } }

    fun appName(chain: String) = LedgerNetworks.of(chain).app
    fun message(e: Throwable, chain: String? = null): String = when (e) {
        is LedgerError -> t("m.ledger.err_${e.code}", "app" to (chain?.let { appName(it) } ?: ""))
        else -> t("m.ledger.err_other", "err" to (e.message ?: e.javaClass.simpleName))
    }

    suspend fun openTransport(): Transport = if (link == "usb") {
        // After an app is opened the device re-enumerates: give it a few seconds to come back.
        var dev = LedgerUsb.find(ctx)
        var tries = 0
        while (dev == null && tries++ < 16) { delay(500); dev = LedgerUsb.find(ctx) }
        val d = dev ?: throw LedgerError("no_device")
        if (!LedgerUsb.permission(ctx, d)) throw LedgerError("permission")
        LedgerUsb.open(ctx, d)
    } else {
        LedgerBle.connect(ctx, (bleDevice ?: throw LedgerError("no_device")).device)
    }

    /** The same session if the link survived (Bluetooth usually does), else a fresh one (USB re-enumerates). */
    suspend fun reattach(): Ledger {
        ledger?.let { l -> if (withTimeoutOrNull(4_000) { runCatching { l.app() }.getOrNull() } != null) return l }
        ledger?.close()
        delay(1_000)
        return Ledger(openTransport()).also { ledger = it }
    }

    /** Make [want] the running app: leave the current one, open [want] (the user confirms on the device). */
    suspend fun ensureApp(want: String): Ledger {
        var l = ledger ?: Ledger(openTransport()).also { ledger = it }
        var app = withTimeoutOrNull(8_000) { runCatching { l.app() }.getOrNull() } ?: run { l = reattach(); l.app() }
        if (app.name == want) return l
        if (!app.dashboard) {
            status = t("m.ledger.closing", "app" to app.name)
            l.quitApp()
            delay(1_500)
            l = reattach()
        }
        status = t("m.ledger.opening", "app" to want)
        l.openApp(want)
        delay(1_500)
        l = reattach()
        app = l.app()
        if (app.name != want) throw LedgerError("wrong_app")
        return l
    }

    fun readAll() {
        job?.cancel()
        job = scope.launch {
            error = null
            skipped = emptyList()
            phase = "working"
            accounts = emptyList()
            selected = emptySet()
            verified = emptySet()
            val owned = Maze.wallets.value.map { Address.key(it.chain, it.address) }.toSet()
            val svc = Maze.service().wallets
            val all = mutableListOf<LedgerAccount>()
            try {
                status = t("m.ledger.connecting")
                for (net in LedgerNetworks.ALL.map { it.id }.filter { it in nets }) {
                    try {
                        val l = ensureApp(appName(net))
                        status = t("m.ledger.reading", "app" to appName(net))
                        // Key derivation and network checks off the main thread; only the device I/O suspends here.
                        val found = withContext(Dispatchers.Default) { LedgerDiscovery.discover(l, net, svc) { accounts = all + it } }
                        all += found
                        accounts = all.toList()
                    } catch (e: LedgerError) {
                        // This coin's app is missing or was declined: note it and go on with the others.
                        if (e.code in setOf("not_installed", "denied", "wrong_app", "bad_request", "status", "xmr_view_declined")) {
                            skipped = skipped + message(e, net)
                        } else throw e
                    }
                }
                selected = all.filter { (it.used == true || it.chain == "monero") && Address.key(it.chain, it.address) !in owned }
                    .map { it.key }.toSet()
                    .ifEmpty { all.firstOrNull { Address.key(it.chain, it.address) !in owned }?.let { setOf(it.key) } ?: emptySet() }
                phase = if (all.isNotEmpty()) "results" else "setup"
                if (all.isEmpty() && skipped.isEmpty()) error = t("m.ledger.nothing")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = message(e)
                ledger?.close(); ledger = null
                phase = if (all.isNotEmpty()) "results" else "setup"
            }
        }
    }

    fun scanBle() {
        job?.cancel()
        bleFound = LedgerBle.bonded(ctx)
        error = null
        phase = "ble"
        job = scope.launch {
            withTimeoutOrNull(20_000) {
                LedgerBle.scan(ctx).catch { error = message(it) }.collect { f ->
                    if (bleFound.none { it.device.address == f.device.address }) bleFound = bleFound + f
                }
            }
        }
    }

    val blePerms = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.all { it }) scanBle() else error = t("m.ledger.err_bt_permission")
    }

    fun start() {
        if (nets.isEmpty()) { error = t("m.ledger.pick_coins"); return }
        if (link == "ble" && bleDevice == null) {
            val ok = blePerms.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
            if (ok) scanBle() else permLauncher.launch(blePerms)
        } else readAll()
    }

    fun restoreHeight(): Long? {
        val r = restore.trim()
        r.toLongOrNull()?.let { return it.takeIf { h -> h in 1..100_000_000 } }
        return runCatching { MoneroSync.heightAt(java.time.LocalDate.parse(r).atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond()) }.getOrNull()
    }

    fun add() {
        val now = System.currentTimeMillis() / 1000
        val owned = Maze.wallets.value.map { Address.key(it.chain, it.address) }.toSet()
        val picked = accounts.filter { it.key in selected }
        // Empty = unknown: the wallet's history is read backwards from today.
        val xmrHeight = if (restore.isBlank()) 0L else restoreHeight() ?: run { error = t("m.xmr.err_restore"); return }
        val keys = mutableMapOf<String, ByteArray>()
        val list = picked.flatMap { a ->
            val chains = if (a.chain == "ethereum") evmNets.toList().ifEmpty { listOf("ethereum") } else listOf(a.chain)
            chains.filter { Address.key(it, a.address) !in owned }.map { ch ->
                val kind = if (a.script.isNotEmpty()) scriptName(a.script) else Chains.of(ch)?.name ?: ch
                if (ch == "monero") {
                    Wallet(WalletStore.newId(), "monero", a.address, "Ledger · Monero", "monero", "XMR", "Monero", created = now,
                        device = "ledger", view = true, restoreHeight = xmrHeight).also { w -> a.viewKey?.let { keys[w.id] = it } }
                } else Wallet(WalletStore.newId(), ch, a.address, "Ledger · $kind #${a.index + 1}".take(40), created = now,
                    script = a.script, path = a.pathText, device = "ledger")
            }
        }
        if (list.isEmpty()) { error = t("m.ledger.nothing"); return }
        scope.launch {
            withContext(Dispatchers.IO) { keys.forEach { (id, k) -> MoneroSync.saveViewKey(id, k) } }
            error = Maze.addWallets(list)
            if (error == null) nav.back() else keys.keys.forEach { MoneroSync.forget(it) }
        }
    }

    val footer: (@Composable () -> Unit)? = if (phase == "results") {
        {
            PrimaryButton(t("m.ledger.add_n", "n" to selected.size.toString()), Modifier.fillMaxWidth(), icon = R.drawable.ic_plus,
                enabled = selected.isNotEmpty()) { add() }
        }
    } else null
    SubScreen(nav, t("m.ledger.title"), footer = footer) {
        Gap(6)
        Glass(Modifier.fillMaxWidth(), tint = p.up) {
            Row {
                Ic(R.drawable.ic_shield_check, tint = p.up, size = 18.dp)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(t("m.ledger.safe_t"), style = Type.strong, color = p.text)
                    Text(t("m.ledger.safe_b"), style = Type.bodySmall, color = p.textMid, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }

        SectionTitle(t("m.ledger.coins"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LedgerNetworks.ALL.forEach { n ->
                Chip("${n.coin} · ${Chains.of(n.id)?.name ?: n.id}", n.id in nets) {
                    if (phase != "working") nets = if (n.id in nets) nets - n.id else nets + n.id
                }
            }
        }
        Text(t("m.ledger.coins_help"), style = Type.small, color = p.muted, modifier = Modifier.padding(top = 6.dp, start = 2.dp))
        if ("ethereum" in nets) {
            Gap(10)
            Text(t("m.ledger.evm_nets"), style = Type.small, color = p.muted)
            Gap(6)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Address.EVM_CHAINS.forEach { ch ->
                    Chip(Chains.of(ch)?.name ?: ch, ch in evmNets) { evmNets = if (ch in evmNets) evmNets - ch else evmNets + ch }
                }
            }
        }
        if ("monero" in nets) {
            Gap(10)
            RestoreChooser(restore) { restore = it; error = null }
        }

        SectionTitle(t("m.ledger.link"))
        Segmented(listOf("usb" to t("m.ledger.usb"), "ble" to "Bluetooth"), link) {
            if (phase != "working") { link = it; phase = "setup"; error = null; ledger?.close(); ledger = null }
        }
        Text(if (link == "usb") t("m.ledger.usb_help") else t("m.ledger.ble_help"), style = Type.small, color = p.muted,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp))
        if (link == "ble" && bleDevice != null && phase != "ble") {
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Ic(R.drawable.ic_bluetooth, tint = p.accent, size = 16.dp)
                Spacer(Modifier.width(6.dp))
                Text(bleDevice!!.name, style = Type.strong, color = p.text, modifier = Modifier.weight(1f))
                LinkButton(t("m.ledger.other_device"), arrow = false) { ledger?.close(); ledger = null; bleDevice = null; start() }
            }
        }
        Gap(10)
        Glass(Modifier.fillMaxWidth()) {
            Text(t("m.ledger.steps_t"), style = Type.strong, color = p.text)
            Text(t("m.ledger.steps_multi"), style = Type.bodySmall, color = p.textMid, modifier = Modifier.padding(top = 2.dp))
        }
        Gap(10)
        when (phase) {
            "setup" -> PrimaryButton(t("m.ledger.connect"), Modifier.fillMaxWidth(),
                icon = if (link == "usb") R.drawable.ic_usb else R.drawable.ic_bluetooth) { start() }
            "ble" -> {
                Glass(Modifier.fillMaxWidth(), padding = 0.dp) {
                    if (bleFound.isEmpty()) Text(t("m.ledger.searching"), style = Type.body, color = p.textMid, modifier = Modifier.padding(14.dp))
                    bleFound.forEachIndexed { i, f ->
                        if (i > 0) Divider(Modifier.padding(horizontal = 14.dp))
                        Row(Modifier.fillMaxWidth().clickable { bleDevice = f; job?.cancel(); readAll() }.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Ic(R.drawable.ic_bluetooth, tint = p.accent, size = 18.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(f.name, style = Type.strong, color = p.text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (f.rssi == 0) t("m.ledger.paired") else "${f.rssi} dBm", style = Type.small, color = p.muted)
                        }
                    }
                }
                Gap(8)
                GhostButton(t("m.ledger.search_again"), Modifier.fillMaxWidth(), icon = R.drawable.ic_arrows_clockwise) { scanBle() }
            }
            "working" -> Glass(Modifier.fillMaxWidth(), tint = p.accent) {
                Text(status, style = Type.body, color = p.text)
                Text(t("m.ledger.look_at_device"), style = Type.small, color = p.textMid, modifier = Modifier.padding(top = 4.dp))
            }
            "results" -> GhostButton(t("m.ledger.read_again"), Modifier.fillMaxWidth(), icon = R.drawable.ic_arrows_clockwise) { readAll() }
        }
        error?.let {
            Gap(10)
            Glass(Modifier.fillMaxWidth(), tint = p.down) { Text(it, style = Type.body, color = p.text) }
        }
        if (skipped.isNotEmpty()) {
            Gap(10)
            Glass(Modifier.fillMaxWidth(), tint = p.warn) {
                Text(t("m.ledger.skipped"), style = Type.strong, color = p.text)
                skipped.forEach { Text("• $it", style = Type.bodySmall, color = p.textMid, modifier = Modifier.padding(top = 2.dp)) }
            }
        }

        if (accounts.isNotEmpty()) {
            SectionTitle(t("m.ledger.accounts"))
            val owned = Maze.wallets.value.map { Address.key(it.chain, it.address) }.toSet()
            Glass(Modifier.fillMaxWidth(), padding = 0.dp) {
                accounts.forEachIndexed { i, a ->
                    if (i > 0) Divider(Modifier.padding(horizontal = 14.dp))
                    val have = Address.key(a.chain, a.address) in owned
                    Row(Modifier.fillMaxWidth().clickable(enabled = !have && phase == "results") {
                        selected = if (a.key in selected) selected - a.key else selected + a.key
                    }.padding(start = 4.dp, end = 14.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.Top) {
                        Checkbox(a.key in selected, onCheckedChange = null, enabled = !have, modifier = Modifier.padding(top = 2.dp),
                            colors = CheckboxDefaults.colors(checkedColor = p.accent, uncheckedColor = p.muted))
                        Spacer(Modifier.width(4.dp))
                        Column(Modifier.weight(1f)) {
                            val net = LedgerNetworks.of(a.chain)
                            Text("${net.coin} · " + if (a.chain == "monero") t("m.xmr.view_badge") else t("m.ledger.account_n", "n" to (a.index + 1).toString()),
                                style = Type.strong, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (a.script.isNotEmpty()) Badge(scriptName(a.script), p.textMid)
                                when {
                                    have -> Badge(t("m.ledger.added"), p.muted)
                                    a.used == true -> Badge(t("m.ledger.used"), p.up)
                                    a.used == false -> Badge(t("m.ledger.new"), p.muted)
                                }
                                if (a.key in verified) Badge(t("m.ledger.verified"), p.up)
                            }
                            Text(Address.short(a.address, 12, 8), style = Mono, color = p.textMid, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 6.dp))
                            val sub = listOfNotNull(a.pathText.takeIf { a.path.isNotEmpty() },
                                a.balance?.takeIf { it > 0 }?.let { Fmt.amount(it) + " " + net.coin }).joinToString(" · ")
                            if (sub.isNotEmpty()) Text(sub, style = Type.small, color = p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp))
                            if (phase == "results") {
                                LinkButton(t("m.ledger.verify"), Modifier.padding(top = 2.dp), arrow = false) {
                                    job?.cancel()
                                    job = scope.launch {
                                        error = null
                                        try {
                                            val l = ensureApp(appName(a.chain))
                                            LedgerDiscovery.verify(l, a)
                                            verified = verified + a.key
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Throwable) {
                                            error = message(e, a.chain)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Text(t("m.ledger.discovery_note"), style = Type.small, color = p.muted, modifier = Modifier.padding(top = 6.dp, start = 2.dp))
        }
    }
}
