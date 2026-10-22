package com.mazecrypto.app.core

import org.bouncycastle.crypto.digests.RIPEMD160Digest
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.math.ec.ECPoint
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Watch-only HD accounts (BIP-32 public derivation): an account's extended
 * public key — typed in, or read from a Ledger — gives every receive and change
 * address of that account without any private key ever being involved.
 *
 * Scripts: "pkh" (BIP-44, 1…/L…), "sh-wpkh" (BIP-49, 3…/M…), "wpkh" (BIP-84,
 * bc1q…/ltc1q…), "tr" (BIP-86 Taproot, bc1p…).
 */
object Hd {
    private val CURVE = CustomNamedCurves.getByName("secp256k1")
    private val G: ECPoint = CURVE.g
    private val N: BigInteger = CURVE.n

    const val XPUB = 0x0488B21E
    const val YPUB = 0x049D7CB2
    const val ZPUB = 0x04B24746
    const val LTUB = 0x019DA462
    const val MTUB = 0x01B26EF6

    val SCRIPTS = listOf("wpkh", "tr", "sh-wpkh", "pkh")

    class ExtKey(val version: Int, val depth: Int, val parentFp: Int, val child: Int, val chainCode: ByteArray, val pub: ByteArray) {
        val point: ECPoint by lazy { CURVE.curve.decodePoint(pub) }
        val fingerprint: Int get() = ByteBuffer.wrap(hash160(pub), 0, 4).int

        fun serialize(version: Int = this.version): String {
            val b = ByteBuffer.allocate(78)
            b.putInt(version); b.put(depth.toByte()); b.putInt(parentFp); b.putInt(child); b.put(chainCode); b.put(pub)
            return Base58.encodeCheck(b.array())
        }

        /** Non-hardened child; null for the (astronomically rare) invalid index, which BIP-32 says to skip. */
        fun child(i: Int): ExtKey? {
            require(i >= 0) { "hardened derivation needs the private key" }
            val mac = Mac.getInstance("HmacSHA512")
            mac.init(SecretKeySpec(chainCode, "HmacSHA512"))
            mac.update(pub)
            mac.update(ByteBuffer.allocate(4).putInt(i).array())
            val r = mac.doFinal()
            val il = BigInteger(1, r.copyOfRange(0, 32))
            if (il >= N) return null
            val k = G.multiply(il).add(point).normalize()
            if (k.isInfinity) return null
            return ExtKey(version, depth + 1, fingerprint, i, r.copyOfRange(32, 64), k.getEncoded(true))
        }
    }

    /** What an extended public key's version says: networks and the script it implies (null = ask). */
    class Kind(val chains: List<String>, val script: String?)

    fun kindOf(version: Int): Kind? = when (version) {
        XPUB -> Kind(listOf("bitcoin"), null)          // BIP-44 legacy and BIP-86 Taproot both export "xpub"
        YPUB -> Kind(listOf("bitcoin"), "sh-wpkh")
        ZPUB -> Kind(listOf("bitcoin", "litecoin"), "wpkh")   // some Litecoin wallets reuse zpub
        LTUB -> Kind(listOf("litecoin"), null)
        MTUB -> Kind(listOf("litecoin"), "sh-wpkh")
        else -> null
    }

    /** A checked extended public key, or null when [s] is not one (bad checksum, wrong length, off the curve). */
    fun parse(s: String): ExtKey? {
        val raw = Base58.decodeOrNull(s.trim()) ?: return null
        if (raw.size != 82 || !Base58.checksumOk(raw)) return null
        val b = ByteBuffer.wrap(raw, 0, 78)
        val version = b.int
        if (kindOf(version) == null) return null
        val depth = b.get().toInt() and 0xff
        val fp = b.int
        val child = b.int
        val cc = ByteArray(32).also { b.get(it) }
        val pub = ByteArray(33).also { b.get(it) }
        if (pub[0] != 2.toByte() && pub[0] != 3.toByte()) return null
        val k = ExtKey(version, depth, fp, child, cc, pub)
        return runCatching { k.point; k }.getOrNull()
    }

    /** The account key in the form Ledger and Sparrow show for [script] on [chain]. */
    fun display(k: ExtKey, chain: String, script: String): String = k.serialize(
        when {
            chain == "litecoin" && script == "sh-wpkh" -> MTUB
            chain == "litecoin" && script == "wpkh" -> ZPUB
            chain == "litecoin" -> LTUB
            script == "sh-wpkh" -> YPUB
            script == "wpkh" -> ZPUB
            else -> XPUB
        },
    )

    /** Address [index] on branch [change] (0 = receive, 1 = change) of the account. */
    fun address(account: ExtKey, chain: String, script: String, change: Int, index: Int): String? {
        val k = account.child(change)?.child(index) ?: return null
        return address(k.pub, chain, script)
    }

    fun address(pub: ByteArray, chain: String, script: String): String {
        val ltc = chain == "litecoin"
        return when (script) {
            "pkh" -> Base58.encodeCheck(byteArrayOf(if (ltc) 0x30 else 0x00) + hash160(pub))
            "sh-wpkh" -> Base58.encodeCheck(byteArrayOf(if (ltc) 0x32 else 0x05) + hash160(byteArrayOf(0x00, 0x14) + hash160(pub)))
            "wpkh" -> Bech32.encodeSegwit(if (ltc) "ltc" else "bc", 0, hash160(pub))
            "tr" -> {
                require(!ltc) { "Taproot is Bitcoin only" }
                Bech32.encodeSegwit("bc", 1, taprootOutput(pub))
            }
            else -> throw IllegalArgumentException("script $script")
        }
    }

    /** BIP-86: the output key is the internal key tweaked by its own tagged hash (no script path). */
    fun taprootOutput(pub: ByteArray): ByteArray {
        val x = pub.copyOfRange(1, 33)
        val p = CURVE.curve.decodePoint(byteArrayOf(0x02) + x)          // the even-y lift of the x-only key
        val t = BigInteger(1, taggedHash("TapTweak", x))
        require(t < N)
        val q = p.add(G.multiply(t)).normalize()
        return q.affineXCoord.encoded
    }

    fun taggedHash(tag: String, msg: ByteArray): ByteArray {
        val th = sha256(tag.toByteArray())
        return sha256(th + th + msg)
    }

    /** Compressed form of a 65-byte uncompressed (or already compressed) public key. */
    fun compress(pub: ByteArray): ByteArray = CURVE.curve.decodePoint(pub).getEncoded(true)

    fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

    fun hash160(b: ByteArray): ByteArray {
        val d = RIPEMD160Digest()
        val s = sha256(b)
        d.update(s, 0, s.size)
        return ByteArray(20).also { d.doFinal(it, 0) }
    }

    /** "m/84'/0'/0'" → hardened-flagged integers. */
    fun parsePath(path: String): IntArray {
        val parts = path.trim().removePrefix("m").trim('/').split('/').filter { it.isNotEmpty() }
        return parts.map { p ->
            val hard = p.endsWith("'") || p.endsWith("h")
            val n = p.trimEnd('\'', 'h').toLong()
            require(n in 0 until 0x80000000L)
            (if (hard) n or 0x80000000L else n).toInt()
        }.toIntArray()
    }

    fun pathString(path: IntArray): String = "m" + path.joinToString("") { i ->
        "/" + (i.toLong() and 0x7fffffffL) + if (i < 0) "'" else ""
    }
}
