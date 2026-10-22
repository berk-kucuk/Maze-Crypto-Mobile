package com.mazecrypto.app.monero

import com.mazecrypto.app.core.Keccak
import org.bouncycastle.math.ec.rfc7748.X25519Field as F
import java.math.BigInteger

/**
 * The Ed25519 group as Monero uses it, for view-only scanning: point decoding
 * and encoding, addition, scalar multiplication, and Monero's hash-to-scalar.
 * Field arithmetic is BouncyCastle's (10 × 26-bit limbs); the curve formulas are
 * the extended twisted-Edwards ones (Hisil–Wong–Carter–Dawson, a = −1).
 *
 * Only public data and the private *view* key go through here — nothing that
 * could spend. Not constant-time, which is fine for that.
 */
object Ed {
    private val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    val L: BigInteger = BigInteger.ONE.shiftLeft(252).add(BigInteger("27742317777372353535851937790883648493"))

    private fun fe(b: BigInteger): IntArray = F.create().also { F.decode(le32(b), 0, it) }
    private fun one() = F.create().also { F.one(it) }

    private val D: IntArray = fe(BigInteger.valueOf(-121665).mod(P).multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P))
    private val D2: IntArray = F.create().also { F.add(D, D, it); F.carry(it) }

    class Point internal constructor(val x: IntArray, val y: IntArray, val z: IntArray, val t: IntArray) {
        fun copy() = Point(x.copyOf(), y.copyOf(), z.copyOf(), t.copyOf())
    }

    val IDENTITY = Point(F.create(), one(), one(), F.create())

    private fun mul(a: IntArray, b: IntArray) = F.create().also { F.mul(a, b, it) }
    private fun sqr(a: IntArray) = F.create().also { F.sqr(a, it) }

    /** Scratch field elements, one set per thread: the hot loops allocate nothing. */
    private class Tmp {
        val a = F.create(); val b = F.create(); val c = F.create(); val d = F.create()
        val e = F.create(); val f = F.create(); val g = F.create(); val h = F.create()
        val u = F.create(); val v = F.create()
    }
    private val TMP = ThreadLocal.withInitial { Tmp() }

    private fun sub(x: IntArray, y: IntArray, z: IntArray) { F.sub(x, y, z); F.carry(z) }
    private fun add(x: IntArray, y: IntArray, z: IntArray) { F.add(x, y, z); F.carry(z) }

    /** r = p + q (r may be p or q). */
    private fun addInto(r: Point, p: Point, q: Point, s: Tmp) {
        sub(p.y, p.x, s.u); sub(q.y, q.x, s.v); F.mul(s.u, s.v, s.a)
        add(p.y, p.x, s.u); add(q.y, q.x, s.v); F.mul(s.u, s.v, s.b)
        F.mul(p.t, D2, s.u); F.mul(s.u, q.t, s.c)
        F.mul(p.z, q.z, s.u); add(s.u, s.u, s.d)
        sub(s.b, s.a, s.e); sub(s.d, s.c, s.f); add(s.d, s.c, s.g); add(s.b, s.a, s.h)
        F.mul(s.e, s.f, r.x); F.mul(s.g, s.h, r.y); F.mul(s.f, s.g, r.z); F.mul(s.e, s.h, r.t)
    }

    /** r = 2p (r may be p). Signs folded as in [dbl]. */
    private fun dblInto(r: Point, p: Point, s: Tmp) {
        F.sqr(p.x, s.a); F.sqr(p.y, s.b)
        F.sqr(p.z, s.u); add(s.u, s.u, s.c)
        add(s.a, s.b, s.h)
        add(p.x, p.y, s.u); F.sqr(s.u, s.v); sub(s.h, s.v, s.e)
        sub(s.a, s.b, s.g)
        add(s.c, s.g, s.f)
        F.mul(s.e, s.f, r.x); F.mul(s.g, s.h, r.y); F.mul(s.f, s.g, r.z); F.mul(s.e, s.h, r.t)
    }

    fun add(p: Point, q: Point): Point = Point(F.create(), F.create(), F.create(), F.create()).also { addInto(it, p, q, TMP.get()) }

    fun dbl(p: Point): Point = Point(F.create(), F.create(), F.create(), F.create()).also { dblInto(it, p, TMP.get()) }

    fun neg(p: Point): Point = Point(F.create().also { F.negate(p.x, it) }, p.y, p.z, F.create().also { F.negate(p.t, it) })

    /** A compressed point, or null when the bytes are not on the curve. */
    fun decode(b: ByteArray): Point? {
        if (b.size != 32) return null
        val yb = b.copyOf()
        val sign = (yb[31].toInt() ushr 7) and 1
        yb[31] = (yb[31].toInt() and 0x7f).toByte()
        val y = F.create().also { F.decode(yb, 0, it) }
        val y2 = sqr(y)
        val u = F.create().also { F.copy(y2, 0, it, 0); F.subOne(it); F.carry(it) }
        val v = mul(D, y2).also { F.addOne(it); F.carry(it) }
        val x = F.create()
        if (!F.sqrtRatioVar(u, v, x)) return null
        F.normalize(x)
        if (sign == 1 && F.isZeroVar(x)) return null
        if ((x[0] and 1) != sign) { F.negate(x, x); F.normalize(x) }
        return Point(x, y, one(), mul(x, y))
    }

    fun encode(p: Point): ByteArray {
        val zi = F.create().also { F.invVar(p.z, it) }
        val x = mul(p.x, zi).also { F.normalize(it) }
        val y = mul(p.y, zi).also { F.normalize(it) }
        val out = ByteArray(32)
        F.encode(y, out, 0)
        out[31] = (out[31].toInt() or ((x[0] and 1) shl 7)).toByte()
        return out
    }

    private fun table(p: Point): Array<Point> {
        val t = Array(16) { IDENTITY }
        t[1] = p
        for (i in 2 until 16) t[i] = add(t[i - 1], p)
        return t
    }

    /** k·P for a 32-byte little-endian scalar, 4 bits at a time, in place. */
    fun mul(k: ByteArray, p: Point, tbl: Array<Point> = table(p)): Point {
        val s = TMP.get()
        val r = IDENTITY.copy()
        var started = false
        for (i in 63 downTo 0) {
            if (started) { dblInto(r, r, s); dblInto(r, r, s); dblInto(r, r, s); dblInto(r, r, s) }
            val nib = (k[i / 2].toInt() ushr (4 * (i % 2))) and 0xf
            if (nib != 0) { addInto(r, r, tbl[nib], s); started = true }
        }
        return r
    }

    val G: Point = decode(hex("5866666666666666666666666666666666666666666666666666666666666666"))!!
    private val G_TABLE = table(G)
    /** Monero's second generator for amount commitments. */
    val H: Point = decode(hex("8b655970153799af2aeadc9ff1add0ea6c7251d54154cfa92c173a0dd39c1f94"))!!
    private val H_TABLE = table(H)

    fun mulG(k: ByteArray): Point = mul(k, G, G_TABLE)
    fun mulH(k: ByteArray): Point = mul(k, H, H_TABLE)

    /** 8·P: clears the cofactor, as key derivations do. */
    fun mul8(p: Point): Point { val s = TMP.get(); val r = p.copy(); dblInto(r, r, s); dblInto(r, r, s); dblInto(r, r, s); return r }

    // ── scalars ──────────────────────────────────────────────────────────────

    fun le32(n: BigInteger): ByteArray {
        val be = n.toByteArray()
        val out = ByteArray(32)
        for (i in be.indices) {
            val j = be.size - 1 - i
            if (i < 32) out[i] = be[j]
        }
        return out
    }

    fun fromLe(b: ByteArray): BigInteger = BigInteger(1, b.reversedArray())

    /** sc_reduce32: a 32-byte value modulo the group order. */
    fun reduce(b: ByteArray): ByteArray = le32(fromLe(b).mod(L))

    /** A canonical scalar (< L, as private keys are). */
    fun isScalar(b: ByteArray): Boolean = b.size == 32 && fromLe(b) < L

    /** Monero's H_s: Keccak-256, reduced modulo L. */
    fun hs(data: ByteArray): ByteArray = reduce(Keccak.k256(data))

    fun varint(n: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var v = n
        while (v >= 0x80) { out.write(((v and 0x7f) or 0x80).toInt()); v = v ushr 7 }
        out.write(v.toInt())
        return out.toByteArray()
    }

    fun u64le(n: Long): ByteArray = ByteArray(32).also { for (i in 0 until 8) it[i] = (n ushr (8 * i)).toByte() }

    fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
}
