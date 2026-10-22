package com.mazecrypto.app.ui.screens

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mazecrypto.app.R
import com.mazecrypto.app.core.CoinRow
import com.mazecrypto.app.core.Fmt
import com.mazecrypto.app.core.t
import com.mazecrypto.app.data.Maze
import com.mazecrypto.app.ui.Nav
import com.mazecrypto.app.ui.Route
import com.mazecrypto.app.ui.components.ChangeText
import com.mazecrypto.app.ui.components.IconBtn
import com.mazecrypto.app.ui.components.Sparkline
import com.mazecrypto.app.ui.components.StatusPill
import com.mazecrypto.app.ui.rememberNow
import com.mazecrypto.app.ui.status
import com.mazecrypto.app.ui.theme.LocalPalette
import com.mazecrypto.app.ui.theme.Type
import com.mazecrypto.app.widget.CoinWidget
import com.mazecrypto.app.widget.FavoritesWidget
import com.mazecrypto.app.widget.PortfolioWidget

/** Status pill (tap = refresh now) and the settings gear. */
@Composable
fun TopActions(nav: Nav, settings: Boolean = true) {
    val snap by Maze.snapshot.collectAsStateWithLifecycle()
    val refreshing by Maze.refreshing.collectAsStateWithLifecycle()
    val now = rememberNow()
    val (state, text) = status(snap, refreshing, now)
    StatusPill(state, text) { Maze.refreshAsync() }
    if (settings) IconBtn(R.drawable.ic_gear_six) { nav.go(Route.Settings) }
}

/** A tab screen: a scrolling list under a page header, with pull-to-refresh. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabScreen(content: LazyListScope.() -> Unit) {
    val refreshing by Maze.refreshing.collectAsStateWithLifecycle()
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = { Maze.refreshAsync() }, modifier = Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            content = content)
    }
}

/** A pushed screen (chart, settings, editors): back arrow, title, scrolling body. */
@Composable
fun SubScreen(nav: Nav, title: String, actions: @Composable () -> Unit = {}, footer: (@Composable () -> Unit)? = null,
              content: @Composable () -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(R.drawable.ic_arrow_left, tint = p.text) { nav.back() }
            Text(title, style = Type.strong.copy(fontSize = 18.sp),
                color = p.text, modifier = Modifier.weight(1f).padding(start = 4.dp), maxLines = 1)
            actions()
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            content()
        }
        if (footer != null) {
            Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) { footer() }
        } else {
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

/** One coin as a row: ticker and name, 7-day line, price and change. */
@Composable
fun CoinLine(row: CoinRow, fiat: String, period: String = "24h", star: Boolean? = null, onStar: (() -> Unit)? = null,
             onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (star != null) {
            Box(Modifier.size(32.dp).clickable { onStar?.invoke() }, contentAlignment = Alignment.CenterStart) {
                com.mazecrypto.app.ui.components.Ic(if (star) R.drawable.ic_star_fill else R.drawable.ic_star,
                    tint = if (star) p.accent else p.dim, size = 18.dp)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(row.coin.symbol, style = Type.strong, color = p.text, maxLines = 1)
            Text(row.coin.name, style = Type.small, color = p.muted, maxLines = 1)
        }
        Sparkline(row.spark, Modifier.width(64.dp).height(26.dp).padding(horizontal = 4.dp))
        Column(Modifier.width(118.dp), horizontalAlignment = Alignment.End) {
            Text(Fmt.money(row.priceFiat, fiat), style = Type.num, color = if (row.stale) p.textMid else p.text, maxLines = 1)
            ChangeText(row.change(period), style = Type.numSmall)
        }
    }
}

fun Context.pinWidget(kind: String): Boolean {
    val mgr = AppWidgetManager.getInstance(this)
    if (!mgr.isRequestPinAppWidgetSupported) return false
    val cls = when (kind) {
        "coin" -> CoinWidget::class.java
        "portfolio" -> PortfolioWidget::class.java
        "grid" -> com.mazecrypto.app.widget.GridWidget::class.java
        "movers" -> com.mazecrypto.app.widget.MoversWidget::class.java
        "holdings" -> com.mazecrypto.app.widget.HoldingsWidget::class.java
        "plan" -> com.mazecrypto.app.widget.PlanWidget::class.java
        "trend" -> com.mazecrypto.app.widget.TrendWidget::class.java
        "wallets" -> com.mazecrypto.app.widget.WalletsWidget::class.java
        else -> FavoritesWidget::class.java
    }
    return runCatching { mgr.requestPinAppWidget(ComponentName(this, cls), null, null) }.getOrDefault(false)
}

fun greeting(): String {
    val h = java.time.LocalTime.now().hour
    return t(if (h < 12) "home.morning" else if (h < 18) "home.afternoon" else "home.evening")
}

@Composable
fun Gap(h: Int = 12) = Spacer(Modifier.height(h.dp))

/**
 * While shown, the screen cannot be captured (screenshots, recording, the
 * recent-apps thumbnail) — for screens where a key is typed. Restores the
 * user's own setting when it goes away.
 */
@Composable
fun SecureScreen() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.DisposableEffect(Unit) {
        val w = (ctx as? android.app.Activity)?.window
        w?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            if (!Maze.settings.value.secureScreen) w?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
