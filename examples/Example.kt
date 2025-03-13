package xyz.funkybit.client.example

import org.http4k.websocket.WsClient
import org.http4k.websocket.WsStatus
import xyz.funkybit.client.BitcoinWallet
import xyz.funkybit.client.FunkybitApiClient
import xyz.funkybit.client.Wallet
import xyz.funkybit.client.WalletKeyPair
import xyz.funkybit.client.bitcoinConfig
import xyz.funkybit.client.model.AssetAmount
import xyz.funkybit.client.model.BalanceType
import xyz.funkybit.client.model.Balances
import xyz.funkybit.client.model.BalancesUpdated
import xyz.funkybit.client.model.Chain
import xyz.funkybit.client.model.ChainId.Companion.BITCOIN
import xyz.funkybit.client.model.ClientOrderId
import xyz.funkybit.client.model.CreateOrderApiRequest
import xyz.funkybit.client.model.ExecutionRole
import xyz.funkybit.client.model.MyOrders
import xyz.funkybit.client.model.MyOrdersCreated
import xyz.funkybit.client.model.MyOrdersUpdated
import xyz.funkybit.client.model.MyTrades
import xyz.funkybit.client.model.MyTradesCreated
import xyz.funkybit.client.model.MyTradesUpdated
import xyz.funkybit.client.model.OrderAmount
import xyz.funkybit.client.model.OrderSide
import xyz.funkybit.client.model.OrderStatus
import xyz.funkybit.client.model.OutgoingWSMessage
import xyz.funkybit.client.model.Publishable
import xyz.funkybit.client.model.SettlementStatus
import xyz.funkybit.client.model.SubscriptionTopic
import xyz.funkybit.client.model.SymbolInfo
import xyz.funkybit.client.model.address.EvmAddress
import xyz.funkybit.client.model.signature.EvmSignature
import xyz.funkybit.client.receivedDecoded
import xyz.funkybit.client.subscribeToBalances
import xyz.funkybit.client.subscribeToMyOrders
import xyz.funkybit.client.subscribeToMyTrades
import xyz.funkybit.client.unsubscribe
import xyz.funkybit.client.utils.generateOrderNonce
import xyz.funkybit.client.utils.toFundamentalUnits
import java.math.BigDecimal
import java.math.BigInteger

/**
 * Example demonstrating how to use the funkybit API client
 */
