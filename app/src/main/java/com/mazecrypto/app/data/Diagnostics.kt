package com.mazecrypto.app.data

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build

/**
 * The last time Android had to stop Maze Crypto for not responding (an ANR),
 * with the stack of its main thread — read from the system's own exit record
 * (Android 11+). Nothing is sent anywhere: the user copies it if they want to
 * report the freeze.
 */
object Diagnostics {
    class Report(val time: Long, val text: String)

    fun lastFreeze(ctx: Context): Report? {
        if (Build.VERSION.SDK_INT < 30) return null
        return runCatching {
            val am = ctx.getSystemService(ActivityManager::class.java) ?: return null
            val info = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 16)
                .firstOrNull { it.reason == ApplicationExitInfo.REASON_ANR } ?: return null
            val trace = info.traceInputStream?.bufferedReader()?.use { it.readText() } ?: ""
            Report(info.timestamp, summarize(info.description ?: "", trace))
        }.getOrNull()
    }

    /** The main thread and every thread running the app's own code; the rest is noise for this. */
    private fun summarize(description: String, trace: String): String {
        val out = StringBuilder()
        out.append("Maze Crypto ").append(com.mazecrypto.app.BuildConfig.VERSION_NAME)
            .append(" · Android ").append(Build.VERSION.RELEASE).append(" · ").append(Build.MODEL).append('\n')
        out.append(description).append("\n\n")
        for (block in trace.split("\n\n")) {
            val head = block.lineSequence().firstOrNull() ?: continue
            if (head.startsWith("\"main\"") || "com.mazecrypto" in block) out.append(block.trim()).append("\n\n")
            if (out.length > 30_000) break
        }
        return out.toString().take(30_000)
    }
}
