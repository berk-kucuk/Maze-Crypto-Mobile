package com.mazecrypto.app.data

import com.mazecrypto.app.BuildConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Any failure to get a usable response. */
open class NetError(message: String) : Exception(message)
class HostNotAllowed(message: String) : NetError(message)
class RateLimited(message: String) : NetError(message)
/** A definite answer from the server that is not 200 — e.g. 404 for an address an indexer has never seen. */
class HttpError(val code: Int, message: String) : NetError(message)
class ValidationError(message: String) : Exception(message)
class NotSupported(message: String) : Exception(message)

/**
 * The only place that talks to the network (port of providers/http.py).
 *
 * * Host allow-list: a request to any host not registered by a provider is
 *   refused before a socket is opened. No telemetry, no surprise endpoints.
 * * Optional proxy (SOCKS5 — e.g. Orbot's 127.0.0.1:9050 — or HTTP). With a
 *   SOCKS proxy the host name is sent unresolved, so DNS goes through it too.
 * * Per-host minimum spacing between requests and bounded retries, honouring
 *   `Retry-After` on HTTP 429.
 */
class Http(
    allowed: Map<String, Double>,
    private val proxyUrl: String? = null,
    private val timeoutMs: Int = 10_000,
    private val retries: Int = 2,
) {
    private val allowed = HashMap(allowed)
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val last = ConcurrentHashMap<String, Long>()
    private val downUntil = ConcurrentHashMap<String, Long>()
    @Volatile var requests = 0
        private set

    private val proxy: Proxy? = proxyUrl?.let { parseProxy(it) }

    private fun host(url: String): String {
        val u = runCatching { URI(url) }.getOrNull()
        val h = u?.host
        if (u?.scheme != "https" || h == null || h !in allowed) throw HostNotAllowed("blocked request to ${h ?: url}")
        return h
    }

    private suspend fun space(host: String) {
        locks.getOrPut(host) { Mutex() }.withLock {
            val minGap = ((allowed[host] ?: 0.0) * 1000).toLong()
            val wait = (last[host] ?: 0L) + minGap - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            last[host] = System.currentTimeMillis()
        }
    }

    private fun buildUrl(url: String, params: Map<String, Any>?): String {
        if (params.isNullOrEmpty()) return url
        val q = params.entries.joinToString("&") { (k, v) ->
            URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v.toString(), "UTF-8")
        }
        return "$url?$q"
    }

    suspend fun get(url: String, params: Map<String, Any>? = null, maxBytes: Int = 16 * 1024 * 1024): ByteArray =
        request(url, params, null, maxBytes)

    /** POST a JSON body (JSON-RPC reads: balances, transactions). Same allow-list, spacing and retries as GET. */
    suspend fun post(url: String, body: String, maxBytes: Int = 16 * 1024 * 1024): ByteArray =
        request(url, null, body, maxBytes)

    private suspend fun request(url: String, params: Map<String, Any>?, body: String?, maxBytes: Int): ByteArray {
        val h = host(url)
        if ((downUntil[h] ?: 0L) > System.currentTimeMillis()) {
            // Recently unreachable: fail fast so offline mode shows cached data at once.
            throw NetError("$h: unreachable")
        }
        val full = buildUrl(url, params)
        var lastErr: Exception? = null
        for (attempt in 0..retries) {
            space(h)
            requests++
            val result = withContext(Dispatchers.IO) { runCatching { fetchOnce(full, body, maxBytes) } }
            val r = result.getOrElse { e ->
                when (e) {
                    is UnknownHostException, is ConnectException -> {
                        // No route / DNS / dead proxy: retrying right away will not help.
                        downUntil[h] = System.currentTimeMillis() + DOWN_MS
                        throw NetError("$h: ${e.javaClass.simpleName}")
                    }
                    is NetError -> throw e
                    is IOException -> {
                        lastErr = e as Exception
                        delay(400L * (attempt + 1))
                        null
                    }
                    else -> throw NetError("$h: ${e.javaClass.simpleName}")
                }
            } ?: continue
            val (code, bytes, retryAfter) = r
            if (code == 429) {
                val d = retryAfter?.toDoubleOrNull() ?: (2.0 * (attempt + 1))
                if (d > 10 || attempt == retries) throw RateLimited("$h: rate limited")
                delay((d * 1000).toLong())
                continue
            }
            if (code >= 500) {
                lastErr = NetError("$h: HTTP $code")
                delay(400L * (attempt + 1))
                continue
            }
            if (code != 200) throw HttpError(code, "$h: HTTP $code")
            return bytes
        }
        throw NetError("$h: ${lastErr?.javaClass?.simpleName ?: "failed"}")
    }

    private fun fetchOnce(url: String, body: String?, maxBytes: Int): Triple<Int, ByteArray, String?> {
        val conn = (if (proxy != null) URL(url).openConnection(proxy) else URL(url).openConnection()) as HttpURLConnection
        try {
            conn.instanceFollowRedirects = false
            conn.connectTimeout = minOf(timeoutMs, 8_000)
            conn.readTimeout = timeoutMs
            conn.useCaches = false
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/json, text/xml;q=0.9")
            if (body != null) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                val out = body.toByteArray(Charsets.UTF_8)
                conn.setFixedLengthStreamingMode(out.size)
                conn.outputStream.use { it.write(out) }
            }
            val code = conn.responseCode
            if (code != 200) return Triple(code, ByteArray(0), conn.getHeaderField("Retry-After"))
            val bytes = conn.inputStream.use { input ->
                val buf = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    buf.write(chunk, 0, n)
                    if (buf.size() > maxBytes) throw NetError("response too large")
                }
                buf.toByteArray()
            }
            return Triple(code, bytes, null)
        } finally {
            conn.disconnect()
        }
    }

    suspend fun getJson(url: String, params: Map<String, Any>? = null): Any = parseJson(url, get(url, params))

    suspend fun postJson(url: String, body: Any): Any = parseJson(url, post(url, body.toString()))

    private fun parseJson(url: String, bytes: ByteArray): Any {
        return try {
            JSONTokener(String(bytes, Charsets.UTF_8)).nextValue()
        } catch (e: Exception) {
            throw NetError("${runCatching { URI(url).host }.getOrNull()}: invalid JSON")
        }
    }

    suspend fun getObject(url: String, params: Map<String, Any>? = null): JSONObject =
        getJson(url, params) as? JSONObject ?: throw ValidationError("response: expected object")

    suspend fun getArray(url: String, params: Map<String, Any>? = null): JSONArray =
        getJson(url, params) as? JSONArray ?: throw ValidationError("response: expected array")

    suspend fun getText(url: String, params: Map<String, Any>? = null): String =
        String(get(url, params, 4 * 1024 * 1024), Charsets.UTF_8)

    companion object {
        val USER_AGENT = "maze-crypto-android/${BuildConfig.VERSION_NAME}"
        private const val DOWN_MS = 20_000L

        /** socks5://host:port, socks5h://host:port or http(s)://host:port. */
        fun parseProxy(url: String): Proxy {
            val u = URI(url.trim())
            val host = u.host ?: throw IllegalArgumentException("proxy must look like socks5://host:port")
            if (u.port <= 0) throw IllegalArgumentException("proxy must look like socks5://host:port")
            val type = when (u.scheme) {
                "socks5", "socks5h" -> Proxy.Type.SOCKS
                "http", "https" -> Proxy.Type.HTTP
                else -> throw IllegalArgumentException("proxy must look like socks5://host:port")
            }
            return Proxy(type, InetSocketAddress.createUnresolved(host, u.port))
        }
    }
}

