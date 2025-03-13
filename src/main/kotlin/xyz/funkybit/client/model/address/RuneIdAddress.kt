package xyz.funkybit.client.model.address

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

object RuneIdAddressSerializer : KSerializer<RuneIdAddress> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("RuneIdAddress", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: RuneIdAddress,
    ) = encoder.encodeString(value.value)

    override fun deserialize(decoder: Decoder) = RuneIdAddress.canonicalize(decoder.decodeString())
}

@Serializable(with = RuneIdAddressSerializer::class)
data class RuneIdAddress(
    val value: String,
) : Address() {
    override fun canonicalize() = Companion.canonicalize(this.value)

    override fun toString() = this.value

    override fun abbreviated(): String = this.value.take(6) + "..." + this.value.takeLast(4)

    companion object {
        fun canonicalize(value: String) = RuneIdAddress(value)
    }
}
