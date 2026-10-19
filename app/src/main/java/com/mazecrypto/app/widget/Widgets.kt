package com.mazecrypto.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import com.mazecrypto.app.MainActivity
import com.mazecrypto.app.R
import com.mazecrypto.app.core.Fmt
import com.mazecrypto.app.core.t
import com.mazecrypto.app.data.Maze
import com.mazecrypto.app.work.RefreshJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.min

/** One layout, and the smallest size (in dp) it was measured to fit — see tools/gen_widget_layouts.py. */
data class SizedLayout<T>(val key: T, val widthDp: Float, val heightDp: Float)

/**
 * Picks a layout for the size a widget actually has, instead of assuming one
 * (the approach Maze Connect's widgets settled on).
 *
 * * API 31+: a size-mapped RemoteViews; the launcher picks per orientation
 *   and re-picks on resize with no round trip through the app.
 * * API 26-30: the same choice made here from the sizes in the widget's
 *   options, as a landscape/portrait pair.
 */
object WidgetSizing {
    /** The platform's best-fit rule: the closest layout that fits, else the smallest. */
    fun <T> pick(widthDp: Float, heightDp: Float, layouts: List<SizedLayout<T>>): SizedLayout<T> {
        val fitting = layouts.filter { it.widthDp <= widthDp && it.heightDp <= heightDp }
        if (fitting.isEmpty()) return layouts.minBy { it.widthDp * it.heightDp }
        return fitting.minBy {
            val dw = widthDp - it.widthDp
            val dh = heightDp - it.heightDp
            dw * dw + dh * dh
        }
    }

    fun <T> build(ctx: Context, id: Int, layouts: List<SizedLayout<T>>, fallback: T, make: (SizedLayout<T>) -> RemoteViews): RemoteViews {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return RemoteViews(layouts.associate { SizeF(it.widthDp, it.heightDp) to make(it) })
        }
        val o = runCatching { AppWidgetManager.getInstance(ctx).getAppWidgetOptions(id) }.getOrNull()
        val fb = layouts.first { it.key == fallback }
        if (o == null) return make(fb)
        val minW = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
        val maxW = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)
        val minH = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
        val maxH = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
        if (minW <= 0 || maxH <= 0) return make(fb)
        // Portrait is narrow and tall (min width, max height), landscape wide and short.
        val portrait = pick(minW.toFloat(), maxH.toFloat(), layouts)
        val landscape = pick(maxW.toFloat(), minH.toFloat(), layouts)
        return if (portrait == landscape) make(portrait) else RemoteViews(make(landscape), make(portrait))
    }
}

/** Colours of the widget palette (res/values/colors.xml). */
private object WC {
    const val TEXT = 0xFFEDEDF0.toInt()
    const val MID = 0xFFA1A1AA.toInt()
    const val DIM = 0xFF71717A.toInt()
    const val UP = 0xFF34D399.toInt()
    const val DOWN = 0xFFF87171.toInt()
    const val WARN = 0xFFFB923C.toInt()
    val SERIES = intArrayOf(0xFFE9D8B4.toInt(), 0xFF2DD4BF.toInt(), 0xFFA78BFA.toInt(), 0xFF93C5FD.toInt(), 0xFF52525B.toInt())

    fun change(v: Double?) = when {
        v == null || abs(v) < 0.005 -> MID
        v > 0 -> UP
        else -> DOWN
    }
}

object Widgets {
    private const val PREFS = "widgets"
    private const val STALE_MS = 60 * 60 * 1000L          // older than this: the time turns orange
    private const val BUSY_MS = 45 * 1000L

    // ── shared bits ────────────────────────────────────────────────────────

