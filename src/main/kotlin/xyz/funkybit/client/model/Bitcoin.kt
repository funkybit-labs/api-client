package xyz.funkybit.client.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.buildSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.encodeCollection
import xyz.funkybit.client.model.address.BitcoinAddress
import java.math.BigInteger
import java.util.UUID

@Serializable
data class BitcoinRpcRequest(
    val method: String,
    val params: BitcoinRpcParams = BitcoinRpcParams(emptyList<String>()),
    val id: String = UUID.randomUUID().toString(),
    val jsonrpc: String = "2.0",
)

object BitcoinRpcParamsSerializer : KSerializer<BitcoinRpcParams> {
    @OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)
    override val descriptor: SerialDescriptor = buildSerialDescriptor("RpcParams", StructureKind.LIST)

    override fun serialize(
        encoder: Encoder,
        value: BitcoinRpcParams,
    ) {
        when (val param = value.value) {
            is List<*> ->
                encoder.encodeCollection(descriptor, param.size) {
                    param.forEachIndexed { index, it ->
                        when (it) {
                            is String -> encodeSerializableElement(String.serializer().descriptor, index, String.serializer(), it)
                            is BitcoinAddress ->
                                encodeSerializableElement(
                                    String.serializer().descriptor,
                                    index,
                                    String.serializer(),
                                    it.value,
                                )
                            is Int -> encodeSerializableElement(Int.serializer().descriptor, index, Int.serializer(), it)
                            is Long -> encodeSerializableElement(Long.serializer().descriptor, index, Long.serializer(), it)
                            is Boolean -> encodeSerializableElement(Boolean.serializer().descriptor, index, Boolean.serializer(), it)
                        }
                    }
                }
        }
    }

    override fun deserialize(decoder: Decoder): BitcoinRpcParams = throw Exception("not required")
}

data class UnspentUtxo(
    val utxoId: BitcoinUtxoId,
    val btcAmount: BigInteger,
    val tokenName: String = "BTC",
    val runeAmount: BigInteger = BigInteger.ZERO,
) {
    val amount = if (tokenName == "BTC") btcAmount else runeAmount
}
