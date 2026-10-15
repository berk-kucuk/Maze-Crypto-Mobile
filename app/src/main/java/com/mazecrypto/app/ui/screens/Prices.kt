package com.mazecrypto.app.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mazecrypto.app.R
import com.mazecrypto.app.core.Coin
import com.mazecrypto.app.core.CoinRow
import com.mazecrypto.app.core.Fmt
import com.mazecrypto.app.core.t
import com.mazecrypto.app.data.Maze
import com.mazecrypto.app.ui.Nav
import com.mazecrypto.app.ui.Route
import com.mazecrypto.app.ui.components.Badge
import com.mazecrypto.app.ui.components.ChangeText
import com.mazecrypto.app.ui.components.Chip
import com.mazecrypto.app.ui.components.Divider
import com.mazecrypto.app.ui.components.Field
import com.mazecrypto.app.ui.components.Glass
import com.mazecrypto.app.ui.components.Ic
import com.mazecrypto.app.ui.components.IconBtn
import com.mazecrypto.app.ui.components.PageHeader
import com.mazecrypto.app.ui.components.Picker
import com.mazecrypto.app.ui.components.PrimaryButton
import com.mazecrypto.app.ui.components.Sparkline
import com.mazecrypto.app.ui.theme.LocalPalette
import com.mazecrypto.app.ui.theme.Type
import kotlinx.coroutines.delay

