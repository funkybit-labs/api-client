package xyz.funkybit.client.model.address

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

object AddressSerializer : KSerializer<Address> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Address", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: Address,
    ) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder) = Address.auto(decoder.decodeString())
}

@Serializable(with = AddressSerializer::class)
sealed class Address {
    companion object {
        fun auto(value: String): Address =
            when {
                value.startsWith("0x") -> EvmAddress.canonicalize(value)
                value.contains(":") -> RuneIdAddress.canonicalize(value)
                else -> BitcoinAddress.canonicalize(value)
            }
    }

    abstract fun canonicalize(): Address

    abstract fun abbreviated(): String
}
