package com.mazecrypto.app.core

import android.content.Context
import org.json.JSONObject

/**
 * The desktop app's translation tables, shared as-is (assets/i18n/en.json and
 * tr.json are copies of mazecrypto/i18n), plus a small mobile overlay
 * (mobile.en.json / mobile.tr.json) for phone wording and widget text.
 *
 * `t("key", "name" to value)` looks the key up in the active language, falls
 * back to English, then to the key itself, and fills `{name}` placeholders
 * exactly like the desktop's `str.format`.
 *
 * English is the default; Turkish is chosen in Settings, never auto-detected.
 */
object I18n {
    val LANGS = listOf("en", "tr")
    private val tables = HashMap<String, Map<String, String>>()
    @Volatile var language: String = "en"
        private set
    private var app: Context? = null
    private val PLACEHOLDER = Regex("""\{(\w+)\}""")

    fun init(context: Context) {
        app = context.applicationContext
    }

    fun setLanguage(lang: String): String {
        language = if (lang in LANGS) lang else "en"
        return language
    }

    @Synchronized
    private fun table(lang: String): Map<String, String> = tables.getOrPut(lang) {
        val ctx = app ?: return@getOrPut emptyMap()
        val out = HashMap<String, String>()
        for (name in listOf("i18n/$lang.json", "i18n/mobile.$lang.json")) {
            runCatching {
                val text = ctx.assets.open(name).bufferedReader().use { it.readText() }
                val o = JSONObject(text)
                for (k in o.keys()) out[k] = o.getString(k)
            }
        }
        out
    }

    fun has(key: String): Boolean = table(language).containsKey(key) || table("en").containsKey(key)

    fun t(key: String, vararg args: Pair<String, Any?>): String {
        val s = table(language)[key] ?: table("en")[key] ?: key
        if (args.isEmpty()) return s
        val map = args.toMap()
        return PLACEHOLDER.replace(s) { m -> map[m.groupValues[1]]?.toString() ?: m.value }
    }

    fun t(key: String, args: Map<String, Any?>): String = t(key, *args.toList().toTypedArray())

    /** Capitalise the first letter, minding the Turkish dotted/dotless i. */
    fun upperFirst(text: String): String {
        if (text.isEmpty()) return text
        val c = text[0]
        val up = if (language == "tr" && c == 'i') "İ" else if (language == "tr" && c == 'ı') "I" else c.uppercase()
        return up + text.substring(1)
    }
}

fun t(key: String, vararg args: Pair<String, Any?>): String = I18n.t(key, *args)