    fun updateAll(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx)
        val data = WidgetData.load(ctx)
        for ((cls, render) in RENDERERS) {
            for (id in mgr.getAppWidgetIds(ComponentName(ctx, cls))) {
                runCatching { mgr.updateAppWidget(id, render(ctx, id, data)) }
            }
        }
    }

    private val RENDERERS: List<Pair<Class<*>, (Context, Int, WidgetData?) -> RemoteViews>> = listOf(
        FavoritesWidget::class.java to ::favorites,
        GridWidget::class.java to ::grid,
        CoinWidget::class.java to ::coin,
        MoversWidget::class.java to ::movers,
        PortfolioWidget::class.java to ::portfolio,
        HoldingsWidget::class.java to ::holdings,
        PlanWidget::class.java to ::plan,
    )

    fun hasAny(ctx: Context): Boolean {
        val mgr = AppWidgetManager.getInstance(ctx)
        return RENDERERS.any { mgr.getAppWidgetIds(ComponentName(ctx, it.first)).isNotEmpty() }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun markBusy(ctx: Context) = prefs(ctx).edit().putLong("busy", System.currentTimeMillis()).apply()
    fun clearBusy(ctx: Context) = prefs(ctx).edit().remove("busy").apply()
    private fun busy(ctx: Context) = System.currentTimeMillis() - prefs(ctx).getLong("busy", 0) < BUSY_MS

    fun coinFor(ctx: Context, widgetId: Int): String? = prefs(ctx).getString("coin_$widgetId", null)
    fun setCoin(ctx: Context, widgetId: Int, coinId: String) = prefs(ctx).edit().putString("coin_$widgetId", coinId).apply()
    fun forget(ctx: Context, ids: IntArray) = prefs(ctx).edit().apply { ids.forEach { remove("coin_$it") } }.apply()

    private fun openApp(ctx: Context, uri: String, code: Int): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse(uri))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(ctx, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun refreshIntent(ctx: Context): PendingIntent =
        PendingIntent.getBroadcast(ctx, 0, Intent(ctx, WidgetRefreshReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** "14:05", "…" while refreshing; orange when the prices are old or offline. */
    private fun RemoteViews.updated(ctx: Context, d: WidgetData?, id: Int = R.id.w_updated) {
        val text = when {
            busy(ctx) -> "…"
            d == null || d.ts == 0L -> ""
            else -> Fmt.clock(d.ts)
        }
        setTextViewText(id, text)
        val old = d != null && d.ts > 0 && (d.offline || System.currentTimeMillis() - d.ts > STALE_MS)
        setTextColor(id, if (old) WC.WARN else WC.DIM)
    }

    private fun price(d: WidgetData, v: Double?, short: Boolean = false): String =
        when {
            short -> Fmt.moneyShort(v, d.fiat)
            // Cents on a five-digit price are noise at widget size.
            v != null && abs(v) >= 10_000 -> Fmt.money(v, d.fiat, 0)
            else -> Fmt.money(v, d.fiat)
        }

    private fun pct(v: Double?): String = Fmt.pct(v, if (v != null && abs(v) >= 100) 0 else 2)

    // ── sparkline and allocation bitmaps ───────────────────────────────────

    private fun scale(ctx: Context) = min(ctx.resources.displayMetrics.density, 2f)

    /**
     * The 7-day line, coloured by its direction. Drawn at no more than 2x
     * density: RemoteViews carry their bitmaps across processes, and the
     * launcher refuses an update that carries too many pixels.
     */
    fun spark(ctx: Context, xs: List<Double>, wDp: Int, hDp: Int, fill: Boolean = true): Bitmap? {
        if (xs.size < 2) return null
        val s = scale(ctx)
        val w = (wDp * s).toInt().coerceAtLeast(8)
        val h = (hDp * s).toInt().coerceAtLeast(4)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        // A floor of 1% of the price: a stablecoin's flat line stays flat instead of magnifying noise.
        val span = maxOf(xs.max() - xs.min(), abs(xs.average()) * 0.01, 1e-12)
        val lo = (xs.max() + xs.min()) / 2 - span / 2
        val pad = 1.2f * s
        val color = if (xs.last() >= xs.first()) WC.UP else WC.DOWN
        val path = Path()
        xs.forEachIndexed { i, v ->
            val x = i.toFloat() / (xs.size - 1) * (w - 1)
            val y = (pad + (1 - (v - lo) / span) * (h - 2 * pad)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        if (fill) {
            val area = Path(path).apply { lineTo(w.toFloat(), h.toFloat()); lineTo(0f, h.toFloat()); close() }
            c.drawPath(area, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(0f, 0f, 0f, h.toFloat(), (color and 0x00FFFFFF) or 0x33000000, color and 0x00FFFFFF, Shader.TileMode.CLAMP)
            })
        }
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1.3f * s; this.color = color
            strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
        })
        return bmp
    }

    private fun bar(ctx: Context, weights: List<Double>, wDp: Int, hDp: Int): Bitmap {
        val s = scale(ctx)
        val w = (wDp * s).toInt()
        val h = (hDp * s).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = h / 2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val gap = 1.5f * s
        var x = 0f
        val shown = weights.take(3)
        val parts = shown + listOf((100.0 - shown.sum()).coerceAtLeast(0.0))
        parts.forEachIndexed { i, wt ->
            if (wt <= 0.0) return@forEachIndexed
            val len = (wt / 100.0 * w).toFloat()
            p.color = if (i < shown.size) WC.SERIES[i] else WC.SERIES[4]
            c.drawRoundRect(x, 0f, (x + len - gap).coerceAtLeast(x + 1), h.toFloat(), r, r, p)
            x += len
        }
        return bmp
    }

    // ── favorites ──────────────────────────────────────────────────────────

    private sealed interface Fav {
        data class Strip(val cells: Int) : Fav
        data class List(val rows: Int) : Fav
        data class Narrow(val rows: Int) : Fav
    }

    private val FAV_LAYOUTS: kotlin.collections.List<SizedLayout<Fav>> = buildList {
        add(SizedLayout(Fav.Strip(1), 100f, 40f))
        add(SizedLayout(Fav.Strip(2), 150f, 40f))
        add(SizedLayout(Fav.Strip(3), 200f, 40f))
        add(SizedLayout(Fav.Strip(4), 280f, 40f))
        for (n in 2..8) add(SizedLayout(Fav.List(n), 236f, 36f + 21.2f * n))
        // At most 16 sizes in all: Android 12's limit for one size-mapped widget.
        for ((n, h) in listOf(2 to 108f, 3 to 144f, 4 to 181f, 6 to 253f)) add(SizedLayout(Fav.Narrow(n), 100f, h))
    }

    fun favorites(ctx: Context, id: Int, d: WidgetData?): RemoteViews {
        // Bitmaps are drawn once and shared by every size variant.
        val coins = d?.favoriteCoins() ?: emptyList()
        val sparks = coins.take(8).map { spark(ctx, it.spark, 64, 14) }
        return WidgetSizing.build(ctx, id, FAV_LAYOUTS, Fav.List(6)) { sized ->
            when (val k = sized.key) {
                is Fav.Strip -> favStrip(ctx, d, coins, k.cells)
                is Fav.List -> favList(ctx, d, coins, sparks, k.rows)
                is Fav.Narrow -> favNarrow(ctx, d, coins, k.rows)
            }
        }
    }

    private val SYM = intArrayOf(R.id.sym_1, R.id.sym_2, R.id.sym_3, R.id.sym_4, R.id.sym_5, R.id.sym_6, R.id.sym_7, R.id.sym_8)
    private val PRICE = intArrayOf(R.id.price_1, R.id.price_2, R.id.price_3, R.id.price_4, R.id.price_5, R.id.price_6, R.id.price_7, R.id.price_8)
    private val CHG = intArrayOf(R.id.chg_1, R.id.chg_2, R.id.chg_3, R.id.chg_4, R.id.chg_5, R.id.chg_6, R.id.chg_7, R.id.chg_8)
    private val ROW = intArrayOf(R.id.row_1, R.id.row_2, R.id.row_3, R.id.row_4, R.id.row_5, R.id.row_6, R.id.row_7, R.id.row_8)
    private val SPARK = intArrayOf(R.id.spark_1, R.id.spark_2, R.id.spark_3, R.id.spark_4, R.id.spark_5, R.id.spark_6, R.id.spark_7, R.id.spark_8)

    private fun RemoteViews.emptyState(ctx: Context, d: WidgetData?, shown: Int) {
        if (shown > 0) {
            setViewVisibility(R.id.w_empty, View.GONE)
            return
        }
        setViewVisibility(R.id.w_empty, View.VISIBLE)
        setTextViewText(R.id.w_empty, t(if (d == null) "widget.empty_nodata" else "widget.empty_fav"))
    }

    private fun RemoteViews.coinRows(ctx: Context, d: WidgetData?, coins: List<WidgetData.WCoin>, slots: Int, rows: Int, short: Boolean) {
        val period = d?.change ?: "24h"
        for (i in 0 until slots) {
            val c = coins.getOrNull(i)
            if (c == null || i >= rows || d == null) {
                setViewVisibility(ROW[i], View.GONE)
                continue
            }
            setViewVisibility(ROW[i], View.VISIBLE)
            setTextViewText(SYM[i], c.sym)
            setTextViewText(PRICE[i], price(d, c.price, short))
            val ch = c.change(period)
            setTextViewText(CHG[i], pct(ch))
            setTextColor(CHG[i], WC.change(ch))
            setOnClickPendingIntent(ROW[i], openApp(ctx, "mazecrypto://coin/${c.id}", 100 + i))
        }
    }

    private fun favList(ctx: Context, d: WidgetData?, coins: List<WidgetData.WCoin>, sparks: List<Bitmap?>, rows: Int): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_fav_list)
        v.setOnClickPendingIntent(android.R.id.background, openApp(ctx, "mazecrypto://home", 1))
        v.setTextViewText(R.id.w_title, t("widget.favorites"))
        v.updated(ctx, d)
        v.setOnClickPendingIntent(R.id.w_refresh, refreshIntent(ctx))
        v.coinRows(ctx, d, coins, 8, rows, short = false)
        for (i in 0 until min(rows, 8)) {
            val b = sparks.getOrNull(i)
            if (b != null) v.setImageViewBitmap(SPARK[i], b) else v.setImageViewResource(SPARK[i], android.R.color.transparent)
        }
        v.emptyState(ctx, d, min(rows, coins.size))
        return v
    }

    private fun favNarrow(ctx: Context, d: WidgetData?, coins: List<WidgetData.WCoin>, rows: Int): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_fav_narrow)
        v.setOnClickPendingIntent(android.R.id.background, openApp(ctx, "mazecrypto://home", 1))
        v.updated(ctx, d)
        v.setOnClickPendingIntent(R.id.w_refresh, refreshIntent(ctx))
        v.coinRows(ctx, d, coins, 6, rows, short = true)
        v.emptyState(ctx, d, min(rows, coins.size))
        return v
    }

    private fun favStrip(ctx: Context, d: WidgetData?, coins: List<WidgetData.WCoin>, cells: Int): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_fav_strip)
        v.setOnClickPendingIntent(android.R.id.background, openApp(ctx, "mazecrypto://home", 1))
        v.coinRows(ctx, d, coins, 4, cells, short = true)
        // No room for a clock: old prices are dimmed instead.
        val old = d != null && (d.offline || System.currentTimeMillis() - d.ts > STALE_MS)
        for (i in 0 until 4) v.setTextColor(PRICE[i], if (old) WC.MID else WC.TEXT)
        v.emptyState(ctx, d, min(cells, coins.size))
        return v
    }

    // ── one coin ───────────────────────────────────────────────────────────

    private enum class One { SMALL, WIDE, TALL, LARGE }

    private val COIN_LAYOUTS = listOf(
        SizedLayout(One.SMALL, 100f, 48f),
        SizedLayout(One.WIDE, 180f, 44f),
        SizedLayout(One.TALL, 100f, 96f),
        SizedLayout(One.LARGE, 220f, 130f),
    )

    fun coin(ctx: Context, id: Int, d: WidgetData?): RemoteViews {
        val chosen = coinFor(ctx, id)
        val c = d?.let { data -> chosen?.let { data.coin(it) } ?: data.favoriteCoins().firstOrNull() ?: data.coins.firstOrNull() }
        val wideSpark = c?.let { spark(ctx, it.spark, 110, 24) }
        val tallSpark = c?.let { spark(ctx, it.spark, 120, 40) }
        val largeSpark = c?.let { spark(ctx, it.spark, 220, 56) }
        return WidgetSizing.build(ctx, id, COIN_LAYOUTS, One.SMALL) { sized ->
            val layout = when (sized.key) {
                One.SMALL -> R.layout.widget_coin_small
                One.WIDE -> R.layout.widget_coin_wide
                One.TALL -> R.layout.widget_coin_tall
                One.LARGE -> R.layout.widget_coin_large
            }
            val v = RemoteViews(ctx.packageName, layout)
            val uri = if (c != null) "mazecrypto://coin/${c.id}" else "mazecrypto://home"
            v.setOnClickPendingIntent(android.R.id.background, openApp(ctx, uri, 200 + id % 1000))
            if (c == null) {
                v.setTextViewText(R.id.c_sym, t("widget.coin"))
                v.setTextViewText(R.id.c_price, Fmt.DASH)
                v.setTextViewText(R.id.c_chg, "")
            } else {
                v.setTextViewText(R.id.c_sym, c.sym)
                val ch = c.change(d.change)
                v.setTextViewText(R.id.c_chg, pct(ch))
                v.setTextColor(R.id.c_chg, WC.change(ch))
                v.setTextViewText(R.id.c_price, price(d, c.price, short = sized.key == One.SMALL))
                when (sized.key) {
                    One.WIDE -> {
                        v.setTextViewText(R.id.c_name, c.name)
                        wideSpark?.let { v.setImageViewBitmap(R.id.c_spark, it) }
                    }
                    One.TALL -> {
                        v.setTextViewText(R.id.c_name, c.name)
                        tallSpark?.let { v.setImageViewBitmap(R.id.c_spark, it) }
                        v.updated(ctx, d)
                    }
                    One.LARGE -> {
                        v.setTextViewText(R.id.c_name, c.name)
                        largeSpark?.let { v.setImageViewBitmap(R.id.c_spark, it) }
                        v.updated(ctx, d)
                        v.setOnClickPendingIntent(R.id.w_refresh, refreshIntent(ctx))
                        v.setTextViewText(R.id.c_hi_label, t("m.chart.high24"))
                        v.setTextViewText(R.id.c_lo_label, t("m.chart.low24"))
                        v.setTextViewText(R.id.c_7d_label, t("col.7d"))
                        v.setTextViewText(R.id.c_30d_label, t("col.30d"))
                        v.setTextViewText(R.id.c_hi, price(d, c.high24, short = true))
                        v.setTextViewText(R.id.c_lo, price(d, c.low24, short = true))
                        v.setTextViewText(R.id.c_7d, Fmt.pct(c.ch7d, 1))
                        v.setTextColor(R.id.c_7d, WC.change(c.ch7d))
                        v.setTextViewText(R.id.c_30d, Fmt.pct(c.ch30d, 1))
                        v.setTextColor(R.id.c_30d, WC.change(c.ch30d))
                    }
                    One.SMALL -> {}
                }
            }
            v
        }
    }

    // ── portfolio ──────────────────────────────────────────────────────────

    private enum class Pf { SMALL, SMALL_PNL, WIDE, WIDE_XL, TALL }

    private val PF_LAYOUTS = listOf(
        SizedLayout(Pf.SMALL, 100f, 48f),
        SizedLayout(Pf.SMALL_PNL, 100f, 70f),
        SizedLayout(Pf.WIDE, 200f, 44f),
        SizedLayout(Pf.WIDE_XL, 290f, 44f),
        SizedLayout(Pf.TALL, 100f, 116f),
    )

    fun portfolio(ctx: Context, id: Int, d: WidgetData?): RemoteViews {
        val p = d?.portfolio
        val barBmp = p?.let { bar(ctx, it.top.map { t -> t.second }, 140, 6) }
        return WidgetSizing.build(ctx, id, PF_LAYOUTS, Pf.SMALL) { sized ->
            val layout = when (sized.key) {
                Pf.SMALL, Pf.SMALL_PNL -> R.layout.widget_pf_small
                Pf.WIDE, Pf.WIDE_XL -> R.layout.widget_pf_wide
                Pf.TALL -> R.layout.widget_pf_tall
            }
            val v = RemoteViews(ctx.packageName, layout)
            v.setOnClickPendingIntent(android.R.id.background, openApp(ctx, "mazecrypto://portfolio", 3))
            v.setTextViewText(R.id.p_label, t("widget.portfolio"))
            if (d == null || p == null) {
                v.setTextViewText(R.id.p_value, Fmt.DASH)
                v.setTextViewText(R.id.p_day, if (d == null) "" else t("widget.no_trades"))
                v.setTextColor(R.id.p_day, WC.DIM)
                if (sized.key != Pf.SMALL && sized.key != Pf.SMALL_PNL) {
                    v.setTextViewText(R.id.p_pnl, "")
                    v.setTextViewText(R.id.p_day_label, "24h")
                    v.setTextViewText(R.id.p_pnl_label, t("hold.pnl"))
                }
                if (sized.key == Pf.TALL) {
                    v.setViewVisibility(R.id.p_bar, View.GONE)
                    v.setTextViewText(R.id.p_legend, "")
                    v.updated(ctx, d)
                }
                return@build v
            }
            val hide = d.private
            val signed = { x: Double? -> if (hide) "" else Fmt.signedMoney(x, d.fiat, 0) }
            v.setTextViewText(R.id.p_value, if (hide) "••••••" else Fmt.money(p.value, d.fiat, if (p.value >= 1000) 0 else 2))
            v.setTextColor(R.id.p_day, WC.change(p.dayPct))
            when (sized.key) {
                Pf.SMALL -> v.setTextViewText(R.id.p_day, pct(p.dayPct))
                Pf.SMALL_PNL -> {
                    v.setTextViewText(R.id.p_day, pct(p.dayPct))
                    v.setViewVisibility(R.id.p_sub, View.VISIBLE)
                    v.setTextViewText(R.id.p_sub, "${t("hold.pnl")} ${if (hide) "" else signed(p.pnl) + " · "}${pct(p.pnlPct)}")
                    v.setTextColor(R.id.p_sub, WC.change(p.pnlPct))
                }
                Pf.WIDE, Pf.WIDE_XL -> {
                    v.setTextViewText(R.id.p_day_label, "24h")
                    v.setTextViewText(R.id.p_pnl_label, t("hold.pnl"))
                    val xl = sized.key == Pf.WIDE_XL && !hide
                    v.setTextViewText(R.id.p_day, if (xl) "${signed(p.day)} · ${pct(p.dayPct)}" else pct(p.dayPct))
                    v.setTextViewText(R.id.p_pnl, if (xl) "${signed(p.pnl)} · ${pct(p.pnlPct)}" else pct(p.pnlPct))
                    v.setTextColor(R.id.p_pnl, WC.change(p.pnlPct))
                }
                Pf.TALL -> {
                    v.setTextViewText(R.id.p_day_label, "24h")
                    v.setTextViewText(R.id.p_pnl_label, t("hold.pnl"))
                    v.setTextViewText(R.id.p_day, pct(p.dayPct))
                    v.setTextViewText(R.id.p_pnl, pct(p.pnlPct))
                    v.setTextColor(R.id.p_pnl, WC.change(p.pnlPct))
                    v.setViewVisibility(R.id.p_bar, View.VISIBLE)
                    barBmp?.let { v.setImageViewBitmap(R.id.p_bar, it) }
                    v.setTextViewText(R.id.p_legend, p.top.take(3).joinToString(" · ") { "${it.first} ${Fmt.num(it.second, 0)}%" })
                    v.updated(ctx, d)
                }
            }
            v
        }
    }
    // ── tiles ──────────────────────────────────────────────────────────────

    private data class Grid(val cols: Int, val rows: Int)

    // 16 sizes: every column/row count from 1 to 4.
    private val GRID_LAYOUTS = (1..4).flatMap { c -> (1..4).map { r ->
        SizedLayout(Grid(c, r), 24f + 70f * c + 6f * (c - 1), 20f + 46f * r + 6f * (r - 1))
    } }

    private val TILE = intArrayOf(R.id.tile_1, R.id.tile_2, R.id.tile_3, R.id.tile_4, R.id.tile_5, R.id.tile_6, R.id.tile_7, R.id.tile_8,
        R.id.tile_9, R.id.tile_10, R.id.tile_11, R.id.tile_12, R.id.tile_13, R.id.tile_14, R.id.tile_15, R.id.tile_16)
    private val TSYM = intArrayOf(R.id.tsym_1, R.id.tsym_2, R.id.tsym_3, R.id.tsym_4, R.id.tsym_5, R.id.tsym_6, R.id.tsym_7, R.id.tsym_8,
        R.id.tsym_9, R.id.tsym_10, R.id.tsym_11, R.id.tsym_12, R.id.tsym_13, R.id.tsym_14, R.id.tsym_15, R.id.tsym_16)
    private val TPRICE = intArrayOf(R.id.tprice_1, R.id.tprice_2, R.id.tprice_3, R.id.tprice_4, R.id.tprice_5, R.id.tprice_6, R.id.tprice_7,
        R.id.tprice_8, R.id.tprice_9, R.id.tprice_10, R.id.tprice_11, R.id.tprice_12, R.id.tprice_13, R.id.tprice_14, R.id.tprice_15, R.id.tprice_16)
    private val TCHG = intArrayOf(R.id.tchg_1, R.id.tchg_2, R.id.tchg_3, R.id.tchg_4, R.id.tchg_5, R.id.tchg_6, R.id.tchg_7, R.id.tchg_8,
        R.id.tchg_9, R.id.tchg_10, R.id.tchg_11, R.id.tchg_12, R.id.tchg_13, R.id.tchg_14, R.id.tchg_15, R.id.tchg_16)
    private val TROW = intArrayOf(R.id.trow_1, R.id.trow_2, R.id.trow_3, R.id.trow_4)

    fun grid(ctx: Context, id: Int, d: WidgetData?): RemoteViews {
        val coins = d?.favoriteCoins() ?: emptyList()
        return WidgetSizing.build(ctx, id, GRID_LAYOUTS, Grid(3, 2)) { sized ->
            val (maxCols, rows) = sized.key
            val v = RemoteViews(ctx.packageName, R.layout.widget_grid)
            v.setOnClickPendingIntent(android.R.id.background, openApp(ctx, "mazecrypto://home", 1))
            // Fewer coins than tiles: pick the arrangement whose tiles come closest to a
            // 1.4 : 1 card with the fewest empty slots (4 coins in a 4x2 space become 2x2),
            // instead of stretching one row or stacking one column.
            val n = minOf(coins.size, maxCols * rows)
            val best = if (n == 0) null else (1..maxCols).flatMap { c -> (1..rows).map { r -> c to r } }
                .filter { (c, r) -> c * r >= n && (c - 1) * r < n && c * (r - 1) < n }
                .minByOrNull { (c, r) ->
                    val aspect = (sized.widthDp / c) / (sized.heightDp / r)
                    abs(kotlin.math.ln(aspect / 1.4)) + 0.3 * (c * r - n)
                }
            val cols = best?.first ?: maxCols
            val usedRows = best?.second ?: 0
            val old = d != null && (d.offline || System.currentTimeMillis() - d.ts > STALE_MS)
            for (r in 0 until 4) v.setViewVisibility(TROW[r], if (r < usedRows) View.VISIBLE else View.GONE)
            for (r in 0 until 4) for (c in 0 until 4) {
                val slot = r * 4 + c
                val coin = coins.getOrNull(r * cols + c)
                // A missing coin keeps its tile's place (INVISIBLE) so the last row lines up with the others.
                v.setViewVisibility(TILE[slot], when {
                    c >= cols || r >= usedRows -> View.GONE
                    coin == null || d == null -> View.INVISIBLE
                    else -> View.VISIBLE
                })
                if (coin == null || d == null || c >= cols) continue
                v.setTextViewText(TSYM[slot], coin.sym)
                // Two wide columns have room for the whole price; three or four get the short form.
                v.setTextViewText(TPRICE[slot], if (cols <= 2) price(d, coin.price) else price(d, coin.price, short = true))
                v.setTextColor(TPRICE[slot], if (old) WC.MID else WC.TEXT)
                val ch = coin.change(d.change)
                v.setTextViewText(TCHG[slot], pct(ch))
                v.setTextColor(TCHG[slot], WC.change(ch))
                v.setOnClickPendingIntent(TILE[slot], openApp(ctx, "mazecrypto://coin/${coin.id}", 300 + slot))
            }
            v.emptyState(ctx, d, usedRows)
            v
        }
    }

    // ── top movers ─────────────────────────────────────────────────────────

    private sealed interface Mv {
        data class Two(val rows: Int) : Mv
        data class Narrow(val rows: Int) : Mv
    }

    private val MOVER_LAYOUTS: List<SizedLayout<Mv>> = (1..4).map { SizedLayout<Mv>(Mv.Two(it), 200f, 49.4f + 21.2f * it) } +
        listOf(2 to 108f, 3 to 144f, 4 to 181f, 6 to 253f).map { (n, h) -> SizedLayout<Mv>(Mv.Narrow(n), 100f, h) }

    private val G = arrayOf(intArrayOf(R.id.gsym_1, R.id.gsym_2, R.id.gsym_3, R.id.gsym_4), intArrayOf(R.id.gchg_1, R.id.gchg_2, R.id.gchg_3, R.id.gchg_4),
        intArrayOf(R.id.grow_1, R.id.grow_2, R.id.grow_3, R.id.grow_4))
    private val L = arrayOf(intArrayOf(R.id.lsym_1, R.id.lsym_2, R.id.lsym_3, R.id.lsym_4), intArrayOf(R.id.lchg_1, R.id.lchg_2, R.id.lchg_3, R.id.lchg_4),
        intArrayOf(R.id.lrow_1, R.id.lrow_2, R.id.lrow_3, R.id.lrow_4))

    fun movers(ctx: Context, id: Int, d: WidgetData?): RemoteViews {
        val period = d?.change ?: "24h"
        // Stablecoins are left out: their "moves" are rounding noise.
        val moving = d?.coins?.filter { !it.stable && it.change(period) != null } ?: emptyList()
        val up = moving.filter { it.change(period)!! > 0 }.sortedByDescending { it.change(period) }
        val down = moving.filter { it.change(period)!! < 0 }.sortedBy { it.change(period) }
        val mixed = moving.sortedByDescending { abs(it.change(period)!!) }
        val title = t("widget.movers") + " · " + period
        return WidgetSizing.build(ctx, id, MOVER_LAYOUTS, Mv.Two(4)) { sized ->
            when (val k = sized.key) {
                is Mv.Narrow -> {
                    val v = RemoteViews(ctx.packageName, R.layout.widget_fav_narrow)
                    v.setOnClickPendingIntent(android.R.id.background, openApp(ctx, "mazecrypto://prices", 4))
                    v.updated(ctx, d)
                    v.setOnClickPendingIntent(R.id.w_refresh, refreshIntent(ctx))
                    v.coinRows(ctx, d, mixed, 6, k.rows, short = true)
                    v.emptyState(ctx, d, min(k.rows, mixed.size))
                    v
                }
                is Mv.Two -> {
                    val v = RemoteViews(ctx.packageName, R.layout.widget_movers)
                    v.setOnClickPendingIntent(android.R.id.background, openApp(ctx, "mazecrypto://prices", 4))
                    v.setTextViewText(R.id.w_title, title)
                    v.updated(ctx, d)
                    v.setOnClickPendingIntent(R.id.w_refresh, refreshIntent(ctx))
                    v.setTextViewText(R.id.g_head, t("widget.gainers"))
                    v.setTextViewText(R.id.l_head, t("widget.losers"))
                    for ((ids, list, base) in listOf(Triple(G, up, 400), Triple(L, down, 410))) {
                        for (i in 0 until 4) {
                            val c = list.getOrNull(i)
                            if (c == null || i >= k.rows) {
                                v.setViewVisibility(ids[2][i], if (i < k.rows) View.INVISIBLE else View.GONE)
                                continue
                            }
                            v.setViewVisibility(ids[2][i], View.VISIBLE)
                            v.setTextViewText(ids[0][i], c.sym)
                            val ch = c.change(period)
                            v.setTextViewText(ids[1][i], pct(ch))
                            v.setTextColor(ids[1][i], WC.change(ch))
                            v.setOnClickPendingIntent(ids[2][i], openApp(ctx, "mazecrypto://coin/${c.id}", base + i))
                        }
                    }
                    v.emptyState(ctx, d, if (d == null) 0 else 1)
                    v
                }
            }
        }
    }

    // ── holdings ───────────────────────────────────────────────────────────

    private sealed interface Hd {
        data class List(val rows: Int) : Hd
        data class Narrow(val rows: Int) : Hd
    }

    private val HOLD_LAYOUTS: kotlin.collections.List<SizedLayout<Hd>> = (2..8).map { SizedLayout<Hd>(Hd.List(it), 236f, 36f + 21.2f * it) } +
        listOf(2 to 108f, 3 to 144f, 4 to 181f, 6 to 253f).map { (n, h) -> SizedLayout<Hd>(Hd.Narrow(n), 100f, h) }

    /** A thin bar: this holding's share of the portfolio. */
    private fun weightBar(ctx: Context, weight: Double, wDp: Int, hDp: Int): Bitmap {
        val s = scale(ctx)
        val w = (wDp * s).toInt()
        val h = (hDp * s).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val th = 4f * s
        val top = (h - th) / 2
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0xFF1C1C21.toInt()
        c.drawRoundRect(0f, top, w.toFloat(), top + th, th / 2, th / 2, p)
        p.color = WC.SERIES[0]
        c.drawRoundRect(0f, top, (w * (weight / 100.0).coerceIn(0.02, 1.0)).toFloat(), top + th, th / 2, th / 2, p)
        return bmp
    }

    fun holdings(ctx: Context, id: Int, d: WidgetData?): RemoteViews {
        val pf = d?.portfolio
        val hs = pf?.holdings ?: emptyList()
        val bars = hs.take(8).map { weightBar(ctx, it.weight, 64, 14) }
        val hide = d?.private == true
        fun fill(v: RemoteViews, slots: Int, rows: Int, narrow: Boolean) {
            for (i in 0 until slots) {
                val h = hs.getOrNull(i)
                if (h == null || i >= rows || d == null) {
                    v.setViewVisibility(ROW[i], View.GONE)
                    continue
                }
                v.setViewVisibility(ROW[i], View.VISIBLE)
                v.setTextViewText(SYM[i], h.sym)
                v.setTextViewText(PRICE[i], if (hide) Fmt.pct(h.weight, 1, signed = false)
                    else if (narrow) Fmt.moneyShort(h.value, d.fiat) else Fmt.money(h.value, d.fiat, 0))
                v.setTextViewText(CHG[i], pct(h.pnlPct))
                v.setTextColor(CHG[i], WC.change(h.pnlPct))
                if (!narrow) v.setImageViewBitmap(SPARK[i], bars[i])
                v.setOnClickPendingIntent(ROW[i], openApp(ctx, "mazecrypto://coin/${h.id}", 500 + i))
            }
        }
        return WidgetSizing.build(ctx, id, HOLD_LAYOUTS, Hd.List(6)) { sized ->
            val (layout, rows, narrow) = when (val k = sized.key) {
                is Hd.List -> Triple(R.layout.widget_fav_list, k.rows, false)
                is Hd.Narrow -> Triple(R.layout.widget_fav_narrow, k.rows, true)
            }
            val v = RemoteViews(ctx.packageName, layout)
            v.setOnClickPendingIntent(android.R.id.background, openApp(ctx, "mazecrypto://portfolio", 3))
            if (!narrow) v.setTextViewText(R.id.w_title, t("widget.holdings") +
                if (pf != null && !hide) " · " + Fmt.money(pf.value, d.fiat, 0) else "")
            v.updated(ctx, d)
            v.setOnClickPendingIntent(R.id.w_refresh, refreshIntent(ctx))
            fill(v, if (narrow) 6 else 8, rows, narrow)
            if (hs.isEmpty()) {
                v.setViewVisibility(R.id.w_empty, View.VISIBLE)
                v.setTextViewText(R.id.w_empty, t(if (d == null) "widget.empty_nodata" else "widget.empty_hold"))
            } else v.setViewVisibility(R.id.w_empty, View.GONE)
            v
        }
    }

    // ── buying plan ────────────────────────────────────────────────────────

    private enum class Pl { SMALL, MID, TALL, WIDE }

    private val PLAN_LAYOUTS = listOf(
        SizedLayout(Pl.SMALL, 100f, 48f),
        SizedLayout(Pl.MID, 100f, 76f),
        SizedLayout(Pl.TALL, 110f, 108f),
        SizedLayout(Pl.WIDE, 190f, 44f),
    )

    private fun progressBar(ctx: Context, fraction: Float, wDp: Int, hDp: Int): Bitmap {
        val s = scale(ctx)
        val w = (wDp * s).toInt()
        val h = (hDp * s).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0xFF1C1C21.toInt()
        c.drawRoundRect(0f, 0f, w.toFloat(), h.toFloat(), h / 2f, h / 2f, p)
        if (fraction > 0f) {
            p.color = WC.SERIES[0]
            c.drawRoundRect(0f, 0f, maxOf(h.toFloat(), w * fraction.coerceAtMost(1f)), h.toFloat(), h / 2f, h / 2f, p)
        }
        return bmp
    }

    fun plan(ctx: Context, id: Int, d: WidgetData?): RemoteViews {
        val pl = d?.plan
        val bmp = pl?.let { progressBar(ctx, if (it.periods == 0) 0f else it.done.toFloat() / it.periods, 140, 5) }
        return WidgetSizing.build(ctx, id, PLAN_LAYOUTS, Pl.SMALL) { sized ->
            val v = RemoteViews(ctx.packageName, if (sized.key == Pl.WIDE) R.layout.widget_plan_wide else R.layout.widget_plan)
            v.setOnClickPendingIntent(android.R.id.background, openApp(ctx, "mazecrypto://plan", 5))
            v.setTextViewText(R.id.pl_label, t("widget.plan"))
            val vertical = sized.key != Pl.WIDE
            if (vertical) {
                v.setViewVisibility(R.id.pl_when, if (sized.key == Pl.SMALL) View.GONE else View.VISIBLE)
                v.setViewVisibility(R.id.pl_bar, if (sized.key == Pl.TALL) View.VISIBLE else View.GONE)
                v.setViewVisibility(R.id.pl_items, if (sized.key == Pl.TALL) View.VISIBLE else View.GONE)
            }
            if (pl == null) {
                v.setTextViewText(R.id.pl_amount, Fmt.DASH)
                v.setTextViewText(R.id.pl_state, "")
                v.setTextViewText(R.id.pl_when, t("widget.no_plan"))
                if (!vertical) v.setViewVisibility(R.id.pl_bar, View.GONE)
                if (vertical) v.setViewVisibility(R.id.pl_when, View.VISIBLE)
                return@build v
            }
            val money = { x: Double -> Fmt.money(x, pl.fiat, if (abs(x) >= 100) 0 else 2) }
            val finished = pl.state == "finished"
            v.setTextViewText(R.id.pl_amount, if (finished) "✓" else money(pl.amount))
            v.setTextViewText(R.id.pl_state, if (finished) t("dca.finished") else t("dca.state.${pl.state}"))
            v.setTextColor(R.id.pl_state, when (pl.state) {
                "overdue" -> WC.WARN; "due" -> WC.SERIES[0]; "finished" -> WC.UP; else -> WC.MID
            })
            val days = pl.dueEpochDay - java.time.LocalDate.now().toEpochDay()
            val whenText = when {
                days > 0 -> t("dca.in_days", "n" to days)
                days == 0L -> t("dca.today")
                else -> t("dca.ago", "n" to -days)
            }
            v.setTextViewText(R.id.pl_when, if (finished) "${pl.done} / ${pl.periods}"
                else if (vertical) t("dca.period_n", "n" to pl.index + 1, "total" to pl.periods) + " · " + whenText
                else "${pl.index + 1}/${pl.periods} · $whenText")
            bmp?.let { v.setImageViewBitmap(R.id.pl_bar, it) }
            if (vertical) v.setTextViewText(R.id.pl_items, pl.items.take(4).joinToString(" · ") { "${it.first} ${money(it.second)}" })
            v
        }
    }
}

