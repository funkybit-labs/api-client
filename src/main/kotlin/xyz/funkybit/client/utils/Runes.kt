package xyz.funkybit.client.utils

import kotlinx.serialization.Serializable
import org.bitcoinj.script.ScriptOpCodes
import java.io.ByteArrayOutputStream
import java.math.BigInteger

@Serializable
data class RuneId(
    val block: Long,
    val tx: Int,
) {
    companion object {
        fun fromString(value: String): RuneId =
            try {
                val parts = value.split(":")
                RuneId(parts[0].toLong(), parts[1].toInt())
            } catch (e: Exception) {
                throw IllegalArgumentException(e.message)
            }

        fun isRuneId(value: String): Boolean =
            try {
                fromString(value)
                true
            } catch (e: Exception) {
                false
            }
    }

    override fun toString() = "$block:$tx"

    fun delta(next: RuneId): Pair<Long, Int> {
        val blockDelta = next.block - this.block
        return Pair(
            blockDelta,
            if (blockDelta == 0L) {
                next.tx - this.tx
            } else {
                next.tx
            },
        )
    }
}

data class SpacedRune(
    val rune: String,
    val spacers: Int?,
) {
    override fun toString(): String =
        if (spacers != null) {
            val result = StringBuilder()

            for ((i, c) in rune.withIndex()) {
                result.append(c)

                if (i < rune.length - 1 && spacers and (1 shl i) != 0) {
                    result.append("•")
                }
            }
            result.toString()
        } else {
            rune
        }

    companion object {
        fun fromString(value: String): SpacedRune {
            var spacers = 0
            var numSpacers = 0
            for ((i, c) in value.withIndex()) {
                if (c == '•') {
                    numSpacers += 1
                    spacers += 1 shl (i - numSpacers)
                }
            }
            return SpacedRune(value.replace("•", ""), if (spacers != 0) spacers else null)
        }
    }
}

data class Etching(
    val divisibility: UByte?,
    val premine: BigInteger?,
    val rune: String?,
    val spacers: UInt?,
    val symbol: Char?,
    val terms: String?,
    val turbo: Boolean,
)

data class Edict(
    val id: RuneId,
    val amount: BigInteger,
    val output: UInt,
)

data class Runestone(
    val edicts: List<Edict>,
    val etching: Etching?,
    val mint: List<RuneId>,
    val pointer: UInt?,
) {
    @OptIn(ExperimentalStdlibApi::class)
    fun encipher(): String {
        // this is an abbreviated encipher which just handles edicts in the body
        val sortedEdicts =
            edicts
                .sortedWith(compareBy({ it.id.block }, { it.id.tx }))

        val payload = ByteArrayOutputStream()
        payload.writeBytes(encodeVarInt(Tag.BODY.number.toBigInteger()))

        var previous = RuneId(0, 0)
        sortedEdicts.forEach { edict ->
            val (block, tx) = previous.delta(edict.id)
            payload.writeBytes(encodeVarInt(block.toBigInteger()))
            payload.writeBytes(encodeVarInt(tx.toBigInteger()))
            payload.writeBytes(encodeVarInt(edict.amount))
            payload.writeBytes(encodeVarInt(edict.output.toInt().toBigInteger()))
            previous = edict.id
        }

        return ScriptOpCodes.OP_RETURN.toString(16) +
            ScriptOpCodes.OP_13.toString(16) +
            BitcoinSignatureUtils.opPushData(payload.toByteArray().toHexString())
    }
}

enum class Tag(
    val number: Int,
) {
    BODY(0),
    FLAGS(2),
    RUNE(4),

    PREMINE(6),
    CAP(8),
    AMOUNT(10),
    HEIGHT_START(12),
    HEIGHT_END(14),
    OFFSET_START(16),
    OFFSET_END(18),
    MINT(20),
    POINTER(22),
    CENOTAPH(126),
    NOP(127),
}

private fun encodeVarInt(value: BigInteger): ByteArray {
    var mutableValue = value
    val v = mutableListOf<Byte>()

    while (mutableValue.shiftRight(7) > BigInteger.ZERO) {
        v.add((mutableValue.and(BigInteger.valueOf(0x7F)).toInt() or 0x80).toByte())
        mutableValue = mutableValue.shiftRight(7)
    }
    v.add(mutableValue.and(BigInteger.valueOf(0x7F)).toByte())

    return v.toByteArray()
}
