package com.mazecrypto.app.hw

import com.mazecrypto.app.core.Address
import com.mazecrypto.app.core.Base58
import com.mazecrypto.app.core.Hd
import java.nio.ByteBuffer

/**
 * What Maze Crypto asks a Ledger: which app is open, and public keys —
 * extended public keys for Bitcoin/Litecoin accounts, addresses for Ethereum,
 * Solana and TRON. Nothing is ever signed: the app has no command that could
 * move funds, and the device shows the user anything that leaves it when
 * "verify on device" is used.
 */
class Ledger(private val t: Transport) {

    class AppInfo(val name: String, val version: String) {
        val dashboard: Boolean get() = name == "BOLOS"
    }

    private suspend fun send(cla: Int, ins: Int, p1: Int, p2: Int, data: ByteArray = ByteArray(0), timeoutMs: Long = 60_000): ByteArray {
        val r = t.exchange(Framing.apdu(cla, ins, p1, p2, data), timeoutMs)
        if (r.size < 2) throw LedgerError("protocol")
        val sw = (r[r.size - 2].toInt() and 0xff shl 8) or (r[r.size - 1].toInt() and 0xff)
        if (sw != 0x9000) throw LedgerError.of(sw)
        return r.copyOf(r.size - 2)
    }

    suspend fun app(): AppInfo {
        val r = send(0xB0, 0x01, 0, 0, timeoutMs = 10_000)
        if (r.isEmpty() || r[0].toInt() != 1) throw LedgerError("protocol")
        var i = 1
        val nl = r[i++].toInt() and 0xff
        val name = String(r, i, nl, Charsets.US_ASCII); i += nl
        val vl = r[i++].toInt() and 0xff
        val version = String(r, i, vl, Charsets.US_ASCII)
        return AppInfo(name, version)
    }

    /** From the dashboard: start [name] (the user confirms on the device). The USB link restarts afterwards. */
    suspend fun openApp(name: String) { send(0xE0, 0xD8, 0, 0, name.toByteArray(Charsets.US_ASCII), timeoutMs = 120_000) }

    suspend fun quitApp() { runCatching { send(0xB0, 0xA7, 0, 0, timeoutMs = 10_000) } }

    private fun pathData(path: IntArray): ByteArray {
        val b = ByteBuffer.allocate(1 + 4 * path.size)
        b.put(path.size.toByte())
        path.forEach { b.putInt(it) }
        return b.array()
    }

    // ── Bitcoin app (current protocol, CLA 0xE1) ────────────────────────────

    /** The account's extended public key, as an "xpub" string. [display]: also show it on the device. */
    suspend fun btcXpub(path: IntArray, display: Boolean = false): String {
        val r = send(0xE1, 0x00, 0, 0, byteArrayOf(if (display) 1 else 0) + pathData(path), timeoutMs = if (display) 180_000 else 30_000)
        return String(r, Charsets.US_ASCII)
    }

    // ── Bitcoin-family legacy protocol (Litecoin app, older Bitcoin app; CLA 0xE0 INS 0x40) ─────

    private class LegacyKey(val pub: ByteArray, val address: String, val chainCode: ByteArray)

    private suspend fun legacyKey(path: IntArray, display: Boolean = false, format: Int = 0): LegacyKey {
        val r = send(0xE0, 0x40, if (display) 1 else 0, format, pathData(path), timeoutMs = if (display) 180_000 else 30_000)
        var i = 0
        val pl = r[i++].toInt() and 0xff
        val pub = r.copyOfRange(i, i + pl); i += pl
        val al = r[i++].toInt() and 0xff
        val address = String(r, i, al, Charsets.US_ASCII); i += al
        return LegacyKey(pub, address, r.copyOfRange(i, i + 32))
    }

    /** An account's extended public key built from two legacy calls (the key and its parent, for the fingerprint). */
    suspend fun legacyXpub(path: IntArray, version: Int): String {
        val k = legacyKey(path)
        val parentFp = if (path.size > 1) {
            ByteBuffer.wrap(Hd.hash160(Hd.compress(legacyKey(path.copyOf(path.size - 1)).pub)), 0, 4).int
        } else 0
        return Hd.ExtKey(version, path.size, parentFp, path.last(), k.chainCode, Hd.compress(k.pub)).serialize()
    }

    /** The account key for [chain]: the current protocol where the app has it, else the legacy one. */
    suspend fun accountXpub(chain: String, path: IntArray): String =
        if (chain == "bitcoin") {
            try {
                btcXpub(path)
            } catch (e: LedgerError) {
                if (e.code != "wrong_app") throw e
                legacyXpub(path, Hd.XPUB)
            }
        } else legacyXpub(path, Hd.XPUB)

    /** Show an address of the account on the device screen (legacy protocol: Litecoin, older Bitcoin apps). */
    suspend fun legacyShowAddress(path: IntArray, script: String): String =
        legacyKey(path, display = true, format = when (script) { "sh-wpkh" -> 1; "wpkh" -> 2; "tr" -> 3; else -> 0 }).address

    // ── Ethereum app (also every EVM network and Hyperliquid) ───────────────