// ── providers ───────────────────────────────────────────────────────────────

abstract class MazeWidget : AppWidgetProvider() {
    /**
     * Redraw at once, then — when the data is old — refresh right here. This
     * is the platform's 30-minute widget timer (updatePeriodMillis): it still
     * arrives when the system or the phone maker defers the app's jobs, so
     * widgets keep moving on phones that would otherwise freeze them.
     */
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        Maze.init(context)
        Widgets.updateAll(context)
        val age = System.currentTimeMillis() - (WidgetData.load(context)?.ts ?: 0L)
        if (Maze.settings.value.backgroundMinutes == 0 || age < STALE_FOR_TIMER_MS) return
        val pending = goAsync()
        Maze.scope.launch {
            try {
                // A broadcast gets well under a minute; a slow network must not hold it past that.
                withTimeoutOrNull(25_000) {
                    com.mazecrypto.app.work.BackgroundLog.run(context, "widget") { Maze.refresh(background = true) }
                }
                Widgets.updateAll(context)
            } finally {
                pending.finish()
            }
        }
    }

    /** A resize, or the launcher reporting sizes for the first time (API 26-30 picks here). */
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle) {
        Maze.init(context)
        Widgets.updateAll(context)
    }

    override fun onEnabled(context: Context) {
        Maze.init(context)
        RefreshJob.schedule(context)
        // A new widget should not wait up to 15 minutes for its first prices.
        RefreshJob.runNow(context)
    }
}

/** Older than this when the widget timer fires: refresh. Younger: the job already did. */
private const val STALE_FOR_TIMER_MS = 10 * 60 * 1000L

class FavoritesWidget : MazeWidget()

class GridWidget : MazeWidget()

class MoversWidget : MazeWidget()

class HoldingsWidget : MazeWidget()

class PlanWidget : MazeWidget()

class PortfolioWidget : MazeWidget()

class CoinWidget : MazeWidget() {
    override fun onDeleted(context: Context, ids: IntArray) = Widgets.forget(context, ids)
}

/** The refresh icon on a widget: show "…" at once, then fetch in a job. */
class WidgetRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Maze.init(context)
        Widgets.markBusy(context)
        Widgets.updateAll(context)
        RefreshJob.runNow(context)
    }
}

