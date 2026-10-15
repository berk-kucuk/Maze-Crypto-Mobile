package com.mazecrypto.app.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mazecrypto.app.R
import com.mazecrypto.app.core.t
import com.mazecrypto.app.data.Maze
import com.mazecrypto.app.ui.components.Divider
import com.mazecrypto.app.ui.components.Glass
import com.mazecrypto.app.ui.components.Ic
import com.mazecrypto.app.ui.theme.LocalPalette
import com.mazecrypto.app.ui.theme.MazeTheme
import com.mazecrypto.app.ui.theme.Type

/**
 * Which coin a "Coin" widget shows. Optional on Android 12+ (the widget
 * starts with the first favorite); long-press → reconfigure brings it back.
 */
class CoinWidgetConfigureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        Maze.init(this)
        val id = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // Backing out leaves the widget unplaced, as the platform expects.
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        val s = Maze.settings.value
        val current = Widgets.coinFor(this, id)
        setContent {
            MazeTheme(s.accent) {
                val p = LocalPalette.current
                Column(Modifier.fillMaxSize().background(p.bg).statusBarsPadding().navigationBarsPadding().padding(16.dp)) {
                    Text(t("m.widget.pick_coin"), style = Type.pageTitle, color = p.text)
                    Text(t("m.widget.pick_coin_sub"), style = Type.bodySmall, color = p.textMid, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
                    val coins = s.watchlist.sortedBy { c -> s.favorites.indexOf(c.id).let { if (it < 0) Int.MAX_VALUE else it } }
                    if (coins.isEmpty()) Text(t("widget.empty_nodata"), style = Type.body, color = p.textMid)
                    Glass(Modifier.fillMaxWidth(), padding = 0.dp) {
                        LazyColumn {
                            items(coins, key = { it.id }) { c ->
                                Row(Modifier.fillMaxWidth().clickable { pick(id, c.id) }.padding(horizontal = 16.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(c.symbol, style = Type.strong, color = p.text)
                                        Text(c.name, style = Type.small, color = p.muted)
                                    }
                                    if (c.id == current) Ic(R.drawable.ic_check, tint = p.accent, size = 18.dp)
                                    else if (c.id in s.favorites) Ic(R.drawable.ic_star_fill, tint = p.accent.copy(alpha = 0.6f), size = 16.dp)
                                }
                                Divider()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun pick(id: Int, coinId: String) {
        Widgets.setCoin(this, id, coinId)
        Widgets.updateAll(this)
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        finish()
    }
}
