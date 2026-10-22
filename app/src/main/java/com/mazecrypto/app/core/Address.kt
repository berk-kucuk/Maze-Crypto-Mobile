package com.mazecrypto.app.core

import java.math.BigInteger
import java.security.MessageDigest

/**
 * Public wallet addresses: recognising, checking and normalising what the user
 * pastes — and refusing anything that is a secret.
 *
 * Every format is checked by its own checksum (Base58Check, bech32/bech32m,
 * EIP-55, Monero's Keccak checksum), so a typo or a truncated paste is caught
 * before an explorer is ever asked about it. Recovery phrases, private keys and
 * extended keys are recognised and rejected; the caller must drop the text.
 */
object Address {

    /** What a pasted string turned out to be. */
    sealed interface Result {
        /** A valid public address. [chains] are the networks it can belong to, most likely first. */
        data class Ok(val address: String, val chains: List<String>) : Result
        /** A secret: "seed", "private_key" or "xprv". Never store, never send, clear the field. */
        data class Secret(val kind: String) : Result
        /**
         * A watch-only HD account (xpub / ypub / zpub / Ltub / Mtub). [script] is null when the
         * version does not say (a plain xpub can be BIP-44 legacy or BIP-86 Taproot): ask.
         */
        data class Account(val xpub: String, val chains: List<String>, val script: String?) : Result
        /** Not usable: "empty", "testnet", "checksum" or "unknown". */
        data class Bad(val reason: String) : Result
    }

    /** Networks that use Ethereum-style 0x addresses; Hyperliquid (HyperCore + HyperEVM) included. */
    val EVM_CHAINS = listOf("ethereum", "base", "arbitrum", "optimism", "polygon", "bsc", "avalanche", "hyperliquid")

    private val SCHEMES = setOf("bitcoin", "litecoin", "ethereum", "solana", "tron", "monero")
    private val HEX40 = Regex("^0x[0-9a-fA-F]{40}$")
    private val HEX64 = Regex("^(0x)?[0-9a-fA-F]{64}$")
    private val WORD = Regex("^[a-zA-Z]{2,12}$")

    /** Strip a payment-URI wrapper (bitcoin:…?amount=, ethereum:0x…@1/transfer) and stray whitespace. */
    fun clean(input: String): String {
        var s = input.trim().trim('"', '\'', '<', '>')
        val colon = s.indexOf(':')
        if (colon > 0 && s.substring(0, colon).lowercase() in SCHEMES) {
            s = s.substring(colon + 1).removePrefix("//")
            s = s.substringBefore('?').substringBefore('@').substringBefore('/')
        }
        return s.trim()
    }

    /** Does [input] look like a secret? Checked on the raw text, before anything else. */
    fun secretKind(input: String): String? {
        val raw = input.trim()
        val words = raw.split(Regex("\\s+")).filter { it.isNotEmpty() }
        // BIP-39 phrases have 12-24 words, Monero's 25 (or 13 for MyMonero); be generous.
        if (words.size >= 11 && words.count { WORD.matches(it) } >= words.size - 1) return "seed"
        val s = clean(raw)
        if (HEX64.matches(s)) return "private_key"
        val lower = s.lowercase()
        if (listOf("xprv", "yprv", "zprv", "tprv", "uprv", "vprv", "ltpv", "ttpv").any { lower.startsWith(it) }) return "xprv"
        if (s.startsWith("[") && s.endsWith("]")) {
            // A Solana keypair exported as a JSON byte array.
            val n = s.trim('[', ']').split(',').mapNotNull { it.trim().toIntOrNull() }
            if (n.size >= 32 && n.all { it in 0..255 }) return "private_key"
        }
        val b = Base58.decodeOrNull(s)
        if (b != null) {
            // WIF private keys (Bitcoin 0x80, Litecoin 0xB0, testnet 0xEF), with a valid checksum.
            if ((b.size == 37 || b.size == 38) && Base58.checksumOk(b) &&
                (b[0] == 0x80.toByte() || b[0] == 0xB0.toByte() || b[0] == 0xEF.toByte())) return "private_key"
            // A Solana secret key (64 bytes: seed + public key).
            if (b.size == 64) return "private_key"
        }
        return null
    }

