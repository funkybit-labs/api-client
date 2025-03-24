package xyz.funkybit.client.model

import kotlinx.datetime.Instant
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import xyz.funkybit.client.model.signature.Signature
import xyz.funkybit.client.utils.BigDecimalJson
import xyz.funkybit.client.utils.BigIntegerJson
import java.math.BigInteger

@Serializable
@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
sealed class OrderAmount {
    @Serializable
    @SerialName("fixed")
    data class Fixed(
        val value: BigIntegerJson,
    ) : OrderAmount()

    @Serializable
    @SerialName("percent")
    data class Percent(
        val value: Percentage,
    ) : OrderAmount()

    fun negate(): OrderAmount =
        when (this) {
            is Fixed -> Fixed(this.value.negate())
            else -> this
        }

    fun fixedAmount(): BigIntegerJson =
        when (this) {
            is Fixed -> this.value
            else -> BigInteger.ZERO
        }

    fun percentage(): Int? =
        when (this) {
            is Percent -> this.value
            else -> null
        }
}

@Serializable
enum class OrderType {
    Market,
    Limit,
    BackToBackMarket,
}

@Serializable
enum class OrderSide {
    Buy,
    Sell,
}

@Serializable
enum class OrderStatus {
    Open,
    Partial,
    Filled,
    Cancelled,
    Expired,
    Rejected,
    Failed,
    ;

    fun isFinal(): Boolean = this in listOf(Filled, Cancelled, Expired, Failed, Rejected)

    fun isError(): Boolean = this in listOf(Failed, Rejected)
}

@Serializable
data class OrderSlippageTolerance(
    val expectedNotionalWithFee: BigIntegerJson,
    val maxDeviation: BigDecimalJson,
)

@Serializable
@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
sealed class CreateOrderApiRequest {
    abstract val nonce: String
    abstract val marketId: MarketId
    abstract val side: OrderSide
    abstract val amount: OrderAmount
    abstract val signature: Signature
    abstract val signingAddress: String
    abstract val verifyingChainId: String
    abstract val clientOrderId: ClientOrderId
    abstract val captchaToken: String?

    @Serializable
    @SerialName("market")
    data class Market(
        override val nonce: String,
        override val marketId: MarketId,
        override val side: OrderSide,
        override val amount: OrderAmount,
        override val signature: Signature,
        override val signingAddress: String,
        override val verifyingChainId: String,
        override val clientOrderId: ClientOrderId,
        override val captchaToken: String? = null,
        val slippageTolerance: OrderSlippageTolerance? = null,
        val baseTokenContractAddress: String? = null,
    ) : CreateOrderApiRequest()

    @Serializable
    @SerialName("backToBackMarket")
    data class BackToBackMarket(
        override val nonce: String,
        override val marketId: MarketId,
        val secondMarketId: MarketId,
        override val side: OrderSide,
        override val amount: OrderAmount,
        override val signature: Signature,
        override val signingAddress: String,
        override val verifyingChainId: String,
        override val clientOrderId: ClientOrderId,
        override val captchaToken: String? = null,
    ) : CreateOrderApiRequest()

    @Serializable
    @SerialName("limit")
    data class Limit(
        override val nonce: String,
        override val marketId: MarketId,
        override val side: OrderSide,
        override val amount: OrderAmount,
        val price: BigDecimalJson,
        override val signature: Signature,
        override val signingAddress: String,
        override val verifyingChainId: String,
        override val clientOrderId: ClientOrderId,
        override val captchaToken: String? = null,
    ) : CreateOrderApiRequest()
}

@Serializable
data class CreateOrderApiResponse(
    val orderId: OrderId,
    val clientOrderId: ClientOrderId?,
    val requestStatus: RequestStatus,
    val error: ApiError?,
    val order: CreateOrderApiRequest,
)

@Serializable
enum class RequestStatus {
    Accepted,
    Rejected,
}

@Serializable
data class CancelOrderApiRequest(
    val orderId: OrderId,
    val marketId: MarketId,
    val side: OrderSide,
    val amount: BigIntegerJson,
    val nonce: String,
    val signature: Signature,
    val signingAddress: String,
    val verifyingChainId: String,
)

@Serializable
data class CancelOrderApiResponse(
    val orderId: OrderId,
    val requestStatus: RequestStatus,
    val error: ApiError?,
)

@Serializable
enum class ExecutionRole {
    Taker,
    Maker,
}

@Serializable
@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
sealed class Order {
    abstract val id: OrderId
    abstract val clientOrderId: ClientOrderId
    abstract val status: OrderStatus
    abstract val marketId: MarketId
    abstract val side: OrderSide
    abstract val amount: BigIntegerJson
    abstract val executions: List<Execution>
    abstract val timing: Timing

    @Serializable
    @SerialName("market")
    data class Market(
        override val id: OrderId,
        override val clientOrderId: ClientOrderId,
        override val status: OrderStatus,
        override val marketId: MarketId,
        override val side: OrderSide,
        override val amount: BigIntegerJson,
        override val executions: List<Execution>,
        override val timing: Timing,
    ) : Order()

    @Serializable
    @SerialName("backToBackMarket")
    data class BackToBackMarket(
        override val id: OrderId,
        override val clientOrderId: ClientOrderId,
        override val status: OrderStatus,
        override val marketId: MarketId,
        val secondMarketId: MarketId,
        override val side: OrderSide,
        override val amount: BigIntegerJson,
        override val executions: List<Execution>,
        override val timing: Timing,
    ) : Order()

    @Serializable
    @SerialName("limit")
    data class Limit(
        override val id: OrderId,
        override val clientOrderId: ClientOrderId,
        override val status: OrderStatus,
        override val marketId: MarketId,
        override val side: OrderSide,
        override val amount: BigIntegerJson,
        val originalAmount: BigIntegerJson,
        val autoReduced: Boolean,
        val price: BigDecimalJson,
        override val executions: List<Execution>,
        override val timing: Timing,
    ) : Order()

    @Serializable
    data class Execution(
        val tradeId: TradeId,
        val timestamp: Instant,
        val amount: BigIntegerJson,
        val price: BigDecimalJson,
        val role: ExecutionRole,
        val feeAmount: BigIntegerJson,
        val feeSymbol: Symbol,
        val marketId: MarketId,
    )

    @Serializable
    data class Timing(
        val createdAt: Instant,
        val updatedAt: Instant?,
        val closedAt: Instant?,
        val sequencerTimeNs: BigIntegerJson,
    )
}

@Serializable
data class BatchOrdersApiRequest(
    val marketId: MarketId,
    val createOrders: List<CreateOrderApiRequest>,
    val cancelOrders: List<CancelOrderApiRequest>,
)

@Serializable
data class OrdersApiResponse(
    val orders: List<Order>,
)

@Serializable
data class BatchOrdersApiResponse(
    val createdOrders: List<CreateOrderApiResponse>,
    val canceledOrders: List<CancelOrderApiResponse>,
)

@Serializable
data class GetOrderBookApiResponse(
    val marketId: MarketId,
    val bids: List<OrderBook.Entry>,
    val asks: List<OrderBook.Entry>,
    val last: OrderBook.LastTrade,
)
