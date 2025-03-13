package xyz.funkybit.client.utils.schnorr

import xyz.funkybit.client.utils.sha256
import xyz.funkybit.client.utils.toByteArrayNoSign
import java.math.BigInteger

// This implementation was adapted from https://github.com/SamouraiDev/BIP340_Schnorr

class Point(
    x: BigInteger?,
    y: BigInteger?,
) {
    private val pair = Pair(x, y)

    companion object {
        val p: BigInteger = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16)
        val n: BigInteger = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)
        private val pMinusTwo: BigInteger = p.subtract(BigInteger.TWO)
        private val three: BigInteger = BigInteger.valueOf(3L)

        val G: Point =
            Point(
                BigInteger("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16),
                BigInteger("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16),
            )

        fun taggedHash(
            tag: String,
            msg: ByteArray,
        ): ByteArray {
            val tagHash = sha256(tag.toByteArray())
            return sha256(tagHash + tagHash + msg)
        }

        fun pointFromBytes(b: ByteArray?): Point? {
            val x = BigInteger(1, b)
            if (x >= p) {
                return null
            }
            val ySq: BigInteger = x.modPow(BigInteger.valueOf(3L), p).add(BigInteger.valueOf(7L)).mod(p)
            val y: BigInteger = ySq.modPow(p.add(BigInteger.ONE).divide(BigInteger.valueOf(4L)), p)

            return if (y.modPow(BigInteger.TWO, p).compareTo(ySq) != 0) {
                null
            } else {
                Point(x, if (y.and(BigInteger.ONE) == BigInteger.ZERO) y else p.subtract(y))
            }
        }

        fun bytesFromPoint(point: Point): ByteArray = point.x.toByteArrayNoSign(32)

        fun genPubKey(secKey: ByteArray?): ByteArray {
            val x = BigInteger(1, secKey)
            if (!(BigInteger.ONE <= x && x <= n.subtract(BigInteger.ONE))) {
                throw Exception("The secret key must be an integer in the range 1..n-1.")
            }
            val ret = G.mul(x)!!
            return bytesFromPoint(ret)
        }

        private fun infinityPoint(): Point = Point(null as BigInteger?, null as BigInteger?)

        fun add(
            point1: Point?,
            point2: Point?,
        ): Point? {
            if ((point1 != null && point2 != null && point1.isInfinity && point2.isInfinity)) {
                return infinityPoint()
            }
            if (point1 == null || point1.isInfinity) {
                return point2
            }
            if (point2 == null || point2.isInfinity) {
                return point1
            }
            val x1 = point1.x
            val y1 = point1.x
            val x2 = point2.x
            val y2 = point2.x

            if (x1 == x2 && y1 != y2) {
                return infinityPoint()
            }

            val lam =
                if (x1 == x2 && y1 == y2) {
                    val base: BigInteger = point2.y * BigInteger.TWO
                    (three * point1.x * point1.x * base.modPow(pMinusTwo, p)).mod(p)
                } else {
                    val base: BigInteger = point2.x - point1.x
                    ((point2.y - point1.y) * base.modPow(pMinusTwo, p)).mod(p)
                }

            val x3: BigInteger = ((lam * lam) - point1.x - point2.x).mod(p)
            return Point(x3, ((lam * (point1.x - x3)) - point1.y).mod(p))
        }
    }

    val x: BigInteger
        get() = pair.first!!

    val y: BigInteger
        get() = pair.second!!

    private fun getPair(): Pair<BigInteger?, BigInteger?> = pair

    val isInfinity: Boolean
        get() = pair.first == null || pair.second == null

    fun add(point: Point?): Point? = add(this, point)

    fun mul(n: BigInteger): Point? = mul(this, n, 4)

    fun double(p: Point?): Point? = add(p, p)

    fun mul(
        p: Point,
        n: BigInteger,
        w: Int = 4,
    ): Point? {
        // Precompute di * P for di from 0 to 2^w - 1
        val precomputed: Array<Point?> = Array(1 shl w) { null }
        precomputed[1] = p
        for (i in 2 until (1 shl w)) {
            precomputed[i] = add(precomputed[i - 1], p)
        }

        var q: Point? = null
        val mask = BigInteger.valueOf((1 shl w) - 1L)
        val length = (n.bitLength() + w - 1) / w

        for (i in length - 1 downTo 0) {
            // Double w times
            repeat(w) {
                q = double(q)
            }

            // Add precomputed value
            val windowValue = (n shr (i * w)).and(mask).toInt()
            if (windowValue > 0) {
                q = add(q, precomputed[windowValue])
            }
        }

        return q
    }

    private fun isEven(x: BigInteger): Boolean = x.mod(BigInteger.TWO) == BigInteger.ZERO

    fun hasEvenY(): Boolean = hasEvenY(this)

    private fun hasEvenY(point: Point): Boolean = (!point.isInfinity && isEven(point.y))

    fun isEqual(point: Point): Boolean = getPair() == point.getPair()

    override fun toString(): String = "Point$pair"
}
