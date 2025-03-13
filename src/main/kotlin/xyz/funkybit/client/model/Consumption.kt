package xyz.funkybit.client.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeCollection

@Serializable(with = MarketConsumption.AsArraySerializer::class)
data class MarketConsumption(
    val baseAmount: Long,
    val quoteAmount: Long,
    val baseAmountLeft: Long,
    val quoteAmountLeft: Long,
) {
    object AsArraySerializer : KSerializer<MarketConsumption> {
        override val descriptor: SerialDescriptor = buildClassSerialDescriptor("MarketConsumption")

        override fun serialize(
            encoder: Encoder,
            value: MarketConsumption,
        ) {
            encoder.encodeCollection(descriptor, 4) {
                encodeLongElement(descriptor, 0, value.baseAmount)
                encodeLongElement(descriptor, 1, value.quoteAmount)
                encodeLongElement(descriptor, 2, value.baseAmountLeft)
                encodeLongElement(descriptor, 3, value.quoteAmountLeft)
            }
        }

        override fun deserialize(decoder: Decoder): MarketConsumption =
            decoder.decodeStructure(descriptor) {
                var baseAmount = 0L
                var quoteAmount = 0L
                var baseAmountLeft = 0L
                var quoteAmountLeft = 0L

                while (true) {
                    when (val index = decodeElementIndex(descriptor)) {
                        0 -> baseAmount = decodeLongElement(descriptor, 0)
                        1 -> quoteAmount = decodeLongElement(descriptor, 1)
                        2 -> baseAmountLeft = decodeLongElement(descriptor, 2)
                        3 -> quoteAmountLeft = decodeLongElement(descriptor, 3)
                        -1 -> break
                        else -> error("Unexpected index: $index")
                    }
                }

                MarketConsumption(
                    baseAmount = baseAmount,
                    quoteAmount = quoteAmount,
                    baseAmountLeft = baseAmountLeft,
                    quoteAmountLeft = quoteAmountLeft,
                )
            }
    }
}

@Serializable
data class GetConsumptionsApiResponse(
    val consumptions: List<MarketConsumption>,
)
