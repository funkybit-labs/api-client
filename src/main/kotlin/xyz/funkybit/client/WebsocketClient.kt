package xyz.funkybit.client
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.Clock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.WebSocket
import org.http4k.client.OkHttpWebsocketClient.blocking
import org.http4k.core.Uri
import org.http4k.websocket.WsClient
import org.http4k.websocket.WsMessage
import org.http4k.websocket.WsStatus
import xyz.funkybit.client.model.IncomingWSMessage
import xyz.funkybit.client.model.MarketId
import xyz.funkybit.client.model.OHLCDuration
import xyz.funkybit.client.model.OutgoingWSMessage
import xyz.funkybit.client.model.SubscriptionTopic
import xyz.funkybit.client.model.WSFeature
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.concurrent.thread
import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

val json =
    Json {
        encodeDefaults = true
        coerceInputValues = true
        ignoreUnknownKeys = true
    }

class ResubscriptionError : Exception()

open class ReconnectingWebsocketClient(
    protected val apiUrl: String,
    protected val client: FunkybitApiClient,
) {
    private val logger = KotlinLogging.logger {}
    private val activeSubscriptions = CopyOnWriteArraySet<SubscriptionTopic>()
    private var cancelOnDisconnectFeature: WSFeature.CancelOnDisconnect? = null
    private lateinit var websocket: WsClient
    private var heartbeatThread: Thread? = null
    private var isRunning = true
    protected var inMaintenanceMode = false
    var isConnected = false

    fun initialize() {
        websocket =
            newWebsocket().also {
                isConnected = true
            }
        startHeartbeat()
    }

    protected open fun newWebsocket(): WsClient {
        logger.info { "Connecting to websocket ${apiUrl.replace("http:", "ws:").replace("https:", "wss:")}" }
        return blocking(
            uri =
                Uri.of(
                    apiUrl.replace("http:", "ws:").replace("https:", "wss:") + "/connect?auth=${client.authToken}",
                ),
        ).also {
            logger.info { "Successfully connected to the websocket" }
        }
    }

    private fun startHeartbeat() {
        heartbeatThread =
            thread(isDaemon = true, start = true, name = "funkybit-websocket-heartbeat") {
                var lastPing = Clock.System.now()
                while (isRunning) {
                    try {
                        Thread.sleep(1000)
                        val now = Clock.System.now()
                        if (now.minus(lastPing) > 30.seconds) {
                            lastPing = now
                            if (!inMaintenanceMode) {
                                withReconnection { ws -> ws.ping() }
                            }
                        }
                    } catch (e: InterruptedException) {
                        // Thread was interrupted, likely during shutdown
                        logger.info { "Heartbeat thread interrupted" }
                        break
                    } catch (e: Exception) {
                        // Log heartbeat error but don't reconnect here as withReconnection already handles it
                        logger.warn { "Heartbeat error: ${e.message}" }
                    }
                }
            }
    }

    @Synchronized
    @Throws(ResubscriptionError::class)
    private fun attemptReconnect() {
        // another thread trying at the same time might have already reconnected
        if (isConnected) return

        // Create new websocket
        val ws = newWebsocket()

        // Resubscribe to all active topics
        try {
            activeSubscriptions.forEach { topic ->
                ws.send(IncomingWSMessage.Subscribe(topic))
            }

            // set cancel on disconnect behavior
            cancelOnDisconnectFeature?.let {
                ws.setCancelOnDisconnect(it)
            }
            websocket = ws
            isConnected = true
        } catch (resubscribeException: Exception) {
            logger.info(resubscribeException) { "Unable to resubscribe after reconnection" }
            throw ResubscriptionError()
        }
    }

    // Helper function that handles reconnection
    private fun <T> withReconnection(
        maxRetries: Int = 1000,
        block: (WsClient) -> T,
    ): T {
        var retryCount = 0
        var lastException: Exception? = null

        while (retryCount < maxRetries) {
            try {
                return block(websocket)
            } catch (e: Exception) {
                if (!isRunning) throw RuntimeException("Websocket has been closed")
                isConnected = false
                lastException = e
                logger.warn { "WebSocket operation failed: ${e.message}" }

                // Try to reconnect
                try {
                    val delay =
                        if (inMaintenanceMode) {
                            1000L
                        } else {
                            client.reissueAuthToken()
                            // (quick) exponential backoff with jitter
                            val baseDelay = (2 * 1.05.pow(retryCount.toDouble())).coerceAtMost(5000.0).toLong()
                            val jitter = Random.nextLong(baseDelay / 2)
                            baseDelay + jitter
                        }

                    Thread.sleep(delay)
                    while (!isConnected && retryCount < maxRetries) {
                        try {
                            attemptReconnect()
                        } catch (_: ResubscriptionError) {
                            retryCount += 1
                            logger.warn { "Reconnection attempt failed during resubscription (retry $retryCount of $maxRetries)" }
                        }
                    }
                    if (!isConnected) {
                        throw ResubscriptionError()
                    }
                    inMaintenanceMode = false
                    logger.info { "Reconnected successfully" }
                } catch (_: ResubscriptionError) {
                    logger.error { "Unable to successfully resubscribe following reconnect after $maxRetries attempts." }
                    throw RuntimeException("Failed to reconnect")
                } catch (reconnectException: Exception) {
                    // funkybit returns HTTP status code 418 for maintenance mode
                    if ((reconnectException.message ?: "").contains("Expected HTTP 101 response but was '418 '")) {
                        inMaintenanceMode = true
                    } else {
                        retryCount += 1
                    }
                    logger.warn { "Reconnection attempt failed (retry $retryCount of $maxRetries): ${reconnectException.message}" }
                }
            }
        }

        throw lastException ?: RuntimeException("Failed to execute WebSocket operation after $maxRetries retries")
    }

    fun subscribeToOrderBook(marketId: MarketId) {
        val topic = SubscriptionTopic.OrderBook(marketId)
        withReconnection { ws -> ws.send(IncomingWSMessage.Subscribe(topic)) }
        activeSubscriptions.add(topic)
    }

    fun subscribeToIncrementalOrderBook(marketId: MarketId) {
        val topic = SubscriptionTopic.IncrementalOrderBook(marketId)
        withReconnection { ws -> ws.send(IncomingWSMessage.Subscribe(topic)) }
        activeSubscriptions.add(topic)
    }

    fun subscribeToMarketAmmState(marketId: MarketId) {
        val topic = SubscriptionTopic.MarketAmmState(marketId)
        withReconnection { ws -> ws.send(IncomingWSMessage.Subscribe(topic)) }
        activeSubscriptions.add(topic)
    }

    fun subscribeToLaunchpadUpdates() {
        val topic = SubscriptionTopic.Launchpad
        withReconnection { ws -> ws.send(IncomingWSMessage.Subscribe(topic)) }
        activeSubscriptions.add(topic)
    }

    fun subscribeToPrices(
        marketId: MarketId,
        duration: OHLCDuration = OHLCDuration.P5M,
    ) {
        val topic = SubscriptionTopic.Prices(marketId, duration)
        withReconnection { ws -> ws.send(IncomingWSMessage.Subscribe(topic)) }
        activeSubscriptions.add(topic)
    }

    fun subscribeToMyOrders() {
        val topic = SubscriptionTopic.MyOrders
        withReconnection { ws -> ws.send(IncomingWSMessage.Subscribe(topic)) }
        activeSubscriptions.add(topic)
    }

    fun subscribeToMyTrades() {
        val topic = SubscriptionTopic.MyTrades
        withReconnection { ws -> ws.send(IncomingWSMessage.Subscribe(topic)) }
        activeSubscriptions.add(topic)
    }

    fun unsubscribeToMyTrades() {
        val topic = SubscriptionTopic.MyTrades
        activeSubscriptions.remove(topic)
        withReconnection { ws -> ws.send(IncomingWSMessage.Unsubscribe(topic)) }
    }

    fun subscribeToMarketTrades(marketId: MarketId) {
        val topic = SubscriptionTopic.MarketTrades(marketId)
        withReconnection { ws -> ws.send(IncomingWSMessage.Subscribe(topic)) }
        activeSubscriptions.add(topic)
    }

    fun subscribeToBalances() {
        val topic = SubscriptionTopic.Balances
        withReconnection { ws -> ws.send(IncomingWSMessage.Subscribe(topic)) }
        activeSubscriptions.add(topic)
    }

    fun subscribeToConsumptions(marketId: MarketId) {
        val topic = SubscriptionTopic.Consumption(marketId)
        withReconnection { ws -> ws.send(IncomingWSMessage.Subscribe(topic)) }
        activeSubscriptions.add(topic)
    }

    fun unsubscribe(topic: SubscriptionTopic) {
        withReconnection { ws -> ws.send(IncomingWSMessage.Unsubscribe(topic)) }
        activeSubscriptions.remove(topic)
    }

    fun setCancelOnDisconnect(marketIds: List<MarketId>) {
        cancelOnDisconnectFeature =
            WSFeature.CancelOnDisconnect(true, marketIds).also {
                withReconnection { ws -> ws.setCancelOnDisconnect(it) }
            }
    }

    fun unsetCancelOnDisconnect() {
        cancelOnDisconnectFeature = null
        withReconnection { ws -> ws.setCancelOnDisconnect(WSFeature.CancelOnDisconnect(false, emptyList())) }
    }

    fun receivedDecoded(): Sequence<OutgoingWSMessage> =
        sequence {
            while (isRunning) {
                try {
                    // Get a new sequence of messages
                    val messageSequence =
                        withReconnection { ws ->
                            ws.received().iterator()
                        }

                    // Process messages until exhausted or exception occurs
                    while (true) {
                        try {
                            if (!messageSequence.hasNext()) break
                            val message = messageSequence.next()
                            yield(json.decodeFromString<OutgoingWSMessage>(message.bodyString()))
                        } catch (e: Exception) {
                            logger.error { "Error processing message: ${e.message}" }
                            break // Break the inner loop to reconnect
                        }
                    }
                } catch (e: Exception) {
                    // If we get here, the withReconnection failed after max retries
                    // Wait a bit before trying again
                    logger.error { "Failed to connect after multiple attempts: ${e.message}" }
                    Thread.sleep(5000)
                }

                // Only continue the outer loop if we're still running
                if (!isRunning) break
            }
        }

    fun close(status: WsStatus = WsStatus.NORMAL) {
        logger.info { "Websocket is being closed" }
        isRunning = false
        heartbeatThread?.interrupt()
        heartbeatThread?.join(100L)
        try {
            websocket.close(status)
            isConnected = false
        } catch (e: Exception) {
            // Ignore exceptions during close
            logger.warn { "Got an exception closing the websocket: ${e.message}" }
        }
        activeSubscriptions.clear()
        cancelOnDisconnectFeature = null
    }
}

fun WsClient.send(message: IncomingWSMessage) {
    send(WsMessage(json.encodeToString(message)))
}

fun WsClient.ping() {
    send(IncomingWSMessage.Ping)
}

fun WsClient.setCancelOnDisconnect(feature: WSFeature.CancelOnDisconnect) {
    send(IncomingWSMessage.SetFeature(feature))
}

fun WebSocket.send(message: IncomingWSMessage) {
    send(json.encodeToString(message))
}

fun WebSocket.subscribe(topic: SubscriptionTopic) {
    send(IncomingWSMessage.Subscribe(topic))
}

fun WebSocket.unsubscribe(topic: SubscriptionTopic) {
    send(IncomingWSMessage.Unsubscribe(topic))
}
