package com.mazecrypto.app.core

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * A public address the user watches, read-only. Nothing here can sign or move
 * funds: there is no key material anywhere in the app.
 *
 * [chain] is a network id ("bitcoin", "ethereum", "solana" …). Two chains have
 * no public balance lookup and take the amount from the user instead:
 * "monero" (balances are private by design) and "manual" (any coin, e.g. an
 * exchange account) — for those [coinId]/[symbol] name the coin and [amount]
 * is what the user entered.
 */
data class Wallet(
    val id: String,
    val chain: String,
    val address: String,
    val label: String = "",
    val coinId: String = "",
    val symbol: String = "",
    val name: String = "",
    val amount: Double? = null,
    /** False = watch only, left out of the portfolio (someone else's address, a cold-storage test …). */
    val inPortfolio: Boolean = true,
    val created: Long = 0,
) {
    val manual: Boolean get() = chain == "monero" || chain == "manual"

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("chain", chain); put("address", address); put("label", label)
        if (coinId.isNotEmpty()) { put("coin_id", coinId); put("symbol", symbol); put("name", name) }
        putNum("amount", amount); put("in_portfolio", inPortfolio); put("created", created)
    }

    companion object {
        fun fromJson(o: JSONObject) = Wallet(
            o.getString("id"), o.getString("chain"), o.optString("address"), o.optString("label"),
            o.optString("coin_id"), o.optString("symbol"), o.optString("name"), o.optDoubleOrNull("amount"),
            o.optBoolean("in_portfolio", true), o.optLong("created"),
        )
    }
}

/**
 * One coin or token held at an address. [coinId] is a CoinGecko id for coins
 * the app knows; tokens it can only price through the chain's own indexer get
 * a synthetic id (see [isSyntheticCoin]) and carry their USD price in [priceUsd].
 */
data class Asset(
    val coinId: String,
    val symbol: String,
    val name: String,
    val amount: Double,
    val priceUsd: Double? = null,
    val contract: String = "",
    /** Unconfirmed part of [amount] (Bitcoin/Litecoin mempool). */
    val pending: Double = 0.0,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", coinId); put("sym", symbol); put("name", name); put("amount", amount)
        putNum("usd", priceUsd); put("contract", contract); if (pending != 0.0) put("pending", pending)
    }

    companion object {
        fun fromJson(o: JSONObject) = Asset(
            o.getString("id"), o.getString("sym"), o.optString("name"), o.getDouble("amount"),
            o.optDoubleOrNull("usd"), o.optString("contract"), o.optDouble("pending", 0.0),
        )
    }
}

/** Synthetic ids start with "x-": never sent to CoinGecko, priced from the wallet data. */
fun isSyntheticCoin(coinId: String) = coinId.startsWith("x-")

data class WalletBalance(
    val assets: List<Asset>,
    val ts: Long,
    val txCount: Int? = null,
    /** Tokens left out: unknown, unpriced, flagged as scam or worth less than a cent. */
    val ignored: Int = 0,
) {
    fun toJson(): String = JSONObject().apply {
        put("assets", JSONArray().apply { assets.forEach { put(it.toJson()) } })
        put("ts", ts); txCount?.let { put("tx_count", it) }; put("ignored", ignored)
    }.toString()

    companion object {
        fun fromJson(s: String): WalletBalance {
            val o = JSONObject(s)
            val a = o.getJSONArray("assets")
            return WalletBalance(
                (0 until a.length()).map { Asset.fromJson(a.getJSONObject(it)) }, o.getLong("ts"),
                if (o.has("tx_count")) o.getInt("tx_count") else null, o.optInt("ignored"),
            )
        }
    }
}

/** How one transaction changed one coin's balance: positive = received. */
data class Leg(val coinId: String, val symbol: String, val amount: Double)

/**
 * A transaction as seen from the user's addresses. [ts] is in seconds, 0 while
 * unconfirmed. [fee] is what the user paid (only when one of their addresses
 * sent it), in [feeSymbol]. [feeIncluded] = the legs already contain the fee
 * (UTXO chains report the net change of the address).
 */
