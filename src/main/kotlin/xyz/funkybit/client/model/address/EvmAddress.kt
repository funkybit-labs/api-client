package xyz.funkybit.client.model.address

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.web3j.crypto.Keys

object EvmAddressSerializer : KSerializer<EvmAddress> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("EvmAddress", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: EvmAddress,
    ) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder) = EvmAddress.canonicalize(decoder.decodeString())
}

@Serializable(with = EvmAddressSerializer::class)
data class EvmAddress(
    val value: String,
) : Address() {
    override fun canonicalize() = Companion.canonicalize(this.value)

    override fun toString() = this.value

    override fun abbreviated(): String = this.value.take(6) + "..." + this.value.takeLast(4)

    init {
        require(Keys.toChecksumAddress(value) == value) {
            "Invalid address format or not a checksum address"
        }
    }

    companion object {
        fun canonicalize(value: String) = EvmAddress(Keys.toChecksumAddress(value))

        val zero = EvmAddress("0x0000000000000000000000000000000000000000")
    }
}
