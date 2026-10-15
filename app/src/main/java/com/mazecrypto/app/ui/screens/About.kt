package com.mazecrypto.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mazecrypto.app.BuildConfig
import com.mazecrypto.app.core.t
import com.mazecrypto.app.data.MarketService
import com.mazecrypto.app.ui.Nav
import com.mazecrypto.app.ui.components.AppMark
import com.mazecrypto.app.ui.components.Disclaimer
import com.mazecrypto.app.ui.components.Glass
import com.mazecrypto.app.ui.components.SectionTitle
import com.mazecrypto.app.ui.theme.LocalPalette
import com.mazecrypto.app.ui.theme.Type

@Composable
fun AboutScreen(nav: Nav) {
    val p = LocalPalette.current
    SubScreen(nav, t("about.title")) {
        Gap(8)
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppMark(56)
            Spacer(Modifier.width(14.dp))
            Column {
                Text("Maze Crypto", style = Type.strong.copy(fontSize = 20.sp), color = p.text)
                Text("${BuildConfig.VERSION_NAME} · ${t("app.readonly")} · GPL-3.0-or-later", style = Type.small, color = p.muted)
            }
        }
        Gap(14)
        Disclaimer(t("about.disclaimer"))
        listOf("what" to "about.what_body", "privacy" to "m.about.privacy_body", "keys" to "about.keys_body").forEach { (k, body) ->
            SectionTitle(t("about.$k"))
            Glass(Modifier.fillMaxWidth()) { Text(t(body), style = Type.body, color = p.textMid) }
        }
        SectionTitle(t("about.sources"))
        Glass(Modifier.fillMaxWidth()) {
            MarketService.allowedHosts().keys.sorted().forEach {
                Text(it, style = Type.numSmall, color = p.textMid, modifier = Modifier.padding(vertical = 2.dp))
            }
        }
        SectionTitle(t("m.about.credits"))
        Glass(Modifier.fillMaxWidth()) {
            Text(t("m.about.credits_body"), style = Type.bodySmall, color = p.textMid)
        }
    }
}