    fun parse(input: String): Result {
        if (input.isBlank()) return Result.Bad("empty")
        secretKind(input)?.let { return Result.Secret(it) }
        val s = clean(input)
        if (s.isEmpty() || s.length > 120 || s.any { it.isWhitespace() }) return Result.Bad("unknown")
        val lower = s.lowercase()

        if (listOf("tpub", "upub", "vpub").any { lower.startsWith(it) }) return Result.Bad("testnet")
        if (listOf("xpub", "ypub", "zpub", "ltub", "mtub").any { lower.startsWith(it) }) {
            val k = Hd.parse(s) ?: return Result.Bad("checksum")
            val kind = Hd.kindOf(k.version) ?: return Result.Bad("unknown")
            return Result.Account(s, kind.chains, kind.script)
        }

        // EVM: 0x + 40 hex. Mixed case must carry a valid EIP-55 checksum.
        if (HEX40.matches(s)) {
            val body = s.substring(2)
            val mixed = body.any { it.isUpperCase() } && body.any { it.isLowerCase() }
            if (mixed && eip55(body.lowercase()) != s) return Result.Bad("checksum")
            return Result.Ok(eip55(body.lowercase()), EVM_CHAINS)
        }

        // Segwit / Taproot.
        if (lower.startsWith("tb1") || lower.startsWith("bcrt1") || lower.startsWith("tltc1") || lower.startsWith("rltc1")) return Result.Bad("testnet")
        if (lower.startsWith("bc1") || lower.startsWith("ltc1")) {
            val hrp = if (lower.startsWith("bc1")) "bc" else "ltc"
            return if (Bech32.segwitOk(lower, hrp)) Result.Ok(lower, listOf(if (hrp == "bc") "bitcoin" else "litecoin"))
            else Result.Bad("checksum")
        }

        // Monero: its own base58 flavour; standard (4…), subaddress (8…), integrated (4…, 106 chars).
        if ((s.length == 95 || s.length == 106) && (s[0] == '4' || s[0] == '8') && s.all { it in Base58.ALPHABET }) {
            return if (moneroOk(s)) Result.Ok(s, listOf("monero")) else Result.Bad("checksum")
        }

        val b = Base58.decodeOrNull(s) ?: return Result.Bad("unknown")
        if (b.size == 25) {
            if (!Base58.checksumOk(b)) return Result.Bad("checksum")
            return when (b[0].toInt() and 0xff) {
                0x00 -> Result.Ok(s, listOf("bitcoin"))
                0x05 -> Result.Ok(s, listOf("bitcoin", "litecoin"))    // P2SH "3…": used by both
                0x30, 0x32 -> Result.Ok(s, listOf("litecoin"))
                0x41 -> Result.Ok(s, listOf("tron"))
                0x6f, 0xc4, 0x3a -> Result.Bad("testnet")
                else -> Result.Bad("unknown")
            }
        }
        if (b.size == 32 && s.length in 32..44) return Result.Ok(s, listOf("solana"))
        return Result.Bad("unknown")
    }

    /** The same address in a form that compares equal however it was typed. */
    fun key(chain: String, address: String): String =
        if (chain in EVM_CHAINS || address.startsWith("bc1") || address.startsWith("ltc1")) "$chain:${address.lowercase()}" else "$chain:$address"

    /** First and last characters: enough to recognise, short enough for a row. */
    fun short(address: String, head: Int = 6, tail: Int = 4): String =
        if (address.length <= head + tail + 1) address else address.take(head) + "…" + address.takeLast(tail)

    // ── EIP-55 ───────────────────────────────────────────────────────────────

    /** Mixed-case checksum encoding of a lowercase 40-hex address. */
    fun eip55(lowerHex: String): String {
        val hash = Keccak.k256(lowerHex.toByteArray(Charsets.US_ASCII))
        val sb = StringBuilder("0x")
        lowerHex.forEachIndexed { i, c ->
            val nibble = (hash[i / 2].toInt() shr (if (i % 2 == 0) 4 else 0)) and 0x0f
            sb.append(if (c.isLetter() && nibble >= 8) c.uppercaseChar() else c)
        }
        return sb.toString()
    }

    // ── Monero ───────────────────────────────────────────────────────────────

    fun moneroOk(s: String): Boolean {
        val data = Base58.decodeMonero(s) ?: return false
        if (data.size != 69 && data.size != 77) return false
        val prefix = data[0].toInt() and 0xff
        // 18 standard, 19 integrated, 42 subaddress (mainnet).
        if (prefix !in setOf(18, 19, 42)) return false
        if ((prefix == 19) != (data.size == 77)) return false
        val body = data.copyOfRange(0, data.size - 4)
        val sum = Keccak.k256(body).copyOfRange(0, 4)
        return sum.contentEquals(data.copyOfRange(data.size - 4, data.size))
    }

    // ── TRON hex ↔ base58 ────────────────────────────────────────────────────

    /** TronGrid reports addresses as 41-prefixed hex; show them the way wallets do (T…). */
    fun tronFromHex(hex: String): String? {
        val h = hex.removePrefix("0x")
        if (h.length != 42 || !h.startsWith("41") || h.any { it !in "0123456789abcdefABCDEF" }) return null
        val payload = BigInteger(h, 16).toByteArray().let { if (it.size > 21) it.copyOfRange(it.size - 21, it.size) else it }
        return Base58.encodeCheck(payload)
    }
}