// ── validation of everything that comes from the network ──────────────────

object V {
    private val SYMBOL_RE = Regex("^[A-Z0-9]{1,15}$")
    private val COIN_ID_RE = Regex("^[a-z0-9][a-z0-9._-]{0,99}$")
    private val FIAT_RE = Regex("^[A-Z]{3}$")

    fun number(value: Any?, name: String = "value", allowZero: Boolean = true, allowNegative: Boolean = false): Double {
        val f = when (value) {
            is Boolean -> throw ValidationError("$name: boolean is not a number")
            is Number -> value.toDouble()
            is String -> value.trim().toDoubleOrNull() ?: throw ValidationError("$name: not a number")
            else -> throw ValidationError("$name: unexpected type")
        }
        if (!f.isFinite()) throw ValidationError("$name: not finite")
        if (f < 0 && !allowNegative) throw ValidationError("$name: negative")
        if (f == 0.0 && !allowZero) throw ValidationError("$name: zero")
        return f
    }

    fun price(value: Any?, name: String = "price") = number(value, name, allowZero = false)

    fun optional(value: Any?, allowNegative: Boolean = false): Double? =
        if (value == null || value == JSONObject.NULL) null
        else runCatching { number(value, allowNegative = allowNegative) }.getOrNull()

    fun symbol(value: Any?): String {
        val s = value.toString().uppercase().trim()
        if (!SYMBOL_RE.matches(s)) throw ValidationError("bad symbol")
        return s
    }

    fun coinId(value: Any?): String {
        val s = value.toString().trim()
        if (!COIN_ID_RE.matches(s)) throw ValidationError("bad coin id")
        return s
    }

    fun fiat(value: Any?): String {
        val s = value.toString().uppercase().trim()
        if (!FIAT_RE.matches(s)) throw ValidationError("bad currency")
        return s
    }

    /** Build a candle and check it is internally consistent. */
    fun candle(ts: Any?, o: Any?, h: Any?, l: Any?, c: Any?, v: Any?): com.mazecrypto.app.core.Candle {
        var t = number(ts, "ts", allowZero = false).toLong()
        if (t > 10_000_000_000L) t /= 1000          // milliseconds -> seconds
        val op = price(o, "open")
        val hi = price(h, "high")
        val lo = price(l, "low")
        val cl = price(c, "close")
        val vol = number(v, "volume")
        if (hi < maxOf(op, cl, lo) * (1 - 1e-9) || lo > minOf(op, cl, hi) * (1 + 1e-9)) {
            throw ValidationError("candle high/low inconsistent")
        }
        return com.mazecrypto.app.core.Candle(t, op, hi, lo, cl, vol)
    }

    /** Sort by time and drop duplicate timestamps (keeping the last one). */
    fun candles(rows: List<com.mazecrypto.app.core.Candle>) =
        rows.associateBy { it.ts }.values.sortedBy { it.ts }
}

fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
fun JSONArray.arrays(): List<JSONArray> = (0 until length()).mapNotNull { optJSONArray(it) }