data class Transfer(
    val chain: String,
    val txid: String,
    val ts: Long,
    val status: String,           // ok, failed, pending
    val legs: List<Leg>,
    val fee: Double? = null,
    val feeSymbol: String = "",
    val feeIncluded: Boolean = false,
    val counterparty: String = "",
    val walletIds: Set<String> = emptySet(),
) {
    /** in, out, self (between the user's own addresses, or only a fee) or swap (both directions). */
    val direction: String get() {
        if (legs.isEmpty()) return "self"
        val ins = legs.any { it.amount > 0 }
        val outs = legs.any { it.amount < 0 }
        if (ins && outs) return "swap"
        if (outs && feeIncluded && fee != null && legs.size == 1 && abs(legs[0].amount) <= fee * 1.000001) return "self"
        return if (ins) "in" else "out"
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("chain", chain); put("txid", txid); put("ts", ts); put("status", status)
        put("legs", JSONArray().apply { legs.forEach { put(JSONArray().put(it.coinId).put(it.symbol).put(it.amount)) } })
        putNum("fee", fee); put("fee_sym", feeSymbol); put("fee_in", feeIncluded); put("cp", counterparty)
    }

    companion object {
        fun fromJson(o: JSONObject): Transfer {
            val l = o.getJSONArray("legs")
            return Transfer(
                o.getString("chain"), o.getString("txid"), o.getLong("ts"), o.getString("status"),
                (0 until l.length()).map { l.getJSONArray(it).let { a -> Leg(a.getString(0), a.getString(1), a.getDouble(2)) } },
                o.optDoubleOrNull("fee"), o.optString("fee_sym"), o.optBoolean("fee_in"), o.optString("cp"),
            )
        }

        fun listToJson(list: List<Transfer>): String = JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()
        fun listFromJson(s: String): List<Transfer> {
            val a = JSONArray(s)
            return (0 until a.length()).map { fromJson(a.getJSONObject(it)) }
        }
    }
}

/**
 * One feed from several addresses. A transaction that touches two of the
 * user's addresses on the same chain appears once, with the changes summed —
 * so moving coins between one's own wallets shows as a transfer to self, not
 * as a send and a receive. Newest first, unconfirmed on top.
 */
fun mergeActivity(lists: List<List<Transfer>>, limit: Int = 100): List<Transfer> {
    val byTx = LinkedHashMap<String, Transfer>()
    for (list in lists) for (t in list) {
        val k = t.chain + ":" + t.txid
        val prev = byTx[k]
        if (prev == null) {
            byTx[k] = t
            continue
        }
        val sums = LinkedHashMap<String, Leg>()
        (prev.legs + t.legs).forEach { l ->
            val s = sums[l.coinId]
            sums[l.coinId] = if (s == null) l else s.copy(amount = s.amount + l.amount)
        }
        byTx[k] = prev.copy(
            legs = sums.values.filter { abs(it.amount) > 1e-12 },
            fee = prev.fee ?: t.fee,
            counterparty = if (prev.legs.any { it.amount < 0 }) prev.counterparty else t.counterparty,
            walletIds = prev.walletIds + t.walletIds,
            status = if (prev.status == "pending" || t.status == "pending") "pending" else prev.status,
        )
    }
    return byTx.values.sortedWith(compareBy<Transfer> { it.ts != 0L }.thenByDescending { it.ts }).take(limit)
}

/**
 * Wallet balances become the portfolio's amounts.
 *
 * For every coin held in a wallet the position's amount is the on-chain (or
 * manually entered) total. Its cost basis comes from the trades, if any: the
 * trades' average cost times the wallet amount — so recording buys still gives
 * profit/loss, while a coin that was never recorded shows its value with an
 * unknown cost instead of a made-up profit. Coins only in trades stay as they are.
 */
fun mergeWallets(trades: Map<String, Position>, held: Map<String, Pair<String, Double>>): Map<String, Position> {
    val out = LinkedHashMap<String, Position>()
    trades.forEach { (k, v) -> if (k !in held) out[k] = v }
    for ((cid, symAmt) in held) {
        val (sym, amount) = symAmt
        val t = trades[cid]
        val p = Position(cid, t?.symbol ?: sym)
        p.amount = amount
        p.source = "wallet"
        val avg = t?.avgCostFiat
        if (t != null && avg != null) {
            p.costFiat = avg * amount
            p.costUsd = t.avgCostUsd?.let { it * amount }
        } else {
            p.costFiat = 0.0
            p.costUsd = null
            p.costKnown = false
        }
        if (t != null) {
            p.realizedFiat = t.realizedFiat
            p.realizedUsd = t.realizedUsd
            p.feesFiat = t.feesFiat
            p.trades = t.trades
        }
        out[cid] = p
    }
    return out
}
