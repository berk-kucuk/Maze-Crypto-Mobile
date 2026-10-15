package com.mazecrypto.app.data

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * The last good response for every network request, so the app and the
 * widgets can show data while offline (the desktop keeps the same in SQLite).
 * One small file per key under the app's cache directory; values are JSON text.
 */
class Cache(private val dir: File) {
    private val mem = object : LinkedHashMap<String, Pair<Long, String>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, String>>?) = size > 96
    }

    init {
        dir.mkdirs()
    }

    private fun file(key: String): File {
        val h = MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir, "$h.json")
    }

    @Synchronized
    private fun read(key: String): Pair<Long, String>? {
        mem[key]?.let { return it }
        val f = file(key)
        if (!f.exists()) return null
        return runCatching {
            val o = JSONObject(f.readText())
            if (o.optString("k") != key) return null
            (o.getLong("ts") to o.getString("v")).also { mem[key] = it }
        }.getOrNull()
    }

    /** The cached value if it is younger than [ttlSeconds]. */
    fun fresh(key: String, ttlSeconds: Double): String? {
        val (ts, v) = read(key) ?: return null
        return if (System.currentTimeMillis() - ts < ttlSeconds * 1000) v else null
    }

    /** The cached value of any age, with when it was stored (epoch ms). */
    fun stale(key: String): Pair<Long, String>? = read(key)

    @Synchronized
    fun put(key: String, value: String) {
        val now = System.currentTimeMillis()
        mem[key] = now to value
        val f = file(key)
        val tmp = File(dir, f.name + ".tmp")
        runCatching {
            tmp.writeText(JSONObject().put("k", key).put("ts", now).put("v", value).toString())
            tmp.renameTo(f)
        }
    }

    @Synchronized
    fun clear() {
        mem.clear()
        dir.listFiles()?.forEach { it.delete() }
    }
}