object Base58 {
    const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private val INDEX = IntArray(128) { -1 }.also { a -> ALPHABET.forEachIndexed { i, c -> a[c.code] = i } }
    private val B58 = BigInteger.valueOf(58)

    fun decodeOrNull(s: String): ByteArray? {
        if (s.isEmpty() || s.length > 120) return null
        var n = BigInteger.ZERO
        for (c in s) {
            val d = if (c.code < 128) INDEX[c.code] else -1
            if (d < 0) return null
            n = n.multiply(B58).add(BigInteger.valueOf(d.toLong()))
        }
        val zeros = s.takeWhile { it == '1' }.length
        val body = n.toByteArray().let { if (it.isNotEmpty() && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
        val bytes = if (n.signum() == 0) ByteArray(0) else body
        return ByteArray(zeros) + bytes
    }

    fun encode(b: ByteArray): String {
        var n = BigInteger(1, b)
        val sb = StringBuilder()
        while (n.signum() > 0) {
            val qr = n.divideAndRemainder(B58)
            sb.append(ALPHABET[qr[1].toInt()])
            n = qr[0]
        }
        b.takeWhile { it == 0.toByte() }.forEach { _ -> sb.append('1') }
        return sb.reverse().toString()
    }

    private fun sha256d(b: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(md.digest(b))
    }

    fun checksumOk(b: ByteArray): Boolean {
        if (b.size < 5) return false
        val body = b.copyOfRange(0, b.size - 4)
        return sha256d(body).copyOfRange(0, 4).contentEquals(b.copyOfRange(b.size - 4, b.size))
    }

    fun encodeCheck(payload: ByteArray): String = encode(payload + sha256d(payload).copyOfRange(0, 4))

    // Monero splits data into 8-byte blocks of 11 characters; a short last block uses fewer.
    private val BLOCK_CHARS = intArrayOf(0, 2, 3, 5, 6, 7, 9, 10, 11)

    fun encodeMonero(data: ByteArray): String {
        val sb = StringBuilder()
        var i = 0
        while (i < data.size) {
            val n = minOf(8, data.size - i)
            var v = BigInteger(1, data.copyOfRange(i, i + n))
            val chars = CharArray(BLOCK_CHARS[n]) { ALPHABET[0] }
            var k = chars.size - 1
            while (v.signum() > 0 && k >= 0) {
                val qr = v.divideAndRemainder(B58)
                chars[k--] = ALPHABET[qr[1].toInt()]
                v = qr[0]
            }
            sb.append(chars)
            i += n
        }
        return sb.toString()
    }

    /** A standard (primary) Monero address from its two public keys. */
    fun moneroAddress(spendPub: ByteArray, viewPub: ByteArray): String {
        val body = byteArrayOf(18) + spendPub + viewPub
        return encodeMonero(body + Keccak.k256(body).copyOfRange(0, 4))
    }

    fun decodeMonero(s: String): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val len = minOf(11, s.length - i)
            val bytes = BLOCK_CHARS.indexOf(len)
            if (bytes <= 0) return null
            var n = BigInteger.ZERO
            for (c in s.substring(i, i + len)) {
                val d = if (c.code < 128) INDEX[c.code] else -1
                if (d < 0) return null
                n = n.multiply(B58).add(BigInteger.valueOf(d.toLong()))
            }
            if (n.bitLength() > bytes * 8) return null
            val raw = n.toByteArray()
            val block = ByteArray(bytes)
            val src = if (raw.size > bytes) raw.copyOfRange(raw.size - bytes, raw.size) else raw
            System.arraycopy(src, 0, block, bytes - src.size, src.size)
            out.write(block)
            i += len
        }
        return out.toByteArray()
    }
}

object Bech32 {
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val GEN = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
    private const val BECH32M = 0x2bc830a3

    private fun polymod(values: IntArray): Int {
        var chk = 1
        for (v in values) {
            val top = chk ushr 25
            chk = ((chk and 0x1ffffff) shl 5) xor v
            for (i in 0 until 5) if ((top shr i) and 1 == 1) chk = chk xor GEN[i]
        }
        return chk
    }

    private fun expand(hrp: String): IntArray =
        (hrp.map { it.code shr 5 } + 0 + hrp.map { it.code and 31 }).toIntArray()

    /** Encode a witness program (v0 → bech32, v1+ → bech32m). */
    fun encodeSegwit(hrp: String, version: Int, program: ByteArray): String {
        val data = mutableListOf(version)
        var acc = 0
        var bits = 0
        for (b in program) {
            acc = (acc shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                data += (acc shr bits) and 31
            }
        }
        if (bits > 0) data += (acc shl (5 - bits)) and 31
        val const = if (version == 0) 1 else BECH32M
        val pm = polymod(expand(hrp) + data.toIntArray() + IntArray(6)) xor const
        val chk = (0 until 6).map { (pm shr (5 * (5 - it))) and 31 }
        return hrp + "1" + (data + chk).joinToString("") { CHARSET[it].toString() }
    }

