package com.mazecrypto.app

import com.mazecrypto.app.core.Address
import com.mazecrypto.app.core.Keccak
import com.mazecrypto.app.core.Leg
import com.mazecrypto.app.core.Trade
import com.mazecrypto.app.core.Transfer
import com.mazecrypto.app.core.buildPositions
import com.mazecrypto.app.core.mergeActivity
import com.mazecrypto.app.core.mergeWallets
import com.mazecrypto.app.core.summarize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private fun ok(s: String): Address.Result.Ok = Address.parse(s) as? Address.Result.Ok
        ?: throw AssertionError("expected valid: $s, got ${Address.parse(s)}")

    @Test fun keccakVectors() {
        assertEquals("c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470", hex(Keccak.k256(ByteArray(0))))
        assertEquals("4e03657aea45a94fc7d47ba826c8d667c0d1e6e33a64a036ec44f58fa12d6c45", hex(Keccak.k256("abc".toByteArray())))
        // Longer than one 136-byte block.
        assertEquals(32, Keccak.k256(ByteArray(300) { it.toByte() }).size)
    }

    @Test fun bitcoin() {
        assertEquals(listOf("bitcoin"), ok("bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh").chains)
        assertEquals(listOf("bitcoin"), ok("BC1QXY2KGDYGJRSQTZQ2N0YRF2493P83KKFJHX0WLH").chains)    // QR codes are upper case
        assertEquals(listOf("bitcoin"), ok("1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa").chains)
        assertEquals(listOf("bitcoin", "litecoin"), ok("3KMmeqPeQcngyTehdfSwsGqvxfU7J7qtc8").chains)
        // Taproot (bech32m, BIP-350 vector).
        assertEquals(listOf("bitcoin"), ok("bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqzk5jj0").chains)
        assertEquals("bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh", ok("bitcoin:bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh?amount=0.1").address)
        assertEquals(Address.Result.Bad("checksum"), Address.parse("bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlj"))
        assertEquals(Address.Result.Bad("checksum"), Address.parse("1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNb"))
        assertEquals(Address.Result.Bad("testnet"), Address.parse("tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx"))
        // A v0 program encoded with bech32m must be refused.
        assertEquals(Address.Result.Bad("checksum"), Address.parse("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kemeawh"))
    }

    @Test fun evm() {
        val r = ok("0x5aaeb6053f3e94c9b9a09f33669435e7ef1beaed")
        assertEquals("0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed", r.address)       // EIP-55 example
        assertEquals(Address.EVM_CHAINS, r.chains)
        assertEquals("0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045", ok("0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045").address)
        assertEquals(Address.Result.Bad("checksum"), Address.parse("0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAeD"))
        assertEquals(ok("ethereum:0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045@1/transfer?value=1").address, "0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045")
        assertEquals(Address.key("ethereum", "0xABCDEF0000000000000000000000000000000000"), Address.key("ethereum", "0xabcdef0000000000000000000000000000000000"))
    }

    @Test fun otherChains() {
        assertEquals(listOf("solana"), ok("9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM").chains)
        assertEquals(listOf("tron"), ok("TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t").chains)
        assertEquals(listOf("litecoin"), ok("LcNS6c8RddAMjewDrUAAi8BzecKoosnkN3").chains)
        assertEquals(listOf("litecoin"), ok("MS8g7KDz3atp82qrQidtqBXtpHimNr2Uwp").chains)
        // Solana is plain base58 of exactly 32 bytes; a truncated paste is not an address.
        assertTrue(Address.parse("9WzDXwBbmkg8ZTbNMqUxvQRAyrZz") is Address.Result.Bad)
    }

    @Test fun monero() {
        // The Monero general fund (a subaddress).
        val r = ok("888tNkZrPN6JsEgekjMnABU4TBzc2Dt29EPAvkRxbANsAnjyPbb3iQ1YBRk1UXcdRsiKc9dhwMVgN5S9cQUiyoogDavup3H")
        assertEquals(listOf("monero"), r.chains)
        assertEquals(Address.Result.Bad("checksum"),
            Address.parse("888tNkZrPN6JsEgekjMnABU4TBzc2Dt29EPAvkRxbANsAnjyPbb3iQ1YBRk1UXcdRsiKc9dhwMVgN5S9cQUiyoogDavup3J"))
    }

    @Test fun tronHex() {
        assertEquals("TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t", Address.tronFromHex("41a614f803b6fd780986a42c78ec9c7f77e6ded13c"))
        assertNull(Address.tronFromHex("deadbeef"))
    }

    @Test fun secretsAreRefused() {
        val seed = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        assertEquals(Address.Result.Secret("seed"), Address.parse(seed))
        assertEquals(Address.Result.Secret("seed"), Address.parse("  " + seed.replace(" ", "\n") + " "))
        assertEquals(Address.Result.Secret("private_key"), Address.parse("4c0883a69102937d6231471b5dbb6204fe5129617082792ae468d01a3f362318"))
        assertEquals(Address.Result.Secret("private_key"), Address.parse("0x4c0883a69102937d6231471b5dbb6204fe5129617082792ae468d01a3f362318"))
        // WIF (compressed and uncompressed, Bitcoin wiki examples).
        assertEquals(Address.Result.Secret("private_key"), Address.parse("5HueCGU8rMjxEXxiPuD5BDku4MkFqeZyd4dZ1jvhTVqvbTLvyTJ"))
        assertEquals(Address.Result.Secret("private_key"), Address.parse("KwdMAjGmerYanjeui5SHS7JkmpZvVipYvB2LJGU1ZxJwYvP98617"))
        assertEquals(Address.Result.Secret("xprv"), Address.parse("xprv9s21ZrQH143K3QTDL4LXw2F7HEK3wJUD2nW2nRk4stbPy6cq3jPPqjiChkVvvNKmPGJxWUtg6LnF5kejMRNNU3TGtRBeJgk33yuGBxrMPHi"))
        assertEquals(Address.Result.Secret("private_key"), Address.parse("[" + (1..64).joinToString(",") + "]"))
        assertTrue(Address.parse("xpub661MyMwAqRbcFtXgS5sYJABqqG9YLmC4Q1Rdap9gSE8NqtwybGhePY2gZ29ESFjqJoCu1Rupje8YtGqsefD265TMg7usUDFdp6W1EGMcet8") is Address.Result.Account)
        assertFalse(Address.parse("bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh") is Address.Result.Secret)
    }

    @Test fun walletAmountsKeepTradeCost() {
        val trades = listOf(
            Trade(1000, "bitcoin", "BTC", 1.0, 100.0, "USD", usdRate = 1.0, id = 1),
            Trade(2000, "bitcoin", "BTC", 1.0, 300.0, "USD", usdRate = 1.0, id = 2),
        )
        val pos = mergeWallets(buildPositions(trades, "USD", 1.0), mapOf("bitcoin" to ("BTC" to 0.5), "solana" to ("SOL" to 10.0)))
        val btc = pos.getValue("bitcoin")
        assertEquals(0.5, btc.amount, 1e-12)
        assertEquals(200.0, btc.avgCostFiat!!, 1e-9)      // the trades' average cost
        assertEquals(100.0, btc.costFiat, 1e-9)
        val sol = pos.getValue("solana")
        assertFalse(sol.costKnown)
        assertNull(sol.avgCostFiat)
        val s = summarize(pos, mapOf("bitcoin" to 400.0, "solana" to 20.0), mapOf("bitcoin" to 400.0, "solana" to 20.0))
        assertEquals(400.0, s.valueFiat, 1e-9)
        // P/L only over the coin whose cost is known: 0.5 × 400 − 100.
        assertEquals(100.0, s.pnlFiat, 1e-9)
        assertEquals(listOf("SOL"), s.unknownCost)
        assertNull(s.holdings.first { it.position.coinId == "solana" }.pnlFiat)
    }

    @Test fun activityBetweenOwnWalletsIsOneTransfer() {
        val a = Transfer("ethereum", "0x1", 100, "ok", listOf(Leg("ethereum", "ETH", -1.0)), fee = 0.001, feeSymbol = "ETH",
            counterparty = "0xB", walletIds = setOf("a"))
        val b = Transfer("ethereum", "0x1", 100, "ok", listOf(Leg("ethereum", "ETH", 1.0)), counterparty = "0xA", walletIds = setOf("b"))
        val other = Transfer("bitcoin", "f00", 0, "pending", listOf(Leg("bitcoin", "BTC", 0.1)), walletIds = setOf("c"))
        val m = mergeActivity(listOf(listOf(a), listOf(b, other)))
        assertEquals(2, m.size)
        assertEquals("pending", m[0].status)           // unconfirmed first
        assertEquals("self", m[1].direction)
        assertEquals(setOf("a", "b"), m[1].walletIds)
        assertEquals(0.001, m[1].fee!!, 1e-12)
        // UTXO: the sender's change includes the fee; only the fee left the user's addresses.
        val utxo = Transfer("bitcoin", "x", 5, "ok", listOf(Leg("bitcoin", "BTC", -0.0001)), fee = 0.0001, feeIncluded = true)
        assertEquals("self", utxo.direction)
        assertTrue(Transfer("bitcoin", "y", 5, "ok", listOf(Leg("bitcoin", "BTC", -0.5)), fee = 0.0001, feeIncluded = true).direction == "out")
    }

    @org.junit.Test
    fun hdAccounts() {
        // BIP-84 / BIP-86 test vectors ("abandon … about").
        val zpub = "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYs"
        val k = com.mazecrypto.app.core.Hd.parse(zpub)!!
        assertEquals("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", com.mazecrypto.app.core.Hd.address(k, "bitcoin", "wpkh", 0, 0))
        assertEquals("bc1q8c6fshw2dlwun7ekn9qwf37cu2rn755upcp6el", com.mazecrypto.app.core.Hd.address(k, "bitcoin", "wpkh", 1, 0))
        val xpub = "xpub6BgBgsespWvERF3LHQu6CnqdvfEvtMcQjYrcRzx53QJjSxarj2afYWcLteoGVky7D3UKDP9QyrLprQ3VCECoY49yfdDEHGCtMMj92pReUsQ"
        assertEquals("bc1p5cyxnuxmeuwuvkwfem96lqzszd02n6xdcjrs20cac6yqjjwudpxqkedrcr",
            com.mazecrypto.app.core.Hd.address(com.mazecrypto.app.core.Hd.parse(xpub)!!, "bitcoin", "tr", 0, 0))
    }
}
