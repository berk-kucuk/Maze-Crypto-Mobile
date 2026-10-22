package com.mazecrypto.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mazecrypto.app.R
import com.mazecrypto.app.core.Fmt
import com.mazecrypto.app.core.t
import com.mazecrypto.app.ui.theme.Inter
import com.mazecrypto.app.ui.theme.LocalPalette
import com.mazecrypto.app.ui.theme.Type

val CardShape = RoundedCornerShape(18.dp)

/** A near-black glass card with a lit rim, as on the desktop. */
@Composable
fun Glass(
    modifier: Modifier = Modifier,
    padding: Dp = 16.dp,
    tint: Color? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = LocalPalette.current
    val base = tint?.copy(alpha = 0.07f) ?: p.glass
    Column(
        modifier
            .clip(CardShape)
            .background(Brush.verticalGradient(listOf(base.copy(alpha = base.alpha + 0.03f), base)))
            .border(BorderStroke(1.dp, Brush.verticalGradient(listOf(p.glassLineHi, p.glassLine))), CardShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

@Composable
fun Ic(@DrawableRes id: Int, modifier: Modifier = Modifier, tint: Color = LocalPalette.current.text, size: Dp = 20.dp) {
    Icon(painterResource(id), contentDescription = null, modifier = modifier.size(size), tint = tint)
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    val p = LocalPalette.current
    Row(modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp, start = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = Type.section, color = p.muted, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
fun PageHeader(title: String, sub: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    val p = LocalPalette.current
    // Title and actions share a row; the subtitle gets the full width below, so it never wraps against the actions.
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = Type.pageTitle, color = p.text, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            actions()
        }
        if (sub != null) Text(sub, style = Type.pageSub, color = p.textMid, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
fun ChangeText(v: Double?, style: TextStyle = Type.num, decimals: Int = 2, modifier: Modifier = Modifier) {
    Text(Fmt.pct(v, decimals), style = style, color = LocalPalette.current.change(v), modifier = modifier, maxLines = 1)
}

/** Change as a soft coloured pill. */
@Composable
fun ChangePill(v: Double?, decimals: Int = 2) {
    val p = LocalPalette.current
    val c = p.change(v)
    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(c.copy(alpha = 0.12f)).padding(horizontal = 7.dp, vertical = 3.dp)) {
        Text(Fmt.pct(v, decimals), style = Type.numSmall.copy(fontFamily = Inter), color = c, maxLines = 1)
    }
}

/** A line of prices, coloured by its direction, with a soft fill below. */
@Composable
fun Sparkline(values: List<Double>, modifier: Modifier = Modifier, fill: Boolean = true, color: Color? = null) {
    val p = LocalPalette.current
    if (values.size < 2) {
        Box(modifier)
        return
    }
    val c = color ?: if (values.last() >= values.first()) p.up else p.down
    Canvas(modifier) {
        // A floor of 1% of the price: a stablecoin's flat line stays flat instead of magnifying noise.
        val span = maxOf(values.max() - values.min(), kotlin.math.abs(values.average()) * 0.01, 1e-12)
        val lo = (values.max() + values.min()) / 2 - span / 2
        val pad = 2.dp.toPx()
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = i.toFloat() / (values.size - 1) * size.width
            val y = (pad + (1 - (v - lo) / span) * (size.height - 2 * pad)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        if (fill) {
            val area = Path().apply { addPath(path); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
            drawPath(area, Brush.verticalGradient(listOf(c.copy(alpha = 0.22f), c.copy(alpha = 0f))))
        }
        drawPath(path, c, style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, @DrawableRes icon: Int? = null, enabled: Boolean = true, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(
        modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (enabled) p.accent else p.glass)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Ic(icon, tint = if (enabled) p.onAccent else p.dim, size = 17.dp)
            Spacer(Modifier.width(7.dp))
        }
        Text(text, style = Type.button, color = if (enabled) p.onAccent else p.dim, maxLines = 1)
    }
}

@Composable
fun GhostButton(text: String, modifier: Modifier = Modifier, @DrawableRes icon: Int? = null, danger: Boolean = false,
                enabled: Boolean = true, onClick: () -> Unit) {
    val p = LocalPalette.current
    val fg = if (!enabled) p.dim else if (danger) p.down else p.text
    Row(
        modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (danger) p.down.copy(alpha = 0.10f) else p.glassHi)
            .border(1.dp, if (danger) p.down.copy(alpha = 0.35f) else p.glassLine, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Ic(icon, tint = fg, size = 17.dp)
            Spacer(Modifier.width(7.dp))
        }
        Text(text, style = Type.button, color = fg, maxLines = 1)
    }
}

@Composable
fun LinkButton(text: String, modifier: Modifier = Modifier, arrow: Boolean = true, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = Type.button.copy(fontSize = 13.5.sp), color = p.accent)
        if (arrow) {
            Spacer(Modifier.width(4.dp))
            Ic(R.drawable.ic_arrow_right, tint = p.accent, size = 14.dp)
        }
    }
}

@Composable
fun IconBtn(@DrawableRes id: Int, modifier: Modifier = Modifier, tint: Color = LocalPalette.current.textMid, size: Dp = 22.dp,
            enabled: Boolean = true, onClick: () -> Unit) {
    Box(modifier.size(42.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Ic(id, tint = if (enabled) tint else LocalPalette.current.dim, size = size)
    }
}

@Composable
fun Chip(text: String, selected: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) p.accent else p.glass)
            .border(1.dp, if (selected) p.accent else p.glassLine, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 7.dp),
    ) {
        Text(text, style = Type.label, color = if (selected) p.onAccent else p.textMid, maxLines = 1)
    }
}

/** A row of mutually exclusive options (the desktop's Seg buttons). */
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, modifier: Modifier = Modifier, onSelect: (T) -> Unit) {
    val p = LocalPalette.current
    Row(
        modifier.clip(RoundedCornerShape(12.dp)).background(p.glass).border(1.dp, p.glassLine, RoundedCornerShape(12.dp)).padding(3.dp),
    ) {
        options.forEach { (key, label) ->
            val on = key == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(9.dp)).background(if (on) p.accent else Color.Transparent)
                    .clickable { onSelect(key) }.padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = Type.label, color = if (on) p.onAccent else p.textMid, maxLines = 1)
            }
        }
    }
}