    /** A lowercase segwit address for [hrp]: right checksum flavour, witness version and program length. */
    fun segwitOk(addr: String, hrp: String): Boolean {
        if (addr.length > 90) return false
        val pos = addr.lastIndexOf('1')
        if (pos != hrp.length || addr.substring(0, pos) != hrp || addr.length - pos - 1 < 6) return false
        val data = addr.substring(pos + 1).map { CHARSET.indexOf(it) }
        if (data.any { it < 0 }) return false
        val chk = polymod(expand(hrp) + data.toIntArray())
        val version = data[0]
        if (version > 16) return false
        if (version == 0 && chk != 1) return false
        if (version > 0 && chk != BECH32M) return false
        // 5-bit groups to bytes, no padding left over.
        var acc = 0
        var bits = 0
        val prog = mutableListOf<Int>()
        for (v in data.subList(1, data.size - 6)) {
            acc = (acc shl 5) or v
            bits += 5
            while (bits >= 8) {
                bits -= 8
                prog += (acc shr bits) and 0xff
            }
        }
        if (bits >= 5 || ((acc shl (8 - bits)) and 0xff) != 0) return false
        if (prog.size !in 2..40) return false
        if (version == 0 && prog.size != 20 && prog.size != 32) return false
        return true
    }
}

/** Keccak-256 as Ethereum and Monero use it (the original padding, not NIST SHA3-256). */
object Keccak {
    private val RC = ulongArrayOf(
        0x0000000000000001uL, 0x0000000000008082uL, 0x800000000000808AuL, 0x8000000080008000uL,
        0x000000000000808BuL, 0x0000000080000001uL, 0x8000000080008081uL, 0x8000000000008009uL,
        0x000000000000008AuL, 0x0000000000000088uL, 0x0000000080008009uL, 0x000000008000000AuL,
        0x000000008000808BuL, 0x800000000000008BuL, 0x8000000000008089uL, 0x8000000000008003uL,
        0x8000000000008002uL, 0x8000000000000080uL, 0x000000000000800AuL, 0x800000008000000AuL,
        0x8000000080008081uL, 0x8000000000008080uL, 0x0000000080000001uL, 0x8000000080008008uL,
    ).map { it.toLong() }.toLongArray()
    private val ROT = intArrayOf(0, 1, 62, 28, 27, 36, 44, 6, 55, 20, 3, 10, 43, 25, 39, 41, 45, 15, 21, 8, 18, 2, 61, 56, 14)
    private const val RATE = 136

    private fun permute(a: LongArray) {
        val c = LongArray(5)
        val b = LongArray(25)
        for (round in 0 until 24) {
            for (x in 0 until 5) c[x] = a[x] xor a[x + 5] xor a[x + 10] xor a[x + 15] xor a[x + 20]
            for (x in 0 until 5) {
                val d = c[(x + 4) % 5] xor java.lang.Long.rotateLeft(c[(x + 1) % 5], 1)
                for (y in 0 until 5) a[x + 5 * y] = a[x + 5 * y] xor d
            }
            for (x in 0 until 5) for (y in 0 until 5) {
                b[y + 5 * ((2 * x + 3 * y) % 5)] = java.lang.Long.rotateLeft(a[x + 5 * y], ROT[x + 5 * y])
            }
            for (x in 0 until 5) for (y in 0 until 5) {
                a[x + 5 * y] = b[x + 5 * y] xor (b[(x + 1) % 5 + 5 * y].inv() and b[(x + 2) % 5 + 5 * y])
            }
            a[0] = a[0] xor RC[round]
        }
    }

    fun k256(input: ByteArray): ByteArray {
        val state = LongArray(25)
        val padded = ByteArray(((input.size / RATE) + 1) * RATE)
        System.arraycopy(input, 0, padded, 0, input.size)
        padded[input.size] = (padded[input.size].toInt() xor 0x01).toByte()
        padded[padded.size - 1] = (padded[padded.size - 1].toInt() xor 0x80).toByte()
        for (off in padded.indices step RATE) {
            for (i in 0 until RATE / 8) {
                var lane = 0L
                for (k in 0 until 8) lane = lane or ((padded[off + i * 8 + k].toLong() and 0xff) shl (8 * k))
                state[i] = state[i] xor lane
            }
            permute(state)
        }
        val out = ByteArray(32)
        for (i in 0 until 32) out[i] = (state[i / 8] ushr (8 * (i % 8))).toByte()
        return out
    }
}
