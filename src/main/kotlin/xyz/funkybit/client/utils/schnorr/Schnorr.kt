package xyz.funkybit.core.utils.schnorr

import xyz.funkybit.client.utils.generateRandomBytes
import xyz.funkybit.client.utils.schnorr.Point
import xyz.funkybit.client.utils.toByteArrayNoSign
import java.math.BigInteger
import kotlin.experimental.xor

/*
* This implementation was adapted from https://github.com/SamouraiDev/BIP340_Schnorr &&
* modified to follow https://github.com/bitcoin/bips/blob/master/bip-0340/reference.py
*/

object Schnorr {
    //   From BIP340 Specification for signing (https://github.com/bitcoin/bips/blob/master/bip-0340.mediawiki)
    //
    //   The algorithm Sign(sk, m) is defined as:
    //    1. Let d' = int(sk)
    //    2. Fail if d' = 0 or d' ≥ n
    //    3. Let P = d'⋅G
    //    4. Let d = d' if has_even_y(P), otherwise let d = n - d' .
    //    5. Let t be the byte-wise xor of bytes(d) and hashBIP0340/aux(a)[11].
    //    6. Let rand = hashBIP0340/nonce(t || bytes(P) || m)[12].
    //    7. Let k' = int(rand) mod n[13].
    //    8. Fail if k' = 0.
    //    9. Let R = k'⋅G.
    //    10. Let k = k' if has_even_y(R), otherwise let k = n - k' .
    //    11. Let e = int(hashBIP0340/challenge(bytes(R) || bytes(P) || m)) mod n.
    //    12. Let sig = bytes(R) || bytes((k + ed) mod n).
    //    13. If Verify(bytes(P), m, sig) (see below) returns failure, abort[14].
    //    14. Return the signature sig.
    //

    fun sign(
        m: ByteArray,
        sk: ByteArray,
        auxRand: ByteArray,
        verifySignature: Boolean = false,
    ): ByteArray {
        // 1. Let d' = int(sk)
        val d0 = BigInteger(1, sk)

        // 2. Fail if d' = 0 or d' ≥ n
        if (d0 == BigInteger.ZERO || d0 >= Point.n) {
            throw Exception("The secret key must be an integer in the range 1..n-1.")
        }

        // 3. Let P = d'⋅G
        val p = Point.G.mul(d0)!!

        // 4. Let d = d' if has_even_y(P), otherwise let d = n - d'
        val d = if (p.hasEvenY()) d0 else Point.n.subtract(d0)

        // 5. Let t be the byte-wise xor of bytes(d) and hashBIP0340/aux(a)[11].
        val t = xorBytes(d.toByteArrayNoSign(), Point.taggedHash("BIP0340/aux", auxRand))

        // 6. Let rand = hashBIP0340/nonce(t || bytes(P) || m)[12].
        val rand = Point.taggedHash("BIP0340/nonce", t + Point.bytesFromPoint(p) + m)

        // 7. Let k' = int(rand) mod n[13].
        val k0 = BigInteger(1, rand).mod(Point.n)

        // 8. Fail if k' = 0.
        if (k0.compareTo(BigInteger.ZERO) == 0) {
            throw Exception("Failure. This happens only with negligible probability.")
        }

        // 9. Let R = k'⋅G.
        val r = Point.G.mul(k0)!!

        // 10. Let k = k' if has_even_y(R), otherwise let k = n - k' .
        val k = if (r.hasEvenY()) k0 else Point.n.subtract(k0)

        // 11. Let e = int(hashBIP0340/challenge(bytes(R) || bytes(P) || m)) mod n
        val e = BigInteger(1, Point.taggedHash("BIP0340/challenge", Point.bytesFromPoint(r) + Point.bytesFromPoint(p) + m)).mod(Point.n)

        // 12 Let sig = bytes(R) || bytes((k + ed) mod n)
        val sig = Point.bytesFromPoint(r) + (k + e * d).mod(Point.n).toByteArrayNoSign()

        // 13. If Verify(bytes(P), m, sig) (see below) returns failure, abort[14]
        if (verifySignature && !verify(m, Point.bytesFromPoint(p), sig)) {
            throw Exception("The created signature does not pass verification")
        }

        //  14 Return the signature sig
        return sig
    }

    // From BIP340 for verification
    //
    // The algorithm Verify(pk, m, sig) is defined as:
    // 1. Let P = lift_x(int(pk)); fail if that fails.
    // 2. Let r = int(sig[0:32]); fail if r ≥ p.
    // 3. Let s = int(sig[32:64]); fail if s ≥ n.
    // 4. Let e = int(hashBIP0340/challenge(bytes(r) || bytes(P) || m)) mod n.
    // 5. Let R = s⋅G - e⋅P.
    // 6. Fail if is_infinite(R).
    // 7. Fail if not has_even_y(R).
    // 8. Fail if x(R) ≠ r.
    // 9. Return success iff no failure occurred before reaching this point.
    //
    fun verify(
        m: ByteArray,
        pk: ByteArray,
        sig: ByteArray,
    ): Boolean {
        if (pk.size != 32) {
            throw Exception("The public key must be a 32-byte array.")
        }
        if (sig.size != 64) {
            throw Exception("The signature must be a 64-byte array.")
        }

        // 1. Let P = lift_x(int(pk)); fail if that fails.
        val p = Point.pointFromBytes(pk) ?: return false

        // 2. Let r = int(sig[0:32]); fail if r ≥ p.
        val r = BigInteger(1, sig.slice(0..31).toByteArray())
        if (r >= Point.p) {
            return false
        }

        // 3. Let s = int(sig[32:64]); fail if s ≥ n.
        val s = BigInteger(1, sig.slice(32..63).toByteArray())
        if (s >= Point.n) {
            return false
        }

        // 4. Let e = int(hashBIP0340/challenge(bytes(r) || bytes(P) || m)) mod n.
        val e =
            BigInteger(
                1,
                Point.taggedHash(
                    "BIP0340/challenge",
                    sig.slice(0..31).toByteArray() + pk + m,
                ),
            ).mod(Point.n)

        // 5. Let R = s⋅G - e⋅P.
        val r1 = Point.G.mul(s)!!.add(p.mul(Point.n.subtract(e)))

        // 6. Fail if is_infinite(R).
        // 7. Fail if not has_even_y(R).
        // 8. Fail if x(R) ≠ r.
        // 9.Return success if no failure occurred before reaching this point.
        return !(r1 == null || !r1.hasEvenY() || r1.x.compareTo(r) != 0)
    }

    private fun xorBytes(
        in1: ByteArray,
        in2: ByteArray,
    ): ByteArray {
        if (in1.size != 32 && in2.size != 32) {
            throw Exception("Invalid bytes for xor")
        }
        return in1.mapIndexed { index, byte -> byte.xor(in2[index]) }.toByteArray()
    }

    fun sign(
        m: ByteArray,
        sk: ByteArray,
    ): ByteArray = sign(m, sk, generateRandomBytes(32))
}
