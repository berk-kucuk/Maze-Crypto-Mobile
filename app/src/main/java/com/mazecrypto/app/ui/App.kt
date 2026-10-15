package com.mazecrypto.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.mazecrypto.app.R
import com.mazecrypto.app.core.Fmt
import com.mazecrypto.app.core.t
import com.mazecrypto.app.data.Maze
import com.mazecrypto.app.data.Snapshot
import com.mazecrypto.app.ui.components.Ic
import com.mazecrypto.app.ui.screens.AboutScreen
import com.mazecrypto.app.ui.screens.AlertsScreen
import com.mazecrypto.app.ui.screens.ChartScreen
import com.mazecrypto.app.ui.screens.DcaEditor
import com.mazecrypto.app.ui.screens.HomeScreen
import com.mazecrypto.app.ui.screens.InsightsScreen
import com.mazecrypto.app.ui.screens.OnboardingScreen
import com.mazecrypto.app.ui.screens.PlanScreen
import com.mazecrypto.app.ui.screens.PortfolioScreen
import com.mazecrypto.app.ui.screens.PricesScreen
import com.mazecrypto.app.ui.screens.RuleEditor
import com.mazecrypto.app.ui.screens.SettingsScreen
import com.mazecrypto.app.ui.screens.TargetsEditor
import com.mazecrypto.app.ui.screens.TradeEditor
import com.mazecrypto.app.ui.theme.LocalPalette
import com.mazecrypto.app.ui.theme.MazeTheme
import com.mazecrypto.app.ui.theme.Type
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

sealed interface Route {
    data object Home : Route
    data class Prices(val search: Boolean = false) : Route
    data class Portfolio(val tab: String = "positions") : Route
    data object Plan : Route
    data object Alerts : Route
    data class Chart(val coinId: String) : Route
    data object Settings : Route
    data object About : Route
    data object Insights : Route
    data class Trade(val id: Long = 0, val coinId: String? = null, val side: String = "buy") : Route
    data object Targets : Route
    data object DcaEdit : Route
    data class RuleEdit(val id: String? = null) : Route
}

/** A plain back stack; the bottom-bar tabs reset it. */
class Nav(start: Route = Route.Home) {
    val stack = mutableStateListOf(start)
    val top: Route get() = stack.last()

    fun go(r: Route) {
        stack.add(r)
    }

    fun tab(r: Route) {
        stack.clear()
        stack.add(r)
    }

    fun back(): Boolean {
        if (stack.size <= 1) {
            if (stack.firstOrNull() != Route.Home) {
                tab(Route.Home)
                return true
            }
            return false
        }
        stack.removeAt(stack.lastIndex)
        return true
    }

    /** Where a tip's or a link's action points. */
    fun action(a: String) {
        when {
            a == "watchlist" -> go(Route.Prices(search = true))
            a == "holdings:add" -> go(Route.Trade())
            a == "holdings:targets" -> go(Route.Targets)
            a == "holdings:alloc" -> tab(Route.Portfolio("alloc"))
            a == "holdings:trades" -> tab(Route.Portfolio("trades"))
            a == "dca" -> tab(Route.Plan)
            a == "rules" -> tab(Route.Alerts)
            a.startsWith("chart:") -> go(Route.Chart(a.removePrefix("chart:")))
        }
    }

    /** mazecrypto://coin/<id>, //home, //portfolio, //alerts — from widgets and notifications. */
    fun deepLink(uri: android.net.Uri?) {
        uri ?: return
        if (uri.scheme != "mazecrypto") return
        when (uri.host) {
            "coin" -> uri.lastPathSegment?.let { tab(Route.Home); go(Route.Chart(it)) }
            "portfolio" -> tab(Route.Portfolio())
            "alerts" -> tab(Route.Alerts)
            "plan" -> tab(Route.Plan)
            "prices" -> tab(Route.Prices())
            "home" -> tab(Route.Home)
        }
    }
}

/** (state, text) for the status pill. */
fun status(snap: Snapshot?, refreshing: Boolean, now: Long): Pair<String, String> {
    if (refreshing && snap == null) return "busy" to t("status.updating")
    if (snap == null) return "offline" to t("status.offline")
    if (refreshing) return "busy" to t("status.updating")
    val age = (now - snap.ts) / 1000.0
    return when {
        snap.offline -> "offline" to (snap.staleAge?.let { t("status.offline_cached", "age" to Fmt.age(it + age)) } ?: t("status.offline"))
        snap.staleAge != null -> "stale" to t("status.stale", "age" to Fmt.age(snap.staleAge + age))
        else -> "live" to t("status.live", "age" to Fmt.age(age))
    }
}