@Composable
fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    number: Boolean = false,
    singleLine: Boolean = true,
    error: Boolean = false,
    supporting: String? = null,
    keyboard: KeyboardOptions? = null,
    textStyle: TextStyle? = null,
    trailing: (@Composable () -> Unit)? = null,
    visual: androidx.compose.ui.text.input.VisualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
) {
    val p = LocalPalette.current
    OutlinedTextField(
        value = value, onValueChange = onChange, modifier = modifier.fillMaxWidth(),
        label = { Text(label, style = Type.bodySmall) },
        placeholder = placeholder?.let { { Text(it, style = Type.body, color = p.muted) } },
        singleLine = singleLine, isError = error,
        supportingText = supporting?.let { { Text(it, style = Type.small) } },
        textStyle = (textStyle ?: Type.body).copy(color = p.text, fontFeatureSettings = if (number) "tnum" else null),
        keyboardOptions = keyboard ?: if (number) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
        trailingIcon = trailing,
        visualTransformation = visual,
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = p.accent.copy(alpha = 0.6f), unfocusedBorderColor = p.glassLine,
            focusedLabelColor = p.accent, unfocusedLabelColor = p.textMid, cursorColor = p.accent,
            focusedContainerColor = Color.White.copy(alpha = 0.04f), unfocusedContainerColor = Color.White.copy(alpha = 0.03f),
        ),
    )
}

@Composable
fun SwitchRow(title: String, checked: Boolean, sub: String? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.body, color = if (enabled) p.text else p.dim)
            if (sub != null) Text(sub, style = Type.small, color = p.muted, modifier = Modifier.padding(top = 2.dp))
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled, colors = SwitchDefaults.colors(
            checkedThumbColor = p.onAccent, checkedTrackColor = p.accent, uncheckedThumbColor = p.textMid,
            uncheckedTrackColor = p.glassHi, uncheckedBorderColor = p.glassLineHi,
        ))
    }
}

/** A tappable settings row: title, current value, chevron. */
@Composable
fun ValueRow(title: String, value: String, sub: String? = null, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.body, color = p.text)
            if (sub != null) Text(sub, style = Type.small, color = p.muted, modifier = Modifier.padding(top = 2.dp))
        }
        Text(value, style = Type.label, color = p.textMid, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp).widthIn(max = 180.dp))
        Ic(R.drawable.ic_caret_right, tint = p.muted, size = 16.dp, modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
fun Divider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(LocalPalette.current.glassLine))
}

/** Live · 12s, Stale data · 5m, Offline, Updating… */
@Composable
fun StatusPill(state: String, text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    val c = when (state) {
        "live" -> p.up
        "stale" -> p.warn
        "offline" -> p.down
        else -> p.accent
    }
    Row(
        modifier.clip(RoundedCornerShape(14.dp)).background(c.copy(alpha = 0.08f)).border(1.dp, c.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(c))
        Spacer(Modifier.width(6.dp))
        Text(text, style = Type.small.copy(fontFeatureSettings = "tnum"), color = c, maxLines = 1)
    }
}

