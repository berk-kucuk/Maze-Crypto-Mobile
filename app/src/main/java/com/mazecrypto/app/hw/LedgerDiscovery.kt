package com.mazecrypto.app.hw

import com.mazecrypto.app.core.Hd
import com.mazecrypto.app.core.Wallet
import com.mazecrypto.app.data.WalletService
import kotlinx.coroutines.CancellationException

/** An account found on the Ledger, before the user picks which to watch. */
class LedgerAccount(
    val chain: String,
    val index: Int,
    /** Bitcoin/Litecoin: the account key (zpub/xpub/…); others: the address. */
    val address: String,
    val path: IntArray,
    val script: String = "",
    /** Seen on chain; null when the lookup failed. */
    val used: Boolean?,
    /** Native coin balance when known (account chains: from the first addresses only). */
    val balance: Double? = null,
) {
    /** Monero: the exported private view key (never stored in the clear; goes to SecretBox on add). */
    var viewKey: ByteArray? = null
    val key: String get() = "$chain:$script:${index}"
    val pathText: String get() = Hd.pathString(path)
}

/**
 * Finds the accounts on a Ledger the way Ledger Live does: account 0, 1, 2 …
 * on Ledger Live's derivation paths, until the first one that has never been
 * used (shown too, as the "new account").
 */
object LedgerDiscovery {
    private const val MAX_ACCOUNTS = 8
    private const val PROBE_ADDRESSES = 5

    suspend fun discover(l: Ledger, chain: String, svc: WalletService, onFound: (List<LedgerAccount>) -> Unit): List<LedgerAccount> {
        val out = mutableListOf<LedgerAccount>()
        if (chain == "monero") {
            val m = l.moneroAccount()
            // The device may answer with a dummy key when the export is declined: it must match the address.
            val problem = com.mazecrypto.app.monero.ViewWallet.check(m.address, com.mazecrypto.app.monero.Ed.hex(m.viewKey))
            if (problem != null) throw LedgerError(if (problem == "mismatch") "xmr_view_declined" else "protocol")
            out += LedgerAccount("monero", 0, m.address, intArrayOf(), used = null).also { it.viewKey = m.viewKey }
            onFound(out.toList())
            return out
        }
        if (chain == "bitcoin" || chain == "litecoin") {
            for (script in LedgerNetworks.scriptsFor(chain)) {
                for (n in 0 until MAX_ACCOUNTS) {
                    val path = LedgerNetworks.path(chain, n, script)
                    val raw = Hd.parse(l.accountXpub(chain, path)) ?: throw LedgerError("protocol")
                    val shown = Hd.display(raw, chain, script)
                    val used = probeAccount(svc, raw, chain, script)
                    out += LedgerAccount(chain, n, shown, path, script, used)
                    onFound(out.toList())
                    if (used != true) break
                }
            }
        } else {
            for (n in 0 until MAX_ACCOUNTS) {
                val path = LedgerNetworks.path(chain, n)
                val address = when (chain) {
                    "ethereum" -> l.ethAddress(path)
                    "solana" -> l.solAddress(path)
                    "tron" -> l.tronAddress(path)
                    else -> throw LedgerError("bad_request")
                }
                val (used, bal) = probeAddress(svc, chain, address)
                out += LedgerAccount(chain, n, address, path, used = used, balance = bal)
                onFound(out.toList())
                if (used != true) break
            }
        }
        return out
    }

    private suspend fun probeAccount(svc: WalletService, k: Hd.ExtKey, chain: String, script: String): Boolean? = try {
        val recv = k.child(0)
        var used = false
        for (i in 0 until PROBE_ADDRESSES) {
            val a = recv?.child(i)?.let { Hd.address(it.pub, chain, script) } ?: continue
            if (svc.addressTxCount(chain, a) > 0) { used = true; break }
        }
        used
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        null
    }

    private suspend fun probeAddress(svc: WalletService, chain: String, address: String): Pair<Boolean?, Double?> = try {
        val b = svc.balance(Wallet("probe", chain, address), 300.0).value
        val native = b.assets.firstOrNull()?.amount
        ((b.txCount ?: 0) > 0 || b.assets.any { it.amount > 0 }) to native
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        null to null
    }

    /** Show the account on the device screen, for the user to compare. */
    suspend fun verify(l: Ledger, a: LedgerAccount) {
        when (a.chain) {
            "bitcoin" -> try {
                l.btcXpub(a.path, display = true)
            } catch (e: LedgerError) {
                if (e.code != "wrong_app") throw e
                l.legacyShowAddress(a.path + intArrayOf(0, 0), a.script)
            }
            "litecoin" -> l.legacyShowAddress(a.path + intArrayOf(0, 0), a.script)
            "ethereum" -> l.ethAddress(a.path, display = true)
            "solana" -> l.solAddress(a.path, display = true)
            "tron" -> l.tronAddress(a.path, display = true)
            "monero" -> l.moneroShowAddress()
        }
    }
}
