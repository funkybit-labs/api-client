package xyz.funkybit.client.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

object TxHashSerializer : KSerializer<TxHash> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("TxHash", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: TxHash,
    ) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder) = TxHash.auto(decoder.decodeString())
}

@Serializable(with = TxHashSerializer::class)
sealed class TxHash {
    companion object {
        fun auto(value: String): TxHash =
            when {
                value.startsWith("0x") -> EvmTxHash(value)
                else -> BitcoinTxHash(value)
            }
    }

    abstract fun abbreviated(): String
}

data class BitcoinTxHash(
    val value: String,
) : TxHash() {
    override fun toString() = value

    override fun abbreviated() = value.take(6) + "..." + value.takeLast(4)
}

data class EvmTxHash(
    val value: String,
) : TxHash() {
    override fun toString() = value

    override fun abbreviated() = value.take(6) + "..." + value.takeLast(4)
}
