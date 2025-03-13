package xyz.funkybit.client.utils

import xyz.funkybit.client.utils.schnorr.Point
import java.math.BigInteger

object TaprootUtils {
    // From BIP-341
    //
    // def taproot_tweak_pubkey(pubkey, h):
    //    t = int_from_bytes(tagged_hash("TapTweak", pubkey + h))
    //    if t >= SECP256K1_ORDER:
    //        raise ValueError
    //    P = lift_x(int_from_bytes(pubkey))
    //    if P is None:
    //        raise ValueError
    //    Q = point_add(P, point_mul(G, t))
    //    return 0 if has_even_y(Q) else 1, bytes_from_int(x(Q))
    //
    fun tweakPubkey(
        pubkey: ByteArray,
        h: ByteArray = ByteArray(0),
    ): ByteArray {
        val t =
            BigInteger(
                1,
                Point.taggedHash("TapTweak", pubkey + h),
            )
        if (t > Point.n) {
            throw Exception("unable to tweak pubkey, t > Point.n")
        }
        val p = Point.pointFromBytes(pubkey) ?: throw Exception("unable to tweak pubkey, p is null")
        val q = Point.G.mul(t)?.add(p) ?: throw Exception("unable to tweak pubkey, q is null")
        return q.x.toByteArrayNoSign()
    }

    //
    // def taproot_tweak_seckey(seckey0, h):
    //    seckey0 = int_from_bytes(seckey0)
    //    P = point_mul(G, seckey0)
    //    seckey = seckey0 if has_even_y(P) else SECP256K1_ORDER - seckey0
    //    t = int_from_bytes(tagged_hash("TapTweak", bytes_from_int(x(P)) + h))
    //    if t >= SECP256K1_ORDER:
    //        raise ValueError
    //    return bytes_from_int((seckey + t) % SECP256K1_ORDER)
    //
    fun tweakSeckey(
        secKey: ByteArray,
        h: ByteArray = ByteArray(0),
    ): ByteArray {
        val seckey0 =
            BigInteger(
                1,
                secKey,
            )
        val p = Point.G.mul(seckey0) ?: throw Exception("unable to tweak seckey, p is null")
        val seckey1 = if (p.hasEvenY()) seckey0 else Point.n.subtract(seckey0)
        val t =
            BigInteger(
                1,
                Point.taggedHash("TapTweak", p.x.toByteArrayNoSign() + h),
            )
        if (t > Point.n) {
            throw Exception("unable to tweak seckey, t > Point.n")
        }
        return (seckey1 + t).mod(Point.n).toByteArrayNoSign()
    }
}
