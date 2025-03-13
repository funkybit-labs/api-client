package xyz.funkybit.client.model.signature

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

object SignatureSerializer : KSerializer<Signature> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Signature", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: Signature,
    ) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder) = Signature.auto(decoder.decodeString())
}

@Serializable(with = SignatureSerializer::class)
sealed class Signature {
    companion object {
        fun auto(value: String): Signature =
            when {
                value.startsWith("0x") -> EvmSignature.canonicalize(value)
                else -> BitcoinSignature.canonicalize(value)
            }
    }

    abstract fun canonicalize(): Signature

    abstract fun abbreviated(): String
}
