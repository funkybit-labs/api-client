package xyz.funkybit.client
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.WebSocket
import org.http4k.client.WebsocketClient
import org.http4k.core.Uri
import org.http4k.websocket.WsClient
import org.http4k.websocket.WsMessage
import xyz.funkybit.client.model.IncomingWSMessage
import xyz.funkybit.client.model.MarketId
import xyz.funkybit.client.model.OHLCDuration
import xyz.funkybit.client.model.OutgoingWSMessage
import xyz.funkybit.client.model.SubscriptionTopic

fun WebsocketClient.blocking(
    apiUrl: String,
    auth: String?,
): WsClient =
    blocking(
        uri = Uri.of(apiUrl.replace("http:", "ws:").replace("https:", "wss:") + "/connect" + (auth?.let { "?auth=$auth" } ?: "")),
    )

fun WsClient.send(message: IncomingWSMessage) {
    send(WsMessage(Json.encodeToString(message)))
}

fun WsClient.subscribeToOrderBook(marketId: MarketId) {
    send(IncomingWSMessage.Subscribe(SubscriptionTopic.OrderBook(marketId)))
}

fun WsClient.subscribeToIncrementalOrderBook(marketId: MarketId) {
    send(IncomingWSMessage.Subscribe(SubscriptionTopic.IncrementalOrderBook(marketId)))
}

fun WsClient.subscribeToMarketAmmState(marketId: MarketId) {
    send(IncomingWSMessage.Subscribe(SubscriptionTopic.MarketAmmState(marketId)))
}

fun WsClient.subscribeToLaunchpadUpdates() {
    send(IncomingWSMessage.Subscribe(SubscriptionTopic.Launchpad))
}

fun WsClient.subscribeToPrices(
    marketId: MarketId,
    duration: OHLCDuration = OHLCDuration.P5M,
) {
    send(IncomingWSMessage.Subscribe(SubscriptionTopic.Prices(marketId, duration)))
}

fun WsClient.subscribeToMyOrders() {
    send(IncomingWSMessage.Subscribe(SubscriptionTopic.MyOrders))
}

fun WsClient.subscribeToMyTrades() {
    send(IncomingWSMessage.Subscribe(SubscriptionTopic.MyTrades))
}

fun WsClient.subscribeToMarketTrades(marketId: MarketId) {
    send(IncomingWSMessage.Subscribe(SubscriptionTopic.MarketTrades(marketId)))
}

fun WsClient.subscribeToBalances() {
    send(IncomingWSMessage.Subscribe(SubscriptionTopic.Balances))
}

fun WsClient.subscribeToConsumptions(marketId: MarketId) {
    send(IncomingWSMessage.Subscribe(SubscriptionTopic.Consumption(marketId)))
}

fun WsClient.unsubscribe(topic: SubscriptionTopic) {
    send(IncomingWSMessage.Unsubscribe(topic))
}

fun WsClient.receivedDecoded(): Sequence<OutgoingWSMessage> =
    received().map {
        Json.decodeFromString<OutgoingWSMessage>(it.bodyString())
    }

fun WebSocket.send(message: IncomingWSMessage) {
    send(Json.encodeToString(message))
}

fun WebSocket.subscribe(topic: SubscriptionTopic) {
    send(IncomingWSMessage.Subscribe(topic))
}

fun WebSocket.unsubscribe(topic: SubscriptionTopic) {
    send(IncomingWSMessage.Unsubscribe(topic))
}