@Composable
fun MazeApp(nav: Nav) {
    val settings by Maze.settings.collectAsStateWithLifecycle()
    MazeTheme(settings.accent) {
        val p = LocalPalette.current
        Box(Modifier.fillMaxSize().background(p.bg)) {
            Glow()
            if (!settings.onboarded) {
                OnboardingScreen()
                return@Box
            }
            AutoRefresh()
            BackHandler(enabled = nav.stack.size > 1 || nav.top != Route.Home) { nav.back() }
            // Keyed on the language so every remembered string is rebuilt when it changes.
            key(settings.language) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).statusBarsPadding()) {
                    AnimatedContent(nav.top, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "screen") { r ->
                        Screen(r, nav)
                    }
                }
                if (nav.top.isTab()) BottomBar(nav)
            }
            }
        }
    }
}

private fun Route.isTab() = this is Route.Home || this is Route.Prices || this is Route.Portfolio || this is Route.Plan || this is Route.Alerts

@Composable
private fun Screen(r: Route, nav: Nav) {
    when (r) {
        Route.Home -> HomeScreen(nav)
        is Route.Prices -> PricesScreen(nav, r.search)
        is Route.Portfolio -> PortfolioScreen(nav, r.tab)
        Route.Plan -> PlanScreen(nav)
        Route.Alerts -> AlertsScreen(nav)
        is Route.Chart -> ChartScreen(nav, r.coinId)
        Route.Settings -> SettingsScreen(nav)
        Route.About -> AboutScreen(nav)
        Route.Insights -> InsightsScreen(nav)
        is Route.Trade -> TradeEditor(nav, r.id, r.coinId, r.side)
        Route.Targets -> TargetsEditor(nav)
        Route.DcaEdit -> DcaEditor(nav)
        is Route.RuleEdit -> RuleEditor(nav, r.id)
    }
}

/** Refresh on the user's interval while the app is in front; the widgets follow every refresh. */
@Composable
private fun AutoRefresh() {
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            launch { Maze.showCached() }
            while (true) {
                Maze.refresh()
                delay(Maze.settings.value.refreshSeconds * 1000L)
            }
        }
    }
}

/** A clock that ticks every few seconds, for "Live · 12s" labels. */
@Composable
fun rememberNow(periodMs: Long = 5000): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(periodMs) {
        while (true) {
            delay(periodMs)
            now = System.currentTimeMillis()
        }
    }
    return now
}

/** The faint light drifting behind the Maze apps' windows. */
@Composable
private fun Glow() {
    val p = LocalPalette.current
    Canvas(Modifier.fillMaxSize()) {
        drawCircle(Brush.radialGradient(listOf(p.glow.copy(alpha = 0.075f), Color.Transparent),
            center = Offset(size.width * 0.85f, size.height * 0.02f), radius = size.width * 0.9f),
            radius = size.width * 0.9f, center = Offset(size.width * 0.85f, size.height * 0.02f))
        drawCircle(Brush.radialGradient(listOf(p.glow2.copy(alpha = 0.035f), Color.Transparent),
            center = Offset(size.width * 0.1f, size.height * 0.75f), radius = size.width * 0.8f),
            radius = size.width * 0.8f, center = Offset(size.width * 0.1f, size.height * 0.75f))
    }
}

@Composable
private fun BottomBar(nav: Nav) {
    val p = LocalPalette.current
    val items = listOf(
        Triple<Route, Int, String>(Route.Home, R.drawable.ic_house, t("nav.home")),
        Triple(Route.Prices(), R.drawable.ic_list_bullets, t("nav.watchlist")),
        Triple(Route.Portfolio(), R.drawable.ic_wallet, t("nav.holdings")),
        Triple(Route.Plan, R.drawable.ic_calendar_check, t("m.nav.plan")),
        Triple(Route.Alerts, R.drawable.ic_bell, t("nav.rules")),
    )
    Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, p.bg.copy(alpha = 0.9f), p.bg)))) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.glassLine))
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceAround) {
            items.forEach { (route, icon, label) ->
                val on = route::class == nav.top::class
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { nav.tab(route) }.padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.clip(RoundedCornerShape(10.dp)).background(if (on) p.accent.copy(alpha = 0.14f) else Color.Transparent)
                        .padding(horizontal = 14.dp, vertical = 4.dp)) {
                        Ic(icon, tint = if (on) p.accent else p.muted, size = 21.dp)
                    }
                    Text(label, style = Type.small.copy(fontSize = 11.sp), color = if (on) p.text else p.muted, maxLines = 1,
                        modifier = Modifier.padding(top = 3.dp))
                }
            }
        }
    }
}

/** State a screen can hold across recompositions without a ViewModel. */
@Composable
fun <T> rememberState(initial: T) = remember { mutableStateOf(initial) }
