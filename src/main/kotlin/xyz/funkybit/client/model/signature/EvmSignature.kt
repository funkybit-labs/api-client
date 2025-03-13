package xyz.funkybit.client.model.signature

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import xyz.funkybit.client.utils.toHex
import xyz.funkybit.client.utils.toHexBytes

object EvmSignatureSerializer : KSerializer<EvmSignature> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("EvmSignature", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: EvmSignature,
    ) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder) = EvmSignature.canonicalize(decoder.decodeString())
}

@Serializable(with = EvmSignatureSerializer::class)
data class EvmSignature(
    val value: String,
) : Signature() {
    override fun canonicalize() = Companion.canonicalize(this.value)

    override fun toString() = this.value

    override fun abbreviated() = this.value.take(6) + "..." + this.value.takeLast(4)

    companion object {
        fun canonicalize(value: String) = EvmSignature(value)

        fun emptySignature(): EvmSignature = ByteArray(65).toHex().toEvmSignature()
    }

    fun toByteArray() = value.toHexBytes()
}

fun String.toEvmSignature(): EvmSignature = EvmSignature(this)
