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
import com.mazecrypto.app.core.Transfer
import com.mazecrypto.app.core.Wallet
import com.mazecrypto.app.core.WalletBalance
import com.mazecrypto.app.core.isSyntheticCoin
import com.mazecrypto.app.core.mergeActivity
import com.mazecrypto.app.core.mergeWallets
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    private lateinit var walletStore: WalletStore
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
    private val _wallets = MutableStateFlow<List<Wallet>>(emptyList())
    val wallets: StateFlow<List<Wallet>> = _wallets.asStateFlow()
    private val _walletData = MutableStateFlow<Map<String, WalletStatus>>(emptyMap())
    /** The last balance of every watched address, by wallet id. */
    val walletData: StateFlow<Map<String, WalletStatus>> = _walletData.asStateFlow()

    /** [staleAge] (seconds) when the balance is a cached one after a failed lookup; [error] says why. */
    data class WalletStatus(val balance: WalletBalance?, val staleAge: Double? = null, val error: String? = null, val loading: Boolean = false)

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
        walletStore = WalletStore(File(app.filesDir, "wallets.json"))
        cache = Cache(File(app.cacheDir, "net"))
        val s = Settings.load(settingsFile)
        _settings.value = s
        I18n.setLanguage(s.language)
        _profile.value = store.load()
        _alerts.value = loadAlerts()
        _wallets.value = walletStore.load()
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
        // Wallet coins: the chain's own coin and known tokens up front, so the first balance already has a price.
        val data = _walletData.value
        for (w in _wallets.value) {
            if (w.manual) {
                if (w.coinId.isNotEmpty()) coins.getOrPut(w.coinId) { Coin(w.coinId, w.symbol, w.name.ifEmpty { w.symbol }) }
                continue
            }
            Chains.of(w.chain)?.native?.let { c -> coins.getOrPut(c.id) { c } }
            data[w.id]?.balance?.assets?.forEach { a ->
                if (!isSyntheticCoin(a.coinId) && a.amount > 0) coins.getOrPut(a.coinId) { Coin(a.coinId, a.symbol, a.name) }
            }
        }
        return coins.values.toList()
    }

    /** Paint the last known data immediately; the network refresh replaces it. */
    suspend fun showCached() = withContext(Dispatchers.Default) {
        if (_snap.value != null) return@withContext
        if (_walletData.value.isEmpty()) runCatching { refreshWallets(service(), cacheOnly = true) }
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
            val snap = coroutineScope {
                // Balances and prices in parallel; they use different hosts. Prices are shown
                // as soon as they arrive: one slow explorer must not hold the whole screen back.
                val walletJob = async { runCatching { refreshWallets(svc) } }
                var s = svc.refresh(trackedCoins())
                _snap.value = s
                walletJob.await()
                // A wallet turned up a coin nobody had asked a price for yet.
                val have = s.rows.map { it.coin.id }.toSet()
                if (trackedCoins().any { it.id !in have }) s = svc.refresh(trackedCoins())
                s
            }
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

    // ── wallets ─────────────────────────────────────────────────────────────

    private fun walletTtl(): Double = maxOf(45.0, _settings.value.refreshSeconds - 5.0)

    /** Every watched address at once; one that fails keeps its last balance, marked stale. */
    private suspend fun refreshWallets(svc: MarketService, cacheOnly: Boolean = false) = coroutineScope {
        val list = _wallets.value.filter { !it.manual }
        list.map { w -> async { w.id to fetchWallet(svc, w, cacheOnly) } }.awaitAll().forEach { (id, st) ->
            if (st != null) _walletData.value = _walletData.value + (id to st)
        }
        _walletData.value = _walletData.value.filterKeys { id -> _wallets.value.any { it.id == id } }
    }

    private suspend fun fetchWallet(svc: MarketService, w: Wallet, cacheOnly: Boolean): WalletStatus? = try {
        val f = svc.wallets.balance(w, walletTtl(), cacheOnly)
        WalletStatus(f.value, f.staleAge, f.error)
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        if (cacheOnly) null else WalletStatus(_walletData.value[w.id]?.balance, error = e.message ?: e.javaClass.simpleName)
    }

    /** Look up one address now (after adding it, or from its screen), then refresh what depends on it. */
    fun refreshWalletAsync(id: String) {
        scope.launch {
            val w = _wallets.value.firstOrNull { it.id == id } ?: return@launch
            if (w.manual) return@launch
            _walletData.value = _walletData.value + (id to (_walletData.value[id]?.copy(loading = true) ?: WalletStatus(null, loading = true)))
            val st = fetchWallet(service(), w, cacheOnly = false)
            _walletData.value = _walletData.value + (id to (st ?: WalletStatus(null)))
            afterWalletChange()
        }
    }

    private fun afterWalletChange() {
        val have = _snap.value?.rows?.map { it.coin.id }?.toSet() ?: emptySet()
        if (trackedCoins().any { it.id !in have }) refreshAsync() else {
            updateTips()
            publishWidgets()
        }
    }

    private fun saveWallets(list: List<Wallet>) {
        walletStore.save(list)
        _wallets.value = list
        _walletData.value = _walletData.value.filterKeys { id -> list.any { it.id == id } }
    }

    /**
     * Add watched addresses (one per network for an EVM address on several).
     * Returns an error message, or null when saved. Duplicates are refused.
     */
    fun addWallets(new: List<Wallet>): String? {
        val cur = _wallets.value
        if (cur.size + new.size > WalletStore.MAX_WALLETS) return t("m.wal.err_too_many", "n" to WalletStore.MAX_WALLETS)
        val taken = cur.filter { !it.manual }.map { com.mazecrypto.app.core.Address.key(it.chain, it.address) }.toSet()
        val dup = new.firstOrNull { !it.manual && com.mazecrypto.app.core.Address.key(it.chain, it.address) in taken }
        if (dup != null) return t("m.wal.err_duplicate", "chain" to (Chains.of(dup.chain)?.name ?: dup.chain))
        saveWallets(cur + new)
        new.forEach { if (it.manual) Unit else refreshWalletAsync(it.id) }
        afterWalletChange()
        return null
    }

    fun updateWallet(w: Wallet) {
        saveWallets(_wallets.value.map { if (it.id == w.id) w else it })
        afterWalletChange()
    }

    fun deleteWallet(id: String) {
        saveWallets(_wallets.value.filter { it.id != id })
        updateTips()
        publishWidgets()
    }

    /** Coin id -> (symbol, total amount) over the wallets that count toward the portfolio. */
    fun walletHoldings(): Map<String, Pair<String, Double>> {
        val out = LinkedHashMap<String, Pair<String, Double>>()
        val data = _walletData.value
        for (w in _wallets.value) {
            if (!w.inPortfolio) continue
            val assets = if (w.manual) {
                if (w.coinId.isEmpty() || (w.amount ?: 0.0) <= 0) emptyList() else listOf(w.coinId to (w.symbol to (w.amount ?: 0.0)))
            } else data[w.id]?.balance?.assets?.filter { it.amount > 0 }?.map { it.coinId to (it.symbol to it.amount) } ?: emptyList()
            for ((cid, sa) in assets) {
                val prev = out[cid]
                out[cid] = sa.first to ((prev?.second ?: 0.0) + sa.second)
            }
        }
        return out
    }

    /** USD prices the indexers gave for tokens CoinGecko is not asked about. */
    private fun syntheticPrices(): Map<String, Double> {
        val out = HashMap<String, Double>()
        _walletData.value.values.forEach { st ->
            st.balance?.assets?.forEach { a -> if (isSyntheticCoin(a.coinId)) a.priceUsd?.let { out[a.coinId] = it } }
        }
        return out
    }

    /** History of [ids] (all wallets when empty), merged so transfers between them show once. */
    suspend fun walletActivity(ids: Collection<String> = emptyList()): Pair<List<Transfer>, List<String>> = withContext(Dispatchers.Default) {
        val svc = service()
        val list = _wallets.value.filter { !it.manual && (ids.isEmpty() || it.id in ids) }
        val errors = mutableListOf<String>()
        val parts = coroutineScope {
            list.map { w ->
                async {
                    try {
                        val f = svc.wallets.activity(w)
                        f.error?.let { synchronized(errors) { errors += it } }
                        // A token the balance check did not accept (unpriced, flagged, dust) is spam in the history too.
                        val accepted = _walletData.value[w.id]?.balance?.assets?.map { it.coinId }?.toSet() ?: emptySet()
                        f.value.map { tr -> tr.copy(legs = tr.legs.filter { !isSyntheticCoin(it.coinId) || it.coinId in accepted }) }
                            .filter { it.legs.isNotEmpty() || it.fee != null }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        synchronized(errors) { errors += (e.message ?: e.javaClass.simpleName) }
                        emptyList()
                    }
                }
            }.awaitAll()
        }
        mergeActivity(parts) to errors
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
        val rate = fxRate()
        syntheticPrices().forEach { (cid, p) ->
            usd[cid] = p
            if (rate != null) fiat[cid] = p * rate
        }
        return fiat to usd
    }

    fun fxRate(): Double? = _snap.value?.fx?.get(_settings.value.fiat)

    /** Positions from the trades, with amounts taken from the wallets for every coin held in one. */
    fun positions(trades: List<Trade> = _profile.value.trades): Map<String, Position> {
        val fromTrades = try {
            buildPositions(trades, _settings.value.fiat, fxRate())
        } catch (e: PortfolioError) {
            emptyMap()
        }
        val held = walletHoldings()
        return if (held.isEmpty()) fromTrades else mergeWallets(fromTrades, held)
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

    fun coinName(coinId: String): String = trackedCoins().firstOrNull { it.id == coinId }?.symbol
        ?: walletHoldings()[coinId]?.first ?: coinId

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
            trades = p.trades.size, wallets = _wallets.value.size, tradesWithoutRate = p.trades.count { it.usdRate == null }, targets = p.targets,
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
