package xyz.funkybit.client

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.http4k.websocket.WsClient
import org.http4k.websocket.WsMessage
import org.http4k.websocket.WsStatus
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ReconnectingWebsocketClientTest {
    lateinit var apiClient: FunkybitApiClient
    lateinit var wsClientFactory: MockWsClientFactory
    lateinit var reconnectingClient: TestableReconnectingWebsocketClient

    @BeforeEach
    fun beforeTest() {
        apiClient = mockk(relaxed = true)
        every { apiClient.authToken } returns "test-token"
        every { apiClient.reissueAuthToken() } returns Unit

        wsClientFactory = MockWsClientFactory()
        reconnectingClient =
            TestableReconnectingWebsocketClient(
                apiUrl = "https://api.funkybit.xyz",
                client = apiClient,
                wsClientFactory = wsClientFactory,
            ).also { it.initialize() }
    }

    @AfterEach
    fun afterTest() {
        reconnectingClient.close()
    }

    @Test
    fun `should initialize with connection`() {
        // When client is created
        // Then it should be connected
        assertTrue(reconnectingClient.isConnected)
        // And a websocket should have been created
        assertEquals(1, wsClientFactory.createdClients.size)
    }

    @Test
    fun `should attempt reconnection when operation fails`() {
        // Given a client with a mock websocket that will fail
        val failingClient = wsClientFactory.createdClients.first()
        failingClient.failNextOperation()

        // When we try to use the websocket
        val exception =
            runCatching {
                reconnectingClient.subscribeToOrderBook("BTC-USD")
            }.exceptionOrNull()

        // Then we should eventually reconnect successfully
        assertNull(exception)
        assertTrue(reconnectingClient.isConnected)
        // And we should have created a new client during reconnection
        assertEquals(2, wsClientFactory.createdClients.size)
        // And the subscription should have been re-sent after reconnection
        val lastClient = wsClientFactory.createdClients.last()
        assertTrue(lastClient.sentMessages.any { it.contains("Subscribe") && it.contains("OrderBook") })
    }

    @Test
    fun `should handle concurrent reconnection attempts`() =
        runBlocking {
            // Given a client with a mock websocket that will fail
            val latch = CountDownLatch(1)
            val successCount = AtomicInteger(0)
            val failingClient = wsClientFactory.createdClients.first()
            failingClient.failNextOperation()

            // When multiple threads try to use the websocket simultaneously
            val attempts = 5
            withContext(Dispatchers.Default) {
                val tasks =
                    List(attempts) {
                        async {
                            // Wait for all threads to start at the same time
                            latch.await()
                            try {
                                reconnectingClient.subscribeToOrderBook("BTC-USD-$it")
                                successCount.incrementAndGet()
                            } catch (e: Exception) {
                                // Count failed operations
                            }
                        }
                    }

                // Start all tasks simultaneously
                latch.countDown()
                tasks.awaitAll()
            }

            // Then all operations should eventually succeed
            assertEquals(attempts, successCount.get())
            // And we should have created exactly one new client during reconnection
            // (the key assertion for avoiding race conditions - only one reconnection should happen)
            assertEquals(2, wsClientFactory.createdClients.size)
            assertTrue(reconnectingClient.isConnected)
        }

    @Test
    fun `should resubscribe to active topics after reconnection`() {
        // Given some active subscriptions
        reconnectingClient.subscribeToOrderBook("BTC-USD")
        reconnectingClient.subscribeToPrices("ETH-USD")

        // When connection fails and we reconnect
        val failingClient = wsClientFactory.createdClients.first()
        failingClient.failNextOperation()
        reconnectingClient.subscribeToBalances() // This will trigger reconnection

        // Then after reconnection, we should resubscribe to all topics
        val newClient = wsClientFactory.createdClients.last()
        assertEquals(3, newClient.sentMessages.count { it.contains("Subscribe") })
        assertTrue(newClient.sentMessages.any { it.contains("OrderBook") && it.contains("BTC-USD") })
        assertTrue(newClient.sentMessages.any { it.contains("Prices") && it.contains("ETH-USD") })
        assertTrue(newClient.sentMessages.any { it.contains("Balances") })
    }

    @Test
    fun `should restore CancelOnDisconnect feature after reconnection`() {
        // Given we've set cancelOnDisconnect feature
        val marketIds = listOf("BTC-USD", "ETH-USD")
        reconnectingClient.setCancelOnDisconnect(marketIds)

        // When connection fails and we reconnect
        val failingClient = wsClientFactory.createdClients.first()
        failingClient.failNextOperation()
        reconnectingClient.subscribeToBalances() // This will trigger reconnection

        // Then after reconnection, we should restore the CancelOnDisconnect feature
        val newClient = wsClientFactory.createdClients.last()
        assertTrue(
            newClient.sentMessages.any {
                it.contains("SetFeature") &&
                    it.contains("CancelOnDisconnect") &&
                    it.contains("BTC-USD") &&
                    it.contains("ETH-USD")
            },
        )
    }

    @Test
    fun `should handle very frequent disconnections without race conditions`() =
        runBlocking {
            // Given a client that will disconnect frequently
            wsClientFactory.setFailureRate(0.33) // 33% of operations will fail

            // When we perform many operations in parallel
            val successCount = AtomicInteger(0)
            val operations = 10

            withContext(Dispatchers.Default) {
                val tasks =
                    List(operations) {
                        async {
                            // Use delay to ensure operations overlap
                            delay((0..10).random().toLong())
                            reconnectingClient.subscribeToOrderBook("MKT-$it")
                            successCount.incrementAndGet()
                        }
                    }
                tasks.awaitAll()
            }

            // Then all operations should eventually succeed
            assertEquals(operations, successCount.get())
            // And the client should end up connected
            assertTrue(reconnectingClient.isConnected)
        }
}