fun main() {
    val endpoint = "https://prod-api.funkybit.fun"
    val base = "BTC"
    val baseIsOnBitcoin = false
    val quote = "BTC"
    val quoteIsOnBitcoin = true
    val makerBaseAmount = "0.0001"
    val price = "1.0"
    val takerQuoteAmount = "0.0001"
    // Create a client with a wallet
    val privateKey = "0x1198d5fcb2d6c0fc1c7225f4b76d598fd029229557277b4952e0bafd899cc3d3" // Example key - replace with your own
    val evmKeyPair = WalletKeyPair.EVM.fromPrivateKeyHex(privateKey)
    val client =
        FunkybitApiClient(
            keyPair = evmKeyPair,
            apiUrl = endpoint,
        )
    val wallet = Wallet(client)
    println("Connected with address: ${client.address}")

    val config = client.getConfiguration()
    val accountConfig = client.getAccountConfiguration()

    // authorize bitcoin wallet
    val btcKeyPair = WalletKeyPair.Bitcoin.fromPrivateKeyHex(privateKey, bitcoinConfig.params)
    val bitcoinClient = FunkybitApiClient(keyPair = btcKeyPair, apiUrl = endpoint, chainId = Chain.Id(BITCOIN))
    val bitcoinWallet = BitcoinWallet(btcKeyPair, config.chains, bitcoinClient)
    bitcoinClient.authorizeWallet(
        bitcoinWallet.signAuthorizeBitcoinWalletRequest(
            evmKeyPair.ecKeyPair,
            client.address as EvmAddress,
            bitcoinWallet.walletAddress,
            wallet.currentChainId,
        ),
    )

    // Connect to WebSocket for real-time updates
    val webSocket = client.newWebSocket(client.authToken)

    try {
        val evmChain = config.evmChains.first()
        client.switchChain(evmChain.id)
        val baseSymbol =
            if (baseIsOnBitcoin) {
                config.bitcoinChain.symbols.first { it.name == "$base:$BITCOIN" }
            } else {
                evmChain.symbols.first {
                    it.name ==
                        "$base:${evmChain.id.value}"
                }
            }
        val quoteSymbol =
            if (quoteIsOnBitcoin) {
                config.bitcoinChain.symbols.first { it.name == "$quote:$BITCOIN" }
            } else {
                evmChain.symbols.first {
                    it.name ==
                        "$quote:${evmChain.id.value}"
                }
            }

        // Subscribe to balance updates before deposit
        webSocket.subscribeToBalances()
        println("Waiting for balance subscription confirmation...")
        waitForSubscription(webSocket) { it is Balances }

        // Get initial base balance
        val initialBalances = client.getBalances().balances
        val initialBaseBalance = initialBalances.firstOrNull { it.symbol.value == baseSymbol.name }?.available ?: BigInteger.ZERO
        println("Initial $base balance: $initialBaseBalance")

        // Deposit base for the limit sell
        val walletBaseBalance = wallet.getWalletBalance(baseSymbol)
        if (walletBaseBalance.amount < makerBaseAmount.toBigDecimal()) {
            throw RuntimeException(
                "Need at least $makerBaseAmount $base in ${if (baseIsOnBitcoin) bitcoinClient.address else client.address} on chain ${if (baseIsOnBitcoin) BITCOIN else evmChain.id.value}",
            )
        }
        if (baseIsOnBitcoin) {
            bitcoinWallet.depositNative(makerBaseAmount.toFundamentalUnits(8))
        } else {
            wallet.deposit(AssetAmount(baseSymbol, makerBaseAmount))
        }
        println("Deposited $makerBaseAmount $base")

        // Wait for base balance to increase
        waitForBalanceIncrease(webSocket, baseSymbol, initialBaseBalance)

        webSocket.unsubscribe(SubscriptionTopic.Balances)

        // Subscribe to MyOrders topic
        webSocket.subscribeToMyOrders()

        // Wait for subscription confirmation
        println("Waiting for order subscription confirmation...")
        waitForSubscription(webSocket) { it is MyOrders }

        val market =
            config.markets.first {
                it.baseSymbol.value == baseSymbol.name && it.quoteSymbol.value == quoteSymbol.name
            }

        // Place a limit sell order
        val sellOrder =
            CreateOrderApiRequest.Limit(
                marketId = market.id,
                side = OrderSide.Sell,
                amount = OrderAmount.Fixed(makerBaseAmount.toFundamentalUnits(baseSymbol.decimals)),
                price = price.toBigDecimal(),
                signature = EvmSignature.emptySignature(),
                nonce = generateOrderNonce(),
                clientOrderId = ClientOrderId("example-sell-${System.currentTimeMillis()}"),
                signingAddress = evmKeyPair.address().value,
                verifyingChainId = evmChain.id,
                captchaToken = "recaptcha-token",
            )

        val signedSellOrder = wallet.signOrder(sellOrder)
        println("Placing limit sell order...")
        val sellResponse = client.createOrder(signedSellOrder)
        println("Limit sell order placed: ${sellResponse.order}")

        // Wait for order created confirmation
        println("Waiting for order created...")
        waitForOrderCreated(webSocket, sellResponse.order.clientOrderId)
        val walletQuoteBalance = wallet.getWalletBalance(quoteSymbol)
        if (walletQuoteBalance.amount < takerQuoteAmount.toBigDecimal()) {
            throw RuntimeException(
                "Need at least $takerQuoteAmount $quote in ${if (quoteIsOnBitcoin) bitcoinClient.address else client.address} on chain ${if (quoteIsOnBitcoin) BITCOIN else evmChain.id.value}",
            )
        }
        // Deposit quote for the market buy
        if (quoteIsOnBitcoin) {
            bitcoinWallet.depositNative(takerQuoteAmount.toFundamentalUnits(8))
        } else {
            wallet.deposit(AssetAmount(quoteSymbol, takerQuoteAmount))
        }
        println("Deposited $takerQuoteAmount $quote")

        // Subscribe to balance updates for quote deposit
        webSocket.subscribeToBalances()
        println("Waiting for balance subscription confirmation...")
        waitForSubscription(webSocket) { it is Balances }

        // Get initial quote balance
        val initialQuoteBalance =
            client
                .getBalances()
                .balances
                .firstOrNull { it.symbol.value == quoteSymbol.name }
                ?.available ?: BigInteger.ZERO
        println("Initial $quote balance: $initialQuoteBalance")

        // Wait for quote balance to increase
        waitForBalanceIncrease(webSocket, quoteSymbol, initialQuoteBalance)

        webSocket.unsubscribe(SubscriptionTopic.Balances)

        // Subscribe to MyTrades topic for the market buy
        webSocket.subscribeToMyTrades()
        println("Waiting for trades subscription confirmation...")
        waitForSubscription(webSocket) { it is MyTrades }

        // Place a market buy order for takerQuoteAmount base
        val marketBuyOrder =
            CreateOrderApiRequest.Market(
                marketId = market.id,
                side = OrderSide.Buy,
                amount =
                    OrderAmount.Fixed(
                        makerBaseAmount.toBigDecimal().divide(BigDecimal.valueOf(2L)).toFundamentalUnits(baseSymbol.decimals),
                    ),
                signature = EvmSignature.emptySignature(),
                nonce = generateOrderNonce(),
                clientOrderId = ClientOrderId("example-market-buy-${System.currentTimeMillis()}"),
                signingAddress = evmKeyPair.address().value,
                verifyingChainId = evmChain.id,
                captchaToken = "recaptcha-token",
            )

        val signedMarketBuyOrder = wallet.signOrder(marketBuyOrder)
        println("Placing market buy order...")
        val buyResponse = client.createOrder(signedMarketBuyOrder)
        println("Market buy order placed: ${buyResponse.order}")

        // Wait for market buy order created confirmation
        println("Waiting for market buy order created...")
        waitForOrderCreated(webSocket, buyResponse.order.clientOrderId)

        // Track state for both orders and trades
        var limitSellFilled = false
        var marketBuyFilled = false
        var tradeSettled = false

        // Wait for all updates
        println("Waiting for trade updates...")
        while (!tradeSettled) {
            when (val publish = webSocket.receivedDecoded().first()) {
                is OutgoingWSMessage.Publish -> {
                    when (publish.data) {
                        is MyOrdersUpdated -> {
                            val order = publish.data.orders.first()
                            if (order.clientOrderId == sellResponse.order.clientOrderId) {
                                println("Limit sell order updated: $order")
                                if (order.status == OrderStatus.Filled) {
                                    limitSellFilled = true
                                }
                            }
                        }
                        is MyTradesCreated -> {
                            val trades = publish.data.trades
                            // Find the trade that matches our market buy order
                            val marketBuyTrade = trades.find { it.executionRole === ExecutionRole.Taker }
                            if (marketBuyTrade != null) {
                                println("Market buy trade created: $marketBuyTrade")
                                marketBuyFilled = true
                            }
                        }
                        is MyTradesUpdated -> {
                            val trade = publish.data.trades.first()
                            // Only process updates for our market buy trade
                            if (trade.executionRole == ExecutionRole.Taker) {
                                println("Market buy trade updated: $trade")
                                if (trade.settlementStatus == SettlementStatus.Completed) {
                                    println("Trade settled successfully!")
                                    tradeSettled = true
                                } else if (trade.settlementStatus == SettlementStatus.Failed) {
                                    println("Trade settlement failed: ${trade.error}")
                                    throw RuntimeException("Trade settlement failed")
                                }
                            }
                        }
                        else -> {}
                    }
                }
            }
        }

        // Cancel all orders
        println("Cancelling all orders...")
        val orders = client.listOrders(marketId = market.id).orders
        client.cancelOpenOrders(listOf(market.id))
        orders.forEach { order ->
            if (order.status == OrderStatus.Open) {
                println("Cancelling order: ${order.clientOrderId}")
                // Wait for order cancelled confirmation
                waitForOrderCancelled(webSocket, order.clientOrderId)
            }
        }
        println("All orders cancelled")

        // Get final balances
        println("Retrieving final balances...")
        val balances = client.getBalances().balances
        println("Final balances:")
        balances.forEach { balance ->
            println("${balance.symbol}: ${balance.total} (${balance.available} available)")
        }

        // Subscribe to balance updates
        webSocket.subscribeToBalances()
        println("Waiting for balance subscription confirmation...")
        waitForSubscription(webSocket) { it is Balances }

        // Withdraw balances
        println("Withdrawing available balances...")
        listOf(baseSymbol, quoteSymbol).forEach { symbol ->
            val balance = balances.first { it.symbol.value == symbol.name }
            if (balance.available > BigInteger.ZERO) {
                println("Withdrawing ${balance.available} ${balance.symbol}")
                if ((baseIsOnBitcoin && symbol == baseSymbol) || (quoteIsOnBitcoin && symbol == quoteSymbol)) {
                    bitcoinClient.createWithdrawal(bitcoinWallet.signWithdraw(symbol.name, balance.available, 8U))
                } else {
                    wallet.withdraw(AssetAmount(symbol, balance.available))
                }
            }
        }
        println("All withdrawal requests sent")

        // Wait for balances to go to zero
        println("Waiting for balances to be withdrawn...")
        var baseWithdrawn = false
        var quoteWithdrawn = false

        while (!baseWithdrawn || !quoteWithdrawn) {
            when (val publish = webSocket.receivedDecoded().first()) {
                is OutgoingWSMessage.Publish -> {
                    when (publish.data) {
                        is BalancesUpdated -> {
                            publish.data.balances.forEach { balance ->
                                println("Balance updated: ${balance.symbol}: ${balance.value} (${balance.type})")
                                when (balance.symbol.value) {
                                    baseSymbol.name -> {
                                        if (balance.type == BalanceType.Available && balance.value == BigInteger.ZERO) {
                                            baseWithdrawn = true
                                        }
                                    }
                                    quoteSymbol.name -> {
                                        if (balance.type == BalanceType.Available && balance.value == BigInteger.ZERO) {
                                            quoteWithdrawn = true
                                        }
                                    }
                                }
                            }
                        }
                        else -> {}
                    }
                }
            }
        }
        println("All balances withdrawn")

        println("Final state:")
        println("Limit sell filled: $limitSellFilled")
        println("Market buy filled: $marketBuyFilled")
        println("Trade settled: $tradeSettled")
    } catch (e: Exception) {
        println("Error: ${e.message}")
        e.printStackTrace()
    } finally {
        webSocket.close(WsStatus(WsStatus.NORMAL.code, ""))
        println("Example completed")
    }
}

