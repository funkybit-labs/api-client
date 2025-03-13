package xyz.funkybit.client.utils

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import org.bouncycastle.util.encoders.Hex
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

fun sha256(b: ByteArray?): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(b)
}

fun doubleSha256(b: ByteArray?) = sha256(sha256(b))

// this is to match arch - they sha256 but then second sha256 is of the hex string of the first sha256
fun doubleSha256FromHex(b: ByteArray?) = sha256(sha256(b).toHex(false).toByteArray())

fun generateRandomBytes(length: Int): ByteArray {
    val bytes =
        ByteArray(length).also {
            SecureRandom().nextBytes(it)
        }
    return bytes
}

fun generateHexString(length: Int = 64): String {
    val alphaChars = ('0'..'9').toList().toTypedArray() + ('a'..'f').toList().toTypedArray()
    return (1..length).map { alphaChars.random().toChar() }.toMutableList().joinToString("")
}

fun generateOrderNonce() = generateHexString(32)

@OptIn(ExperimentalStdlibApi::class)
fun ByteArray.toHex(add0x: Boolean = true) = (if (add0x) "0x" else "") + this.toHexString(HexFormat.Default)

@OptIn(ExperimentalStdlibApi::class, ExperimentalUnsignedTypes::class)
fun UByteArray.toHex(add0x: Boolean = true) = (if (add0x) "0x" else "") + this.toHexString(HexFormat.Default)

fun String.toHexBytes() = Hex.decode(this.replace("0x", ""))

fun Instant.truncateTo(unit: DateTimeUnit.TimeBased): Instant =
    when (unit) {
        DateTimeUnit.NANOSECOND -> throw IllegalArgumentException("Truncation of nanoseconds is not supported")
        else -> Instant.fromEpochMilliseconds(toEpochMilliseconds().let { it - it % unit.duration.inWholeMilliseconds })
    }

fun ByteArray.toPaddedHexString(length: Int) = joinToString("") { "%02X".format(it) }.padStart(length, '0')

fun ByteArray.pad(length: Int) = Hex.decode(this.toPaddedHexString(length * 2))

fun BigInteger.toByteArrayNoSign(len: Int = 32): ByteArray {
    val byteArray = this.toByteArray()
    return when {
        byteArray.size == len + 1 && byteArray[0].compareTo(0) == 0 -> byteArray.slice(IntRange(1, byteArray.size - 1)).toByteArray()
        byteArray.size < len -> byteArray.pad(len)
        else -> byteArray
    }
}
