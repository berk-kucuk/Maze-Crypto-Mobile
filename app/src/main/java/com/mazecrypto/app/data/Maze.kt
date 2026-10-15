package com.mazecrypto.app.data

import android.content.Context
import com.mazecrypto.app.core.Alert
import com.mazecrypto.app.core.AdvisorInput
import com.mazecrypto.app.core.Coin
import com.mazecrypto.app.core.Fmt
import com.mazecrypto.app.core.I18n
import com.mazecrypto.app.core.Interval
import com.mazecrypto.app.core.OTHER
import com.mazecrypto.app.core.Position
import com.mazecrypto.app.core.PortfolioError
import com.mazecrypto.app.core.RuleContext
import com.mazecrypto.app.core.RuleState
import com.mazecrypto.app.core.Summary
import com.mazecrypto.app.core.TechState
import com.mazecrypto.app.core.Tip
import com.mazecrypto.app.core.TipFormatter
import com.mazecrypto.app.core.Trade
import com.mazecrypto.app.core.buildPositions
import com.mazecrypto.app.core.current
import com.mazecrypto.app.core.dataNeeds
import com.mazecrypto.app.core.evaluateRules
import com.mazecrypto.app.core.schedule
import com.mazecrypto.app.core.summarize
import com.mazecrypto.app.core.t
import com.mazecrypto.app.core.techState
import com.mazecrypto.app.widget.WidgetData
import com.mazecrypto.app.widget.Widgets
import com.mazecrypto.app.work.AlertNotifier
import com.mazecrypto.app.work.RefreshJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import kotlin.math.abs

/**
 * The app's single source of truth — the desktop's Controller without Qt.
 *
 * The screens, the widgets and the background job all go through here, so a
 * refresh from any of them updates every other: the open app, every widget on
 * the home screen and the alert rules.
 */
object Maze {
    lateinit var app: Context
        private set
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var settingsFile: File
    private lateinit var store: ProfileStore
    private lateinit var alertFile: File
    lateinit var cache: Cache
        private set

    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()
    private val _profile = MutableStateFlow(Profile())
    val profile: StateFlow<Profile> = _profile.asStateFlow()
    private val _snap = MutableStateFlow<Snapshot?>(null)
    val snapshot: StateFlow<Snapshot?> = _snap.asStateFlow()
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()
    private val _tips = MutableStateFlow<List<Tip>>(emptyList())
    val tips: StateFlow<List<Tip>> = _tips.asStateFlow()
    private val _alerts = MutableStateFlow<List<AlertEntry>>(emptyList())
    val alertLog: StateFlow<List<AlertEntry>> = _alerts.asStateFlow()
    private val _tech = MutableStateFlow<Map<String, TechState>>(emptyMap())

