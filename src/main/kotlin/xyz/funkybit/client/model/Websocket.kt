package xyz.funkybit.client.model

import kotlinx.datetime.Instant
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import xyz.funkybit.client.utils.BigDecimalJson
import xyz.funkybit.client.utils.BigIntegerJson

@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
@Serializable
sealed class IncomingWSMessage {
    @Serializable
    @SerialName("Ping")
    data object Ping : IncomingWSMessage()

    @Serializable
    @SerialName("Subscribe")
    data class Subscribe(
        val topic: SubscriptionTopic,
    ) : IncomingWSMessage()

    @Serializable
    @SerialName("Unsubscribe")
    data class Unsubscribe(
        val topic: SubscriptionTopic,
    ) : IncomingWSMessage()
}

@Serializable
@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
sealed class SubscriptionTopic {
    @Serializable
    @SerialName("OrderBook")
    data class OrderBook(
        val marketId: MarketId,
    ) : SubscriptionTopic()

    @Serializable
    @SerialName("IncrementalOrderBook")
    data class IncrementalOrderBook(
        val marketId: MarketId,
    ) : SubscriptionTopic()

    @Serializable
    @SerialName("Launchpad")
    data object Launchpad : SubscriptionTopic()

    @Serializable
    @SerialName("MarketAmmState")
    data class MarketAmmState(
        val marketId: MarketId,
    ) : SubscriptionTopic()

    @Serializable
    @SerialName("Prices")
    data class Prices(
        val marketId: MarketId,
        val duration: OHLCDuration,
    ) : SubscriptionTopic()

    @Serializable
    @SerialName("MyTrades")
    data object MyTrades : SubscriptionTopic()

    @Serializable
    @SerialName("MarketTrades")
    data class MarketTrades(
        val marketId: MarketId,
    ) : SubscriptionTopic()

    @Serializable
    @SerialName("MyOrders")
    data object MyOrders : SubscriptionTopic()

    @Serializable
    @SerialName("Balances")
    data object Balances : SubscriptionTopic()

    @Serializable
    @SerialName("Consumption")
    data class Consumption(
        val marketId: MarketId,
    ) : SubscriptionTopic()
}

@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
@Serializable
sealed class OutgoingWSMessage {
    @Serializable
    @SerialName("Pong")
    data object Pong : OutgoingWSMessage()

    @Serializable
    @SerialName("Publish")
    data class Publish(
        val topic: SubscriptionTopic,
        val data: Publishable,
    ) : OutgoingWSMessage()
}

@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
@Serializable
sealed class Publishable

@Serializable
@SerialName("OrderBook")
data class OrderBook(
    val marketId: MarketId,
    val buy: List<Entry>,
    val sell: List<Entry>,
    val last: LastTrade,
) : Publishable() {
    @Serializable
    data class Entry(
        val price: String,
        val size: BigDecimalJson,
    )

    @Serializable
    data class LastTrade(
        val price: String,
        val direction: LastTradeDirection,
    )

    @Serializable
    enum class LastTradeDirection {
        Up,
        Down,
        Unchanged,
    }
}

@Serializable
@SerialName("OrderBookDiff")
data class OrderBookDiff(
    val sequenceNumber: Long,
    val marketId: MarketId,
    val buy: List<OrderBook.Entry>,
    val sell: List<OrderBook.Entry>,
    val last: OrderBook.LastTrade?,
) : Publishable()

@Serializable
@SerialName("Prices")
data class Prices(
    val market: MarketId,
    val duration: OHLCDuration,
    val ohlc: List<OHLC>,
    val full: Boolean,
    val dailyChange: BigDecimalJson,
    val dailyMarketCapChange: BigDecimalJson,
) : Publishable()

@Serializable
data class OHLC
    @OptIn(ExperimentalSerializationApi::class)
    constructor(
        val start: Instant,
        val open: BigDecimalJson,
        val high: BigDecimalJson,
        val low: BigDecimalJson,
        val close: BigDecimalJson,
        val duration: OHLCDuration,
        @EncodeDefault
        @ExperimentalSerializationApi
        val openMarketCap: BigDecimalJson? = null,
        @EncodeDefault
        @ExperimentalSerializationApi
        val highMarketCap: BigDecimalJson? = null,
        @EncodeDefault
        @ExperimentalSerializationApi
        val lowMarketCap: BigDecimalJson? = null,
        @EncodeDefault
        @ExperimentalSerializationApi
        val closeMarketCap: BigDecimalJson? = null,
    )

@Serializable
@SerialName("MyTrades")
data class MyTrades(
    val trades: List<Trade>,
) : Publishable()

@Serializable
@SerialName("MyTradesCreated")
data class MyTradesCreated(
    val trades: List<Trade>,
) : Publishable()

@Serializable
@SerialName("MarketTradesCreated")
data class MarketTradesCreated(
    val sequenceNumber: Long,
    val marketId: MarketId,
    val trades: List<MarketTrade>,
) : Publishable()

@Serializable
@SerialName("MyTradesUpdated")
data class MyTradesUpdated(
    val trades: List<Trade>,
) : Publishable()

@Serializable
@SerialName("MyOrders")
data class MyOrders(
    val orders: List<Order>,
) : Publishable()

@Serializable
@SerialName("Balances")
data class Balances(
    val balances: List<Balance>,
) : Publishable()

@Serializable
enum class BalanceType {
    Total,
    Available,
}

@Serializable
data class UpdatedBalance(
    val symbol: Symbol,
    val type: BalanceType,
    val value: BigIntegerJson,
    val lastUpdated: Instant,
)

@Serializable
@SerialName("BalancesUpdated")
data class BalancesUpdated(
    val balances: List<UpdatedBalance>,
) : Publishable()

@Serializable
@SerialName("MyOrdersCreated")
data class MyOrdersCreated(
    val orders: List<Order>,
) : Publishable()

@Serializable
@SerialName("MyOrdersUpdated")
data class MyOrdersUpdated(
    val orders: List<Order>,
) : Publishable()

@Serializable
@SerialName("Consumption")
data class Consumption(
    val consumption: MarketConsumption,
) : Publishable()
