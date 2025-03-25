package xyz.funkybit.client.model

import kotlinx.datetime.Instant
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.buildSerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeCollection
import xyz.funkybit.client.utils.BigDecimalJson
import xyz.funkybit.client.utils.BigDecimalSerializer
import xyz.funkybit.client.utils.BigIntegerJson
import xyz.funkybit.client.utils.BigIntegerSerializer
import java.math.BigDecimal
import java.math.BigInteger

@Serializable
enum class SettlementStatus {
    Pending,
    Settling,
    PendingRollback,
    FailedSettling,
    Completed,
    Failed,
}

@Serializable
data class Trade(
    val id: TradeId,
    val timestamp: Instant,
    val orderId: OrderId,
    val clientOrderId: ClientOrderId?,
    val marketId: MarketId,
    val executionRole: ExecutionRole,
    val counterOrderId: OrderId,
    val side: OrderSide,
    val amount: BigIntegerJson,
    val price: BigDecimalJson,
    val feeAmount: BigIntegerJson,
    val feeSymbol: Symbol,
    val settlementStatus: SettlementStatus,
    val error: String? = null,
)

@Serializable
data class TradesApiResponse(
    val trades: List<Trade>,
)

@Serializable
data class MarketTradesApiResponse(
    val marketId: MarketId,
    val trades: List<MarketTrade>,
)

@Serializable(with = MarketTrade.AsArraySerializer::class)
data class MarketTrade(
    val id: TradeId,
    val type: OrderSide,
    val amount: BigIntegerJson,
    val price: BigDecimalJson,
    val notional: BigIntegerJson,
    val timestamp: Instant,
    val takerNickname: String,
    val takerId: BigIntegerJson,
) {
    object AsArraySerializer : KSerializer<MarketTrade> {
        private val tradeIdSerializer = kotlinx.serialization.serializer(TradeId::class.javaObjectType)
        private val orderSideSerializer = kotlinx.serialization.serializer(OrderSide::class.javaObjectType)
        private val amountSerializer = BigIntegerSerializer
        private val priceSerializer = BigDecimalSerializer
        private val notionalSerializer = BigIntegerSerializer
        private val timestampSerializer = kotlinx.serialization.serializer<Long>()
        private val takerNicknameSerializer = kotlinx.serialization.serializer<String>()
        private val takerIdSerializer = BigIntegerSerializer

        @OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)
        override val descriptor: SerialDescriptor =
            buildSerialDescriptor("MarketTradesCreated.Trade", StructureKind.LIST) {
                element("id", tradeIdSerializer.descriptor)
                element("type", orderSideSerializer.descriptor)
                element("amount", amountSerializer.descriptor)
                element("price", priceSerializer.descriptor)
                element("notional", notionalSerializer.descriptor)
                element("timestamp", timestampSerializer.descriptor)
                element("takerNickname", takerNicknameSerializer.descriptor)
                element("takerId", takerIdSerializer.descriptor)
            }

        override fun serialize(
            encoder: Encoder,
            value: MarketTrade,
        ) = encoder.encodeCollection(descriptor, 5) {
            encodeSerializableElement(tradeIdSerializer.descriptor, 0, tradeIdSerializer, value.id)
            encodeSerializableElement(orderSideSerializer.descriptor, 1, orderSideSerializer, value.type)
            encodeSerializableElement(amountSerializer.descriptor, 2, amountSerializer, value.amount)
            encodeSerializableElement(priceSerializer.descriptor, 3, priceSerializer, value.price)
            encodeSerializableElement(notionalSerializer.descriptor, 4, notionalSerializer, value.notional)
            encodeSerializableElement(timestampSerializer.descriptor, 5, timestampSerializer, value.timestamp.toEpochMilliseconds())
            encodeSerializableElement(takerNicknameSerializer.descriptor, 6, takerNicknameSerializer, value.takerNickname)
            encodeSerializableElement(takerIdSerializer.descriptor, 7, takerIdSerializer, value.takerId)
        }

        override fun deserialize(decoder: Decoder): MarketTrade =
            decoder.decodeStructure(descriptor) {
                var id: TradeId? = null
                var type: OrderSide? = null
                var amount: BigInteger? = null
                var price: BigDecimal? = null
                var notional: BigInteger? = null
                var timestamp: Instant? = null
                var takerNickname: String? = null
                var takerId: BigInteger? = null

                while (true) {
                    when (val index = decodeElementIndex(descriptor)) {
                        0 -> id = decodeSerializableElement(tradeIdSerializer.descriptor, 0, tradeIdSerializer) as TradeId
                        1 -> type = decodeSerializableElement(orderSideSerializer.descriptor, 1, orderSideSerializer) as OrderSide
                        2 -> amount = decodeSerializableElement(amountSerializer.descriptor, 2, amountSerializer)
                        3 -> price = decodeSerializableElement(priceSerializer.descriptor, 3, priceSerializer)
                        4 -> notional = decodeSerializableElement(notionalSerializer.descriptor, 4, notionalSerializer)
                        5 ->
                            timestamp =
                                Instant.fromEpochMilliseconds(
                                    decodeSerializableElement(timestampSerializer.descriptor, 5, timestampSerializer),
                                )
                        6 -> takerNickname = decodeSerializableElement(takerNicknameSerializer.descriptor, 6, takerNicknameSerializer)
                        7 -> takerId = decodeSerializableElement(takerIdSerializer.descriptor, 7, takerIdSerializer) as BigInteger
                        CompositeDecoder.DECODE_DONE -> break
                        else -> error("Unexpected index: $index")
                    }
                }
                MarketTrade(
                    id ?: throw SerializationException("Trade id is missing in json array"),
                    type = type ?: throw SerializationException("Trade type is missing in json array"),
                    amount = amount ?: throw SerializationException("Trade amount is missing in json array"),
                    price = price ?: throw SerializationException("Trade price is missing in json array"),
                    notional = notional ?: throw SerializationException("Trade notional is missing in json array"),
                    timestamp = timestamp ?: throw SerializationException("Trade timestamp is missing in json array"),
                    takerNickname = takerNickname ?: throw SerializationException("Trade taker nickname is missing in json array"),
                    takerId = takerId ?: throw SerializationException("Trade taker id is missing in json array"),
                )
            }
    }
}