@Composable
fun PricesScreen(nav: Nav, openSearch: Boolean) {
    val p = LocalPalette.current
    val settings by Maze.settings.collectAsStateWithLifecycle()
    val snap by Maze.snapshot.collectAsStateWithLifecycle()
    var search by remember { mutableStateOf(openSearch) }
    var period by remember { mutableStateOf(settings.widgetChange.takeIf { it != "24h" } ?: "24h") }
    var menuFor by remember { mutableStateOf<Coin?>(null) }
    val favs = settings.favorites.toSet()

    TabScreen {
        item {
            PageHeader(t("watch.title"), t("m.watch.sub", "n" to settings.watchlist.size, "fiat" to settings.fiat)) {
                TopActions(nav)
            }
            Gap(10)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf("1h" to "1h", "24h" to t("col.24h"), "7d" to t("col.7d"), "30d" to t("col.30d")).forEach { (k, label) ->
                    Chip(label, selected = period == k) { period = k }
                }
                Spacer(Modifier.weight(1f))
                IconBtn(R.drawable.ic_plus, tint = p.accent) { search = true }
            }
            Gap(10)
            if (settings.favorites.isEmpty() && settings.watchlist.isNotEmpty()) {
                Glass(Modifier.fillMaxWidth(), padding = 12.dp, tint = p.accent) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Ic(R.drawable.ic_star_fill, tint = p.accent, size = 16.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(t("m.watch.star_hint"), style = Type.bodySmall, color = p.textMid)
                    }
                }
                Gap(10)
            }
        }
        if (settings.watchlist.isEmpty()) {
            item {
                com.mazecrypto.app.ui.components.EmptyState(R.drawable.ic_coins, t("tip.start_watch.t"), t("tip.start_watch.b")) {
                    PrimaryButton(t("watch.add"), icon = R.drawable.ic_plus) { search = true }
                }
            }
        } else {
            item {
                Glass(Modifier.fillMaxWidth(), padding = 0.dp) {
                    settings.watchlist.forEachIndexed { i, coin ->
                        if (i > 0) Divider(Modifier.padding(horizontal = 14.dp))
                        val row = snap?.row(coin.id) ?: CoinRow(coin)
                        PriceRow(row, settings.fiat, period, coin.id in favs, settings.expert,
                            onStar = { Maze.updateSettings { s -> s.copy(favorites = if (coin.id in s.favorites) s.favorites - coin.id else s.favorites + coin.id) } },
                            onLong = { menuFor = coin }) { nav.go(Route.Chart(coin.id)) }
                    }
                }
            }
            item {
                Gap(10)
                Text(snap?.let { t("watch.foot", "time" to Fmt.clock(it.ts)) } ?: "", style = Type.small, color = p.muted)
                Text(t("m.watch.long_press"), style = Type.small, color = p.muted, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }

    if (search) {
        CoinSearchSheet(onDismiss = { search = false }, inList = settings.watchlist.map { it.id }.toSet()) { coin ->
            Maze.updateSettings { s ->
                if (s.watchlist.any { it.id == coin.id }) s
                else s.copy(watchlist = s.watchlist + coin, favorites = if (s.favorites.size < 8) s.favorites + coin.id else s.favorites)
            }
        }
    }
    menuFor?.let { coin ->
        val idx = settings.watchlist.indexOfFirst { it.id == coin.id }
        val fav = coin.id in favs
        Picker(coin.symbol, listOfNotNull(
            "chart" to t("watch.open_chart"),
            "star" to if (fav) t("m.watch.unstar") else t("m.watch.star"),
            ("up" to t("watch.move_up")).takeIf { idx > 0 },
            ("down" to t("watch.move_down")).takeIf { idx < settings.watchlist.size - 1 },
            "remove" to t("watch.remove"),
        ), null, onDismiss = { menuFor = null }) { action ->
            when (action) {
                "chart" -> nav.go(Route.Chart(coin.id))
                "star" -> Maze.updateSettings { s -> s.copy(favorites = if (fav) s.favorites - coin.id else s.favorites + coin.id) }
                "up", "down" -> Maze.updateSettings { s ->
                    val l = s.watchlist.toMutableList()
                    val j = if (action == "up") idx - 1 else idx + 1
                    l[idx] = l[j].also { l[j] = l[idx] }
                    // Favorites follow the list order, so the widgets do too.
                    val order = l.map { it.id }
                    s.copy(watchlist = l, favorites = s.favorites.sortedBy { order.indexOf(it) })
                }
                "remove" -> Maze.updateSettings { s -> s.copy(watchlist = s.watchlist.filter { it.id != coin.id }, favorites = s.favorites - coin.id) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PriceRow(row: CoinRow, fiat: String, period: String, star: Boolean, expert: Boolean,
                     onStar: () -> Unit, onLong: () -> Unit, onClick: () -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLong).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(30.dp).clickable(onClick = onStar), contentAlignment = Alignment.CenterStart) {
                Ic(if (star) R.drawable.ic_star_fill else R.drawable.ic_star, tint = if (star) p.accent else p.dim, size = 18.dp)
            }
            Column(Modifier.weight(1f)) {
                Text(row.coin.symbol, style = Type.strong, color = p.text, maxLines = 1)
                Text(row.coin.name, style = Type.small, color = p.muted, maxLines = 1)
            }
            Sparkline(row.spark, Modifier.width(62.dp).height(26.dp).padding(horizontal = 4.dp))
            Column(Modifier.width(116.dp), horizontalAlignment = Alignment.End) {
                Text(Fmt.money(row.priceFiat, fiat), style = Type.num, color = if (row.stale) p.textMid else p.text, maxLines = 1)
                ChangeText(row.change(period), style = Type.numSmall)
            }
        }
        if (expert) {
            Row(Modifier.padding(start = 30.dp, top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${t("col.ath")} ${Fmt.pct(row.athChangePct, 1)}", style = Type.small, color = p.muted)
                Text("${t("col.mcap")} ${Fmt.compact(row.marketCap)}", style = Type.small, color = p.muted)
                if (row.fiatSource.isNotEmpty()) Text(row.fiatSource, style = Type.small, color = p.muted, maxLines = 1)
            }
        }
    }
}

/** Find any coin on CoinGecko. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoinSearchSheet(onDismiss: () -> Unit, inList: Set<String> = emptySet(), closeOnPick: Boolean = false, onPick: (Coin) -> Unit) {
    val p = LocalPalette.current
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Pair<Coin, Int?>>>(emptyList()) }
    var state by remember { mutableStateOf("") }
    var added by remember { mutableStateOf(inList) }
    LaunchedEffect(q) {
        if (q.isBlank()) {
            results = emptyList(); state = ""; return@LaunchedEffect
        }
        delay(350)
        state = t("search.searching")
        state = try {
            results = Maze.service().search(q)
            if (results.isEmpty()) t("search.none") else ""
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            t("search.failed", "err" to (e.message ?: ""))
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = p.surface) {
        Column(Modifier.padding(horizontal = 16.dp).navigationBarsPadding()) {
            Text(t("search.title"), style = Type.strong, color = p.text)
            Gap(10)
            Field(q, { q = it }, t("m.search.label"), placeholder = t("search.placeholder"))
            if (state.isNotEmpty()) Text(state, style = Type.small, color = p.muted, modifier = Modifier.padding(top = 8.dp))
            LazyColumn(Modifier.heightIn(min = 200.dp, max = 460.dp).padding(top = 6.dp)) {
                items(results, key = { it.first.id }) { (coin, rank) ->
                    val has = coin.id in added
                    Row(Modifier.fillMaxWidth().clickable(enabled = !has) {
                        onPick(coin)
                        added = added + coin.id
                        if (closeOnPick) onDismiss()
                    }.padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(coin.symbol, style = Type.strong, color = p.text)
                            Text(coin.name, style = Type.small, color = p.muted)
                        }
                        if (rank != null) Text("#$rank", style = Type.small, color = p.muted, modifier = Modifier.padding(end = 10.dp))
                        if (has) Badge(t("m.search.added"), p.up) else Ic(R.drawable.ic_plus, tint = p.accent, size = 18.dp)
                    }
                }
            }
            Text(t("m.search.hint"), style = Type.small, color = p.muted, modifier = Modifier.padding(vertical = 10.dp))
        }
    }
}
