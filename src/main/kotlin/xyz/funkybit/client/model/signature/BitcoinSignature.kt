package xyz.funkybit.client.model.signature

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

object BitcoinSignatureSerializer : KSerializer<BitcoinSignature> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("BitcoinSignature", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: BitcoinSignature,
    ) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder) = BitcoinSignature.canonicalize(decoder.decodeString())
}

@Serializable(with = BitcoinSignatureSerializer::class)
sealed class BitcoinSignature : Signature() {
    companion object {
        fun canonicalize(value: String) = Unrecognized(value).canonicalize()
    }

    override fun canonicalize() =
        when {
            this.toString().length == 128 -> Schnorr(this.toString())
            else -> Unrecognized(this.toString())
        }

    data class Schnorr(
        val raw: String,
    ) : BitcoinSignature() {
        override fun toString() = raw

        override fun abbreviated() = raw.take(6) + "..." + raw.takeLast(4)
    }

    data class Unrecognized(
        val raw: String,
    ) : BitcoinSignature() {
        override fun toString() = raw

        override fun abbreviated() = raw.take(6) + "..." + raw.takeLast(4)
    }
}
