package com.mazecrypto.app.data

import com.mazecrypto.app.core.Coin
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * User preferences (the desktop's config.toml, as JSON in the app's files
 * directory). Portfolio data — trades, targets, rules, DCA plan — lives in
 * [ProfileStore], not here.
 */
data class Settings(
    val language: String = "en",
    val fiat: String = "USD",
    val secondaryFiat: String = "",
    val exchanges: List<String> = listOf("binance"),
    val watchlist: List<Coin> = emptyList(),
    /** Coins shown on the home-screen widgets, in this order. */
    val favorites: List<String> = emptyList(),
    /** How often prices refresh while the app is open. */
    val refreshSeconds: Int = 60,
    /** How often the widgets and alerts refresh in the background; 0 = never. */
    val backgroundMinutes: Int = 15,
    val notifications: Boolean = true,
    val chartInterval: String = "1d",
    val onboarded: Boolean = false,
    val expert: Boolean = false,
    val accent: String = "champagne",
    val proxyEnabled: Boolean = false,
    val proxyUrl: String = "socks5h://127.0.0.1:9050",
    /** Which change the widgets show: 1h, 24h or 7d. */
    val widgetChange: String = "24h",
    /** Hide money amounts on the portfolio widget (percentages only). */
    val widgetPrivate: Boolean = false,
    /**
     * Monero always goes through Tor: the SOCKS5 port of Orbot (or any Tor client) on
     * this host and port. There is no clearnet fallback.
     */
    val torHost: String = "127.0.0.1",
    val torPort: Int = 9050,
    /** A Monero node (host:port, an .onion address or https://…); "" = Maze's list of onion nodes. */
    val moneroNode: String = "",
    /** Mask every money amount in the app (shoulder-surfing, screen sharing); toggled from the dashboard. */
    val hideAmounts: Boolean = false,
    /** Block screenshots, screen recording and the recent-apps thumbnail for the whole app. */
    val secureScreen: Boolean = false,
    /** Read old Monero history on mobile data too (it can be gigabytes); off = Wi-Fi only. */
    val moneroMobileData: Boolean = false,
) {
    /** Favorites that are still on the watchlist; the first coins of the watchlist if none are starred. */
    fun widgetCoins(): List<Coin> {
        val byId = watchlist.associateBy { it.id }
        val favs = favorites.mapNotNull { byId[it] }
        return favs.ifEmpty { watchlist.take(6) }
    }

    fun validated(): Settings {
        var s = this
        if (s.language !in LANGUAGES) s = s.copy(language = "en")
        runCatching { V.fiat(s.fiat) }.onFailure { s = s.copy(fiat = "USD") }
        if (s.secondaryFiat.isNotEmpty() && (runCatching { V.fiat(s.secondaryFiat) }.isFailure || s.secondaryFiat == s.fiat)) {
            s = s.copy(secondaryFiat = "")
        }
        s = s.copy(
            refreshSeconds = s.refreshSeconds.coerceIn(15, 3600),
            backgroundMinutes = if (s.backgroundMinutes == 0) 0 else s.backgroundMinutes.coerceIn(15, 720),
            exchanges = s.exchanges.filter { it in EXCHANGES }.distinct(),
            watchlist = s.watchlist.distinctBy { it.id },
        )
        s = s.copy(favorites = s.favorites.distinct().filter { id -> s.watchlist.any { it.id == id } })
        if (s.chartInterval !in INTERVALS) s = s.copy(chartInterval = "1d")
        if (s.accent !in ACCENTS) s = s.copy(accent = "champagne")
        if (s.widgetChange !in listOf("1h", "24h", "7d")) s = s.copy(widgetChange = "24h")
        if (s.torPort !in 1..65535) s = s.copy(torPort = 9050)
        if (s.torHost.isBlank() || s.torHost.length > 253 || s.torHost.any { it.isWhitespace() || it == '/' }) s = s.copy(torHost = "127.0.0.1")
        if (s.moneroNode.length > 200) s = s.copy(moneroNode = "")
        return s
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("language", language); put("fiat", fiat); put("secondary_fiat", secondaryFiat)
        put("exchanges", JSONArray(exchanges)); put("favorites", JSONArray(favorites))
        put("refresh_seconds", refreshSeconds); put("background_minutes", backgroundMinutes)
        put("notifications", notifications); put("chart_interval", chartInterval); put("onboarded", onboarded)
        put("expert", expert); put("accent", accent)
        put("proxy", JSONObject().put("enabled", proxyEnabled).put("url", proxyUrl))
        put("widget_change", widgetChange); put("widget_private", widgetPrivate)
        put("tor", JSONObject().put("host", torHost).put("port", torPort)); put("monero_node", moneroNode)
        put("hide_amounts", hideAmounts); put("secure_screen", secureScreen); put("monero_mobile_data", moneroMobileData)
        put("watchlist", JSONArray().apply { watchlist.forEach { put(it.toJson()) } })
    }

    companion object {
        val LANGUAGES = listOf("en", "tr")
        val INTERVALS = listOf("1h", "4h", "1d", "1w")
        val ACCENTS = listOf("mono", "champagne", "gold", "violet")

        fun fromJson(o: JSONObject): Settings {
            val d = Settings()
            fun strings(a: JSONArray?) = a?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }
            val wl = o.optJSONArray("watchlist")?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    runCatching {
                        val c = a.getJSONObject(i)
                        Coin(V.coinId(c.getString("id")), V.symbol(c.getString("symbol")), c.optString("name").take(60))
                    }.getOrNull()
                }
            } ?: emptyList()
            val proxy = o.optJSONObject("proxy") ?: JSONObject()
            return Settings(
                language = o.optString("language", d.language),
                fiat = o.optString("fiat", d.fiat),
                secondaryFiat = o.optString("secondary_fiat", d.secondaryFiat),
                exchanges = strings(o.optJSONArray("exchanges")) ?: d.exchanges,
                watchlist = wl,
                favorites = strings(o.optJSONArray("favorites")) ?: emptyList(),
                refreshSeconds = o.optInt("refresh_seconds", d.refreshSeconds),
                backgroundMinutes = o.optInt("background_minutes", d.backgroundMinutes),
                notifications = o.optBoolean("notifications", d.notifications),
                chartInterval = o.optString("chart_interval", d.chartInterval),
                onboarded = o.optBoolean("onboarded", false),
                expert = o.optBoolean("expert", false),
                accent = o.optString("accent", d.accent),
                proxyEnabled = proxy.optBoolean("enabled", false),
                proxyUrl = proxy.optString("url", d.proxyUrl),
                widgetChange = o.optString("widget_change", d.widgetChange),
                widgetPrivate = o.optBoolean("widget_private", false),
                torHost = o.optJSONObject("tor")?.optString("host", d.torHost) ?: d.torHost,
                torPort = o.optJSONObject("tor")?.optInt("port", d.torPort) ?: d.torPort,
                moneroNode = o.optString("monero_node", ""),
                hideAmounts = o.optBoolean("hide_amounts", false),
                secureScreen = o.optBoolean("secure_screen", false),
                moneroMobileData = o.optBoolean("monero_mobile_data", false),
            ).validated()
        }

        fun load(file: File): Settings {
            if (!file.exists()) return Settings()
            return try {
                fromJson(JSONObject(file.readText()))
            } catch (e: Exception) {
                // A broken file must not stop the app; keep it for inspection.
                file.renameTo(File(file.parentFile, file.name + ".broken"))
                Settings()
            }
        }

        fun save(file: File, s: Settings) {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(s.validated().toJson().toString(2))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }
}