// A testable subclass of ReconnectingWebsocketClient that lets us inject a mock websocket factory
class TestableReconnectingWebsocketClient(
    apiUrl: String,
    client: FunkybitApiClient,
    private val wsClientFactory: MockWsClientFactory,
) : ReconnectingWebsocketClient(apiUrl, client) {
    override fun newWebsocket(): WsClient =
        wsClientFactory.createClient(apiUrl.replace("http:", "ws:").replace("https:", "wss:") + "/connect?auth=${client.authToken}")

    fun isInMaintenanceMode(): Boolean = inMaintenanceMode
}

// Mock WsClient Factory for controlled testing
class MockWsClientFactory {
    val createdClients = mutableListOf<MockWsClient>()
    private val maintenanceMode = AtomicBoolean(false)
    private val failureRate = AtomicReference(0.0)
    private val allowNextConn = AtomicBoolean(false)

    fun createClient(uri: String): WsClient {
        if (maintenanceMode.get() && !allowNextConn.getAndSet(false)) {
            throw RuntimeException("Expected HTTP 101 response but was '418 '")
        }

        val client = MockWsClient()
        createdClients.add(client)
        return client
    }

    fun setFailureRate(rate: Double) {
        failureRate.set(rate)
    }

    fun enterMaintenanceMode() {
        maintenanceMode.set(true)
    }

    fun exitMaintenanceMode() {
        maintenanceMode.set(false)
    }

    fun allowNextConnection() {
        allowNextConn.set(true)
    }

    inner class MockWsClient : WsClient {
        val sentMessages = mutableListOf<String>()
        private val shouldFail = AtomicBoolean(false)

        fun failNextOperation() {
            shouldFail.set(true)
        }

        fun enterMaintenanceMode() {
            this@MockWsClientFactory.enterMaintenanceMode()
            failNextOperation()
        }

        override fun send(message: WsMessage) {
            checkFailure()
            sentMessages.add(message.bodyString())
        }

        override fun close(status: WsStatus) {
            // No-op for testing
        }

        override fun received(): Sequence<WsMessage> {
            checkFailure()
            return emptySequence()
        }

        private fun checkFailure() {
            if (shouldFail.getAndSet(false) || Math.random() < failureRate.get()) {
                throw IOException("Simulated websocket failure")
            }
        }
    }
}