private fun waitForSubscription(
    webSocket: WsClient,
    predicate: (Publishable) -> Boolean,
) {
    while (true) {
        when (val publish = webSocket.receivedDecoded().first()) {
            is OutgoingWSMessage.Publish -> {
                if (predicate(publish.data)) {
                    println("Subscription confirmed")
                    break
                }
            }
        }
    }
}

private fun waitForOrderCreated(
    webSocket: WsClient,
    orderId: ClientOrderId,
) {
    while (true) {
        when (val publish = webSocket.receivedDecoded().first()) {
            is OutgoingWSMessage.Publish -> {
                when (publish.data) {
                    is MyOrdersCreated -> {
                        if (publish.data.orders.any { it.clientOrderId == orderId }) {
                            println("Order created: ${publish.data}")
                            break
                        }
                    }
                    else -> {}
                }
            }
        }
    }
}

private fun waitForOrderCancelled(
    webSocket: WsClient,
    orderId: ClientOrderId,
) {
    while (true) {
        when (val publish = webSocket.receivedDecoded().first()) {
            is OutgoingWSMessage.Publish -> {
                when (publish.data) {
                    is MyOrdersUpdated -> {
                        if (publish.data.orders.any { it.clientOrderId == orderId && it.status == OrderStatus.Cancelled }) {
                            println("Order cancelled: ${publish.data}")
                            break
                        }
                    }
                    else -> {}
                }
            }
        }
    }
}

private fun waitForBalanceIncrease(
    webSocket: WsClient,
    symbol: SymbolInfo,
    initialBalance: BigInteger,
) {
    println("Waiting for $symbol deposit to complete...")
    while (true) {
        when (val publish = webSocket.receivedDecoded().first()) {
            is OutgoingWSMessage.Publish -> {
                when (publish.data) {
                    is BalancesUpdated -> {
                        val balance = publish.data.balances.find { it.symbol.value == symbol.name }
                        if (balance != null && balance.type == BalanceType.Available) {
                            println("$symbol balance updated: ${balance.value}")
                            if (balance.value > initialBalance) {
                                println("$symbol deposit completed")
                                break
                            }
                        }
                    }
                    else -> {}
                }
            }
        }
    }
}