@Composable
fun Badge(text: String, color: Color = LocalPalette.current.textMid) {
    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.12f)).padding(horizontal = 7.dp, vertical = 2.dp)) {
        Text(text, style = Type.small, color = color, maxLines = 1)
    }
}

/** The not-advice notice, in the accent tint. */
@Composable
fun Disclaimer(text: String, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.accent.copy(alpha = 0.07f))
        .border(1.dp, p.accent.copy(alpha = 0.25f), RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 11.dp)) {
        Text(text, style = Type.bodySmall, color = p.accentHi)
    }
}

@Composable
fun Explain(title: String, body: String, @DrawableRes icon: Int? = null, color: Color = LocalPalette.current.textMid) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        if (icon != null) {
            Box(Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(color.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Ic(icon, tint = color, size = 16.dp)
            }
            Spacer(Modifier.width(11.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.strong.copy(fontSize = 14.sp), color = p.text)
            Text(body, style = Type.bodySmall, color = p.textMid, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
fun EmptyState(@DrawableRes icon: Int, title: String, body: String, action: (@Composable () -> Unit)? = null) {
    val p = LocalPalette.current
    Glass(Modifier.fillMaxWidth(), padding = 20.dp) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(p.accent.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Ic(icon, tint = p.accent, size = 20.dp)
        }
        Text(title, style = Type.strong.copy(fontSize = 16.sp), color = p.text, modifier = Modifier.padding(top = 12.dp))
        Text(body, style = Type.body, color = p.textMid, modifier = Modifier.padding(top = 4.dp))
        if (action != null) {
            Spacer(Modifier.height(14.dp))
            action()
        }
    }
}

@Composable
fun Confirm(title: String, text: String? = null, confirm: String = t("btn.ok"), danger: Boolean = false,
            onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val p = LocalPalette.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = p.surface,
        title = { Text(title, style = Type.strong.copy(fontSize = 17.sp), color = p.text) },
        text = text?.let { { Text(it, style = Type.body, color = p.textMid) } },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) {
                Text(confirm, style = Type.button, color = if (danger) p.down else p.accent)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("btn.cancel"), style = Type.button, color = p.textMid) } },
    )
}

/** A list of options in a dialog — used instead of dropdowns, which are awkward on a phone. */
@Composable
fun <T> Picker(title: String, options: List<Pair<T, String>>, selected: T?, onDismiss: () -> Unit, onPick: (T) -> Unit) {
    val p = LocalPalette.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = p.surface,
        title = { Text(title, style = Type.strong.copy(fontSize = 17.sp), color = p.text) },
        text = {
            androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(options.size) { i ->
                    val (key, label) = options[i]
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onPick(key); onDismiss() }
                        .padding(horizontal = 6.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, style = Type.body, color = if (key == selected) p.accent else p.text, modifier = Modifier.weight(1f))
                        if (key == selected) Ic(R.drawable.ic_check, tint = p.accent, size = 18.dp)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("btn.cancel"), style = Type.button, color = p.textMid) } },
    )
}

/** Thin progress bar in the accent colour. */
@Composable
fun Progress(fraction: Float, modifier: Modifier = Modifier, color: Color = LocalPalette.current.accent) {
    val p = LocalPalette.current
    Box(modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(p.glassHi)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(6.dp).clip(RoundedCornerShape(3.dp)).background(color))
    }
}

/** Two values side by side under small labels. */
@Composable
fun KeyValue(key: String, value: String, modifier: Modifier = Modifier, color: Color = LocalPalette.current.text) {
    val p = LocalPalette.current
    Column(modifier) {
        Text(key, style = Type.small, color = p.muted, maxLines = 1)
        Text(value, style = Type.num, color = color, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
    }
}

val ScreenPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp)

@Composable
fun Dot(color: Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}


/** The app's mark on its black tile, for headers. */
@Composable
fun AppMark(sizeDp: Int) {
    Box(Modifier.size(sizeDp.dp).clip(RoundedCornerShape((sizeDp * 0.22f).dp)).background(Color.Black)
        .border(1.dp, LocalPalette.current.glassLine, RoundedCornerShape((sizeDp * 0.22f).dp)), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Image(painterResource(R.drawable.ic_launcher_foreground), null,
            Modifier.wrapContentSize(unbounded = true).size((sizeDp * 1.5f).dp))
    }
}