    suspend fun ethAddress(path: IntArray, display: Boolean = false): String {
        val r = send(0xE0, 0x02, if (display) 1 else 0, 0, pathData(path), timeoutMs = if (display) 180_000 else 30_000)
        val pl = r[0].toInt() and 0xff
        val al = r[1 + pl].toInt() and 0xff
        val hex = String(r, 2 + pl, al, Charsets.US_ASCII).lowercase().removePrefix("0x")
        require(hex.length == 40 && hex.all { it in "0123456789abcdef" })
        return Address.eip55(hex)
    }

    // ── Solana app ───────────────────────────────────────────────────────────

    suspend fun solAddress(path: IntArray, display: Boolean = false): String {
        val r = send(0xE0, 0x05, if (display) 1 else 0, 0, pathData(path), timeoutMs = if (display) 180_000 else 30_000)
        require(r.size == 32)
        return Base58.encode(r)
    }

    // ── TRON app ─────────────────────────────────────────────────────────────

    suspend fun tronAddress(path: IntArray, display: Boolean = false): String {
        val r = send(0xE0, 0x02, if (display) 1 else 0, 0, pathData(path), timeoutMs = if (display) 180_000 else 30_000)
        val pl = r[0].toInt() and 0xff
        val al = r[1 + pl].toInt() and 0xff
        val a = String(r, 2 + pl, al, Charsets.US_ASCII)
        require((Address.parse(a) as? Address.Result.Ok)?.chains == listOf("tron"))
        return a
    }

    // ── Monero app ───────────────────────────────────────────────────────────

    class MoneroAccount(val address: String, val viewKey: ByteArray)

    private var moneroCla = 0x03

    /** Monero's own APDU layout: an options byte (0) before the data. Older apps answer CLA 0x00 instead of 0x03. */
    private suspend fun xmr(ins: Int, p1: Int, data: ByteArray = ByteArray(0), timeoutMs: Long = 30_000): ByteArray {
        for (cla in listOf(moneroCla, 0x00, 0x04).distinct()) {
            try {
                return send(cla, ins, p1, 0, byteArrayOf(0) + data, timeoutMs).also { moneroCla = cla }
            } catch (e: LedgerError) {
                if (e.sw != 0x6e00) throw e
            }
        }
        throw LedgerError("wrong_app")
    }

    /**
     * The wallet's primary address and its private *view* key, for a view-only
     * wallet. The device asks the user to allow exporting the view key; the
     * spend key never leaves it.
     */
    suspend fun moneroAccount(): MoneroAccount {
        xmr(0x02, 0, "0.18.4.0".toByteArray(Charsets.US_ASCII))              // INS_RESET with the client version
        val pub = xmr(0x20, 1)                                               // INS_GET_KEY: public keys
        if (pub.size < 64) throw LedgerError("protocol")
        val viewPub = pub.copyOfRange(0, 32)
        val spendPub = pub.copyOfRange(32, 64)
        val sec = xmr(0x20, 2, timeoutMs = 180_000)                          // INS_GET_KEY: export view key (confirm on device)
        if (sec.size < 32) throw LedgerError("protocol")
        return MoneroAccount(Base58.moneroAddress(spendPub, viewPub), sec.copyOfRange(0, 32))
    }

    /** Show the primary address on the device screen. */
    suspend fun moneroShowAddress() {
        xmr(0x21, 0, ByteArray(8), timeoutMs = 180_000)                    // INS_DISPLAY_ADDRESS, account 0 / index 0
    }

    fun close() = t.close()

    companion object {
        fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
    }
}

/** A network the Ledger screen can read, and the app on the device that serves it. */
class LedgerNetwork(val id: String, val app: String, val coin: String)

object LedgerNetworks {
    val ALL = listOf(
        LedgerNetwork("bitcoin", "Bitcoin", "BTC"),
        LedgerNetwork("ethereum", "Ethereum", "ETH"),
        LedgerNetwork("solana", "Solana", "SOL"),
        LedgerNetwork("tron", "Tron", "TRX"),
        LedgerNetwork("monero", "Monero", "XMR"),
        LedgerNetwork("litecoin", "Litecoin", "LTC"),
    )

    fun of(id: String) = ALL.first { it.id == id }

    /** Purpose per script, as Ledger Live derives accounts. */
    val PURPOSE = mapOf("wpkh" to 84, "tr" to 86, "sh-wpkh" to 49, "pkh" to 44)
    val COIN_TYPE = mapOf("bitcoin" to 0, "litecoin" to 2, "ethereum" to 60, "solana" to 501, "tron" to 195, "monero" to 128)

    fun scriptsFor(chain: String) = if (chain == "litecoin") listOf("wpkh", "sh-wpkh", "pkh") else listOf("wpkh", "tr", "sh-wpkh", "pkh")

    private const val H = 0x80000000.toInt()

    /** Ledger Live's path of account [n]. */
    fun path(chain: String, n: Int, script: String = ""): IntArray {
        val coin = COIN_TYPE.getValue(chain) or H
        return when (chain) {
            "bitcoin", "litecoin" -> intArrayOf(PURPOSE.getValue(script) or H, coin, n or H)
            "solana" -> intArrayOf(44 or H, coin, n or H)
            else -> intArrayOf(44 or H, coin, n or H, 0, 0)            // Ethereum, TRON
        }
    }
}