    @Volatile private var service: MarketService? = null
    private val refreshLock = Mutex()
    private var initialized = false

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        app = context.applicationContext
        I18n.init(app)
        settingsFile = File(app.filesDir, "settings.json")
        store = ProfileStore(File(app.filesDir, "profile.json"))
        alertFile = File(app.filesDir, "alerts.json")
        cache = Cache(File(app.cacheDir, "net"))
        val s = Settings.load(settingsFile)
        _settings.value = s
        I18n.setLanguage(s.language)
        _profile.value = store.load()
        _alerts.value = loadAlerts()
        initialized = true
    }

    fun service(): MarketService = service ?: MarketService(_settings.value, cache).also { service = it }

    // ── refresh ─────────────────────────────────────────────────────────────

    /** Watchlist plus every coin the portfolio, targets, plan or rules refer to. */
    fun trackedCoins(): List<Coin> {
        val s = _settings.value
        val p = _profile.value
        val coins = LinkedHashMap<String, Coin>()
        s.watchlist.forEach { coins[it.id] = it }
        p.trades.forEach { coins.getOrPut(it.coinId) { Coin(it.coinId, it.symbol, it.symbol) } }
        p.targetSymbols.forEach { (cid, sym) -> if (cid != OTHER) coins.getOrPut(cid) { Coin(cid, sym, sym) } }
        p.dcaPlan?.let { plan ->
            plan.symbols.forEach { (cid, sym) -> coins.getOrPut(cid) { Coin(cid, sym, sym) } }
            plan.extras.forEach { coins.getOrPut(it.coinId) { Coin(it.coinId, it.symbol, it.symbol) } }
        }
        p.rules.forEach { r ->
            val c = r.condition
            if (c.coinId.isNotEmpty() && c.symbol.isNotEmpty()) coins.getOrPut(c.coinId) { Coin(c.coinId, c.symbol, c.symbol) }
        }
        return coins.values.toList()
    }

    /** Paint the last known data immediately; the network refresh replaces it. */
    suspend fun showCached() = withContext(Dispatchers.Default) {
        if (_snap.value != null) return@withContext
        val snap = runCatching { service().refresh(trackedCoins(), cacheOnly = true) }.getOrNull() ?: return@withContext
        if (_snap.value == null && snap.rows.any { it.priceFiat != null }) {
            _snap.value = Snapshot(snap.rows, snap.fx, snap.ts, snap.staleAge ?: 0.0, false, snap.errors)
            updateTips()
        }
    }

    fun refreshAsync() {
        scope.launch { refresh() }
    }

    /**
     * Fetch prices, then update tips, widgets and alerts. [background] is the
     * scheduled job: it skips the optional daily-history pass to stay light.
     */
    suspend fun refresh(background: Boolean = false) = withContext(Dispatchers.Default) {
        // Parsing a few thousand tickers is real work: never on the main thread.
        if (!refreshLock.tryLock()) return@withContext
        _refreshing.value = true
        try {
            val svc = service()
            val snap = svc.refresh(trackedCoins())
            _snap.value = snap
            updateTips()
            // Wait for it: the background job may be the last thing this process does.
            publishWidgets()?.join()
            evaluateAlerts(svc)
            if (!background) enrich(svc, snap)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
        } finally {
            _refreshing.value = false
            refreshLock.unlock()
        }
    }

    /** RSI and trend for held coins from 31 daily closes (cached for hours), for the assistant. */
    private suspend fun enrich(svc: MarketService, snap: Snapshot) {
        val held = summary().holdings.map { it.position.coinId }.toSet()
        val out = HashMap(_tech.value)
        for (row in snap.rows) {
            if (row.coin.id !in held || row.coin.isStable) continue
            val cs = runCatching { svc.daily(row.coin, 31, row.priceUsd) }.getOrNull() ?: continue
            if (cs.size > 15) out[row.coin.id] = techState(cs.map { it.close })
        }
        _tech.value = out
        updateTips()
    }

    // ── settings ────────────────────────────────────────────────────────────

    fun updateSettings(transform: (Settings) -> Settings) {
        val old = _settings.value
        val new = transform(old).validated()
        Settings.save(settingsFile, new)
        _settings.value = new
        I18n.setLanguage(new.language)
        val rebuild = old.exchanges != new.exchanges || old.proxyEnabled != new.proxyEnabled ||
            old.proxyUrl != new.proxyUrl || old.fiat != new.fiat || old.secondaryFiat != new.secondaryFiat
        if (rebuild) {
            service = null
            _snap.value = null
        }
        if (old.backgroundMinutes != new.backgroundMinutes) RefreshJob.schedule(app)
        val coinsChanged = old.watchlist.map { it.id } != new.watchlist.map { it.id }
        if (rebuild || coinsChanged) refreshAsync() else {
            updateTips()
            publishWidgets()
        }
    }

    // ── portfolio ───────────────────────────────────────────────────────────

    private fun saveProfile(p: Profile) {
        store.save(p)
        _profile.value = p
        updateTips()
        publishWidgets()
    }

    fun updateProfile(transform: (Profile) -> Profile) = saveProfile(transform(_profile.value))

    fun prices(): Pair<Map<String, Double>, Map<String, Double>> {
        val fiat = HashMap<String, Double>()
        val usd = HashMap<String, Double>()
        _snap.value?.rows?.forEach { r ->
            r.priceFiat?.let { fiat[r.coin.id] = it }
            r.priceUsd?.let { usd[r.coin.id] = it }
        }
        return fiat to usd
    }

    fun fxRate(): Double? = _snap.value?.fx?.get(_settings.value.fiat)

    fun positions(trades: List<Trade> = _profile.value.trades): Map<String, Position> =
        try {
            buildPositions(trades, _settings.value.fiat, fxRate())
        } catch (e: PortfolioError) {
            emptyMap()
        }

    fun summary(): Summary {
        val (fiat, usd) = prices()
        return summarize(positions(), fiat, usd)
    }

    /** Add or replace a trade. Returns an error message key, or null when saved. */
    fun saveTrade(trade: Trade): String? {
        val checked = try {
            ProfileStore.checkTrade(trade)
        } catch (e: Exception) {
            return t("trade.err_invalid", "err" to (e.message ?: ""))
        }
        val p = _profile.value
        val isNew = checked.id == 0L
        val withId = if (isNew) checked.copy(id = p.nextTradeId) else checked
        val trades = if (isNew) p.trades + withId else p.trades.map { if (it.id == withId.id) withId else it }
        if (!ProfileStore.consistent(trades, _settings.value.fiat, fxRate())) return t("trade.err_position")
        val newCoin = _snap.value?.row(withId.coinId) == null
        saveProfile(p.copy(trades = trades, nextTradeId = if (isNew) p.nextTradeId + 1 else p.nextTradeId))
        if (newCoin) refreshAsync()       // a new coin needs a price
        return null
    }

    fun deleteTrade(id: Long): String? {
        val p = _profile.value
        val trades = p.trades.filter { it.id != id }
        if (!ProfileStore.consistent(trades, _settings.value.fiat, fxRate())) return t("trade.err_delete_breaks")
        saveProfile(p.copy(trades = trades))
        return null
    }

    fun importTrades(trades: List<Trade>): String? {
        val p = _profile.value
        var next = p.nextTradeId
        val added = trades.map { it.copy(id = next++) }
        val all = p.trades + added
        if (!ProfileStore.consistent(all, _settings.value.fiat, fxRate())) return t("trade.err_position")
        saveProfile(p.copy(trades = all, nextTradeId = next))
        refreshAsync()
        return null
    }

    // ── alerts ──────────────────────────────────────────────────────────────

    data class AlertEntry(val ruleId: String, val name: String, val text: String, val ts: Double)

    private fun loadAlerts(): List<AlertEntry> = runCatching {
        JSONArray(alertFile.readText()).objects().map {
            AlertEntry(it.optString("rule"), it.optString("name"), it.optString("text"), it.optDouble("ts"))
        }
    }.getOrElse { emptyList() }

    private fun saveAlerts(list: List<AlertEntry>) {
        runCatching {
            alertFile.writeText(JSONArray().apply {
                list.forEach { put(JSONObject().put("rule", it.ruleId).put("name", it.name).put("text", it.text).put("ts", it.ts)) }
            }.toString())
        }
    }

    fun coinName(coinId: String): String = trackedCoins().firstOrNull { it.id == coinId }?.symbol ?: coinId

    fun alertText(a: Alert): String {
        val parts = mutableListOf<String>()
        if (a.value != null) {
            val kind = a.rule.condition.kind
            val unit = if (kind == "drift") " pp" else if (kind == "rsi" || kind == "price") "" else "%"
            val subject = if (a.subject.isNotEmpty()) (if (a.subject == OTHER) t("targets.other") else coinName(a.subject)) else ""
            parts += (if (subject.isNotEmpty()) "$subject: " else "") + Fmt.num(a.value, 2) + unit
        }
        if (a.rule.message.isNotEmpty()) parts += a.rule.message
        return parts.joinToString(" — ").ifEmpty { a.rule.name }
    }

    private suspend fun evaluateAlerts(svc: MarketService) {
        val p = _profile.value
        val rules = p.rules
        if (rules.isEmpty() || _snap.value == null) return
        val (fiat, usd) = prices()
        val s = summarize(positions(), fiat, usd)
        val ctx = RuleContext(
            pricesFiat = fiat, pricesUsd = usd,
            avgCostFiat = s.holdings.associate { it.position.coinId to (it.position.avgCostFiat ?: 0.0) },
            weights = s.weights, targets = p.targets, fiat = _settings.value.fiat,
        )
        val coins = trackedCoins().associateBy { it.id }
        for ((cid, iv) in dataNeeds(rules)) {
            val coin = coins[cid] ?: continue
            runCatching {
                val cs = svc.candles(coin, "usd", Interval.of(iv), if (iv != "1h") 260 else 72, usd[cid])
                ctx.closes[cid to iv] = cs.candles.map { it.close }
            }
        }
        // Re-read: the user may have edited rules while candles were loading.
        val state = HashMap<String, RuleState>()
        _profile.value.ruleState.forEach { (k, v) -> state[k] = v.copy() }
        val fired = evaluateRules(rules, ctx, state, System.currentTimeMillis() / 1000.0)
        val cur = _profile.value
        store.save(cur.copy(ruleState = state))
        _profile.value = cur.copy(ruleState = state)
        if (fired.isNotEmpty()) {
            val entries = fired.map { AlertEntry(it.rule.id, it.rule.name, alertText(it), it.ts) }
            val log = (entries + _alerts.value).take(100)
            _alerts.value = log
            saveAlerts(log)
            if (_settings.value.notifications) fired.forEach { AlertNotifier.send(app, it.rule.name, alertText(it), it.rule.id) }
        }
    }

    fun saveRules(rules: List<com.mazecrypto.app.core.Rule>) {
        updateProfile { it.copy(rules = rules) }
        scope.launch { runCatching { evaluateAlerts(service()) } }
    }

    fun clearAlertLog() {
        _alerts.value = emptyList()
        saveAlerts(emptyList())
    }

    // ── assistant ───────────────────────────────────────────────────────────

    fun moneyFmt(fiat: String): (Double) -> String = { v -> Fmt.money(v, fiat, if (abs(v) >= 100) 0 else 2) }

    fun updateTips() {
        val s = summary()
        val p = _profile.value
        val st = _settings.value
        val plan = p.dcaPlan
        val cur = plan?.let {
            val values = s.holdings.associate { h -> h.position.coinId to (h.valueFiat ?: 0.0) }
            current(schedule(it, values, p.targets), p.dcaDone, LocalDate.now())
        }
        val names = HashMap<String, String>()
        trackedCoins().forEach { names[it.id] = it.symbol }
        names.putAll(p.targetSymbols)
        names[OTHER] = t("targets.other")
        val snap = _snap.value
        val x = AdvisorInput(
            fiat = st.fiat, rows = snap?.rows ?: emptyList(), watchlist = st.watchlist.map { it.id }, summary = s,
            trades = p.trades.size, tradesWithoutRate = p.trades.count { it.usdRate == null }, targets = p.targets,
            names = names, planAmount = plan?.amount, current = cur, planExists = plan != null, rules = p.rules.size,
            tech = _tech.value, offline = snap?.offline == true, staleAge = snap?.staleAge,
        )
        val f = TipFormatter(money = moneyFmt(st.fiat), pct = { Fmt.pct(it, 1) }, num = { Fmt.num(it, 1) })
        _tips.value = com.mazecrypto.app.core.tips(x, f)
    }

    // ── widgets ─────────────────────────────────────────────────────────────

    /** Write what the widgets draw and redraw every widget on the home screen. */
    fun publishWidgets(): Job? {
        if (!initialized) return null
        val s = _settings.value
        val snap = _snap.value
        val p = _profile.value
        val sum = summary()
        return scope.launch(widgetDispatcher) {
            val data = WidgetData.build(app, s, snap, sum, p)
            if (data != null) WidgetData.save(app, data)
            Widgets.updateAll(app)
        }
    }

    /** One at a time, in order: two quick changes must not race to write widget.json. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val widgetDispatcher = Dispatchers.Default.limitedParallelism(1)
}
