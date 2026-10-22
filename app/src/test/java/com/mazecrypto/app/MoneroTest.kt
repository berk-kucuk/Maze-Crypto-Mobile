package com.mazecrypto.app

import com.mazecrypto.app.core.Base58
import com.mazecrypto.app.monero.Ed
import com.mazecrypto.app.monero.ViewWallet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Keys from a throwaway wallet made with monero-wallet-rpc --offline (never funded). */
class MoneroTest {
    private val primary = "41jV8SmnZa2KwmkeSV5U7p8rdTtbbqLZfCfYFuUdEo44JT9uxEPAqP85xccmqnGArkLXzrGEzfp3aiViwfUg5e8GLX6LqU9"
    private val sub01 = "88F66BSU2jq3HaLEh3gwGEAPcWjCXQHGzGJrMoGyPN5LcetHraTqXRk2xvjUZJzmeVXgHA7pvwnBU8dJVXwF6U7iJsnnV98"
    private val view = "d41d25229ad2ea1c8d24fba56fa38a482259399b8adb73195c65b2fc8f6d6407"
    private val spend = "fb70e56298e51aede6b715b6fdc09c80a2f71b1ae3b542374bcb61eaa0cba008"

    @Test
    fun viewKeyIsChecked() {
        assertNull(ViewWallet.check(primary, view))
        assertEquals("spend_key", ViewWallet.check(primary, spend))
        assertEquals("primary", ViewWallet.check(sub01, view))
        assertEquals("mismatch", ViewWallet.check(primary, "01" + view.substring(2)))
    }

    @Test
    fun subaddressesAreDerived() {
        val w = ViewWallet(primary, Ed.hex(view))
        val subSpend = Base58.decodeMonero(sub01)!!.copyOfRange(1, 33)
        assertEquals(0 to 1, w.subs[Ed.hex(subSpend)])
    }

    @Test
    fun pointRoundTrip() {
        val p = Ed.mulG(Ed.hex(view))
        val q = Ed.decode(Ed.encode(p))!!
        assertEquals(Ed.hex(Ed.encode(p)), Ed.hex(Ed.encode(q)))
        // (a + a)·G = a·G + a·G
        val two = Ed.reduce(Ed.le32(Ed.fromLe(Ed.hex(view)).shiftLeft(1)))
        assertEquals(Ed.hex(Ed.encode(Ed.mulG(two))), Ed.hex(Ed.encode(Ed.add(p, p))))
        assertEquals(Ed.hex(Ed.encode(Ed.mulG(two))), Ed.hex(Ed.encode(Ed.dbl(p))))
    }
}

/**
 * A real /get_blocks.bin response (482 blocks from 3787003, saved over Tor), scanned
 * with the Monero General Fund's published view key. Too big for the repository:
 * runs only when XMR_FIXTURE points at the file.
 */
class MoneroFixtureTest {
    @Test
    fun findsGeneralFundDonations() {
        val path = System.getenv("XMR_FIXTURE") ?: return
        val res = com.mazecrypto.app.monero.Epee.read(java.io.File(path).readBytes())
        @Suppress("UNCHECKED_CAST") val blocks = res["blocks"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST") val indices = res["output_indices"] as List<Map<String, Any?>>
        val w = ViewWallet(
            "44AFFq5kSiGBoZ4NMDwYtN18obc8AemS33DBLWs3H7otXft3XjrpDtQGv7SqSsaBYBb98uNbr2VBBEt7f2wfn3RVGQBEP3A",
            Ed.hex("f359631075708155cc3d92a32b75a7d02a5dcf27756707b47a2b31b21c389501"),
        )
        val st = com.mazecrypto.app.monero.ScanState()
        val t0 = System.currentTimeMillis()
        var txs = 0
        blocks.forEachIndexed { bi, e ->
            val b = com.mazecrypto.app.monero.Blob(e["block"] as ByteArray)
            val ts = b.header().timestamp
            val miner = b.tx(null)
            val hashes = b.hashes()
            @Suppress("UNCHECKED_CAST")
            val per = (indices[bi]["indices"] as List<Map<String, Any?>>).map { (it["indices"] as? List<*>).orEmpty().map { v -> v as Long } }
            val all = listOf(miner to per[0]) + (e["txs"] as? List<*>).orEmpty().mapIndexed { k, t ->
                com.mazecrypto.app.monero.Blob((t as Map<*, *>)["blob"] as ByteArray).tx(hashes[k]) to per[k + 1]
            }
            for ((tx, g) in all) { txs++; com.mazecrypto.app.monero.Scanner.apply(st, tx, com.mazecrypto.app.monero.Scanner.scan(w, tx), g, 3787003L + bi, ts) }
        }
        println("scanned $txs txs in ${System.currentTimeMillis() - t0} ms")
        val found = st.outputs.associate { it.txid to it.amount }
        assertEquals(12099870000L, found["b5aae9a6cc1f58c753fa244b630b6df77d11229d6201e2797d84d7b63add0587"])
        assertEquals(0 to 70, st.outputs.first().let { it.major to it.minor })
    }
}

class MoneroSpendTest {
    private fun out(tx: String, h: Long, g: Long, a: Long) =
        com.mazecrypto.app.monero.Owned(tx, h, 0, 0, g, a, 0, 0, false)

    @Test
    fun spendsResolveInAnyScanOrder() {
        // Scanning backwards: the later transaction (with change) is found first, the payment it spent later.
        val st = com.mazecrypto.app.monero.ScanState()
        st.outputs += out("bb", 200, 900, 3)                       // change of tx bb
        st.pays += com.mazecrypto.app.monero.Pay("bb", 200, 0, 7, listOf(longArrayOf(5, 100, 555, 801)))
        st.resolve()
        assertEquals(1, st.unspent.size)
        st.outputs += out("aa", 100, 555, 10)                      // found later, older: spent by bb
        st.resolve()
        assertEquals(listOf("bb"), st.unspent.map { it.txid })
        assertEquals("bb", st.outputs.first { it.txid == "aa" }.spentTx)
        assertEquals(7L, st.fees["bb"])
        // A ring member newer than the spending tx can never be what it spent.
        val st2 = com.mazecrypto.app.monero.ScanState()
        st2.outputs += out("cc", 300, 555, 10)
        st2.pays += com.mazecrypto.app.monero.Pay("bb", 200, 0, 7, listOf(longArrayOf(555)))
        st2.resolve()
        assertEquals(1, st2.unspent.size)
    }
}
