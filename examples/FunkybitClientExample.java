import kotlinx.datetime.Clock;
import kotlinx.datetime.Instant;
import org.http4k.websocket.WsStatus;
import xyz.funkybit.client.BitcoinWallet;
import xyz.funkybit.client.FunkybitApiClient;
import xyz.funkybit.client.ReconnectingWebsocketClient;
import xyz.funkybit.client.Wallet;
import xyz.funkybit.client.WalletKeyPair;
import xyz.funkybit.client.model.AccountConfigurationApiResponse;
import xyz.funkybit.client.model.AssetAmount;
import xyz.funkybit.client.model.Balance;
import xyz.funkybit.client.model.BalanceType;
import xyz.funkybit.client.model.Balances;
import xyz.funkybit.client.model.BalancesUpdated;
import xyz.funkybit.client.model.Chain;
import xyz.funkybit.client.model.ConfigurationApiResponse;
import xyz.funkybit.client.model.CreateOrderApiRequest;
import xyz.funkybit.client.model.CreateOrderApiResponse;
import xyz.funkybit.client.model.ExecutionRole;
import xyz.funkybit.client.model.Market;
import xyz.funkybit.client.model.MyOrders;
import xyz.funkybit.client.model.MyOrdersCreated;
import xyz.funkybit.client.model.MyOrdersUpdated;
import xyz.funkybit.client.model.MyTrades;
import xyz.funkybit.client.model.MyTradesCreated;
import xyz.funkybit.client.model.MyTradesUpdated;
import xyz.funkybit.client.model.Order;
import xyz.funkybit.client.model.OrderAmount;
import xyz.funkybit.client.model.OrderSide;
import xyz.funkybit.client.model.OrderStatus;
import xyz.funkybit.client.model.OutgoingWSMessage;
import xyz.funkybit.client.model.Publishable;
import xyz.funkybit.client.model.SettlementStatus;
import xyz.funkybit.client.model.SubscriptionTopic;
import xyz.funkybit.client.model.SymbolInfo;
import xyz.funkybit.client.model.Trade;
import xyz.funkybit.client.model.TradesApiResponse;
import xyz.funkybit.client.model.UpdatedBalance;
import xyz.funkybit.client.model.address.BitcoinAddress;
import xyz.funkybit.client.model.signature.EvmSignature;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static xyz.funkybit.client.BitcoinConfigKt.getBitcoinConfig;
import static xyz.funkybit.client.model.ChainId.BITCOIN;
import static xyz.funkybit.client.utils.UnitsKt.toFundamentalUnits;
import static xyz.funkybit.client.utils.UtilsKt.generateOrderNonce;


/**
 * Example demonstrating how to use the funkybit API client
 */
public class FunkybitClientExample {

    public static void main(String[] args) {
        String endpoint = FunkybitApiClient.Companion.getDEFAULT_API_URL();
        String base = "BTC";
        boolean baseIsOnBitcoin = true;
        String quote = "USDC";
        boolean quoteIsOnBitcoin = false;
        BigDecimal makerBaseAmount = new BigDecimal("0.0001");
        String price = "100000.0";
        BigDecimal takerQuoteAmount = new BigDecimal("6");

        // Create a client with a wallet
        // Example key - replace with your own
        String privateKey = System.getenv("FUNKYBIT_PRIVATE_KEY");
        if (privateKey == null) {
            privateKey = "0x1198d5fcb2d6c0fc1c7225f4b76d598fd029229557277b4952e0bafd899cc3d3";
        }

        // create bitcoin wallet
        WalletKeyPair.Bitcoin bitcoinKeyPair = WalletKeyPair.Bitcoin.Companion.fromPrivateKeyHex(privateKey, getBitcoinConfig().getParams());
        FunkybitApiClient bitcoinClient = new FunkybitApiClient(
                bitcoinKeyPair,
                endpoint,
                BITCOIN,
                WalletKeyPair.EVM.Companion.generate()
        );
        ConfigurationApiResponse config = bitcoinClient.getConfiguration();
        BitcoinWallet bitcoinWallet = new BitcoinWallet(bitcoinKeyPair, config.getChains(), bitcoinClient);
        AccountConfigurationApiResponse accountConfig = bitcoinClient.getAccountConfiguration();

        System.out.println("Connected with Bitcoin wallet " + bitcoinWallet.getAddress());

        WalletKeyPair.EVM evmKeyPair = WalletKeyPair.EVM.Companion.fromPrivateKeyHex(privateKey);
        FunkybitApiClient evmClient = new FunkybitApiClient(
                evmKeyPair,
                endpoint,
                "1",
                WalletKeyPair.EVM.Companion.generate()
        );
        Wallet evmWallet = Wallet.Companion.invoke(evmClient);

        evmClient.authorizeWallet(
                bitcoinWallet.signAuthorizeEvmWalletRequest(
                        bitcoinKeyPair.getEcKey(),
                        (BitcoinAddress) bitcoinClient.getAddress(),
                        evmWallet.getEvmAddress(),
                        BITCOIN,
                        Clock.System.INSTANCE.now(),
                        false
                )
        );
        System.out.println("EVM wallet " + evmWallet.getAddress() + " authorized");

        // Connect to WebSocket for real-time updates
        ReconnectingWebsocketClient webSocket = evmClient.newWebSocket();

        try {
            Chain evmChain = config.getEvmChains().get(0);
            evmClient.switchChain(evmChain.getId());

            SymbolInfo baseSymbol;
            if (baseIsOnBitcoin) {
                baseSymbol = findSymbol(config.getBitcoinChain().getSymbols(), base + ":" + BITCOIN);
            } else {
                baseSymbol = findSymbol(evmChain.getSymbols(), base + ":" + evmChain.getId());
            }

            SymbolInfo quoteSymbol;
            if (quoteIsOnBitcoin) {
                quoteSymbol = findSymbol(config.getBitcoinChain().getSymbols(), quote + ":" + BITCOIN);
            } else {
                quoteSymbol = findSymbol(evmChain.getSymbols(), quote + ":" + evmChain.getId());
            }

            // Subscribe to balance updates before deposit
            webSocket.subscribeToBalances();
            System.out.println("Waiting for balance subscription confirmation...");
            waitForSubscription(webSocket, message -> message instanceof Balances);

            // Get initial base balance
            List<Balance> initialBalances = evmClient.getBalances().getBalances();
            BigInteger initialBaseBalance = findBalance(initialBalances, baseSymbol);
            System.out.println("Initial " + base + " balance: " + initialBaseBalance);

            // Deposit base for the limit sell
            AssetAmount walletBaseBalance = evmWallet.getWalletBalance(baseSymbol);
            if (walletBaseBalance.getAmount().compareTo(makerBaseAmount) < 0) {
                throw new RuntimeException(
                        "Need at least " + makerBaseAmount + " " + base + " in " +
                                (baseIsOnBitcoin ? bitcoinClient.getAddress() : evmClient.getAddress()) +
                                " on chain " + (baseIsOnBitcoin ? BITCOIN : evmChain.getId())
                );
            }

            if (baseIsOnBitcoin) {
                bitcoinWallet.depositNative(toFundamentalUnits(makerBaseAmount, 8));
            } else {
                evmWallet.deposit(new AssetAmount(baseSymbol, makerBaseAmount));
            }
            System.out.println("Deposited " + makerBaseAmount + " " + base);

            // Wait for base balance to increase
            waitForBalanceIncrease(webSocket, baseSymbol, initialBaseBalance);

            webSocket.unsubscribe(SubscriptionTopic.Balances.INSTANCE);

            // Subscribe to MyOrders topic
            webSocket.subscribeToMyOrders();

            // Wait for subscription confirmation
            System.out.println("Waiting for order subscription confirmation...");
            waitForSubscription(webSocket, message -> message instanceof MyOrders);

            Market market = findMarket(config.getMarkets(), baseSymbol.getName(), quoteSymbol.getName());

            // Place a limit sell order
            CreateOrderApiRequest.Limit sellOrder = new CreateOrderApiRequest.Limit(
                    generateOrderNonce(),
                    market.getId(),
                    OrderSide.Sell,
                    new OrderAmount.Fixed(toFundamentalUnits(makerBaseAmount, baseSymbol.getDecimals())),
                    new BigDecimal(price),
                    EvmSignature.Companion.emptySignature(),
                    evmKeyPair.address().getValue(),
                    evmChain.getId(),
                    "example-sell-" + System.currentTimeMillis(),
                    "recaptcha-token",
                    false
            );

            CreateOrderApiRequest signedSellOrder = evmWallet.signOrder(sellOrder, null);
            System.out.println("Placing limit sell order...");
            CreateOrderApiResponse sellResponse = evmClient.createOrder(signedSellOrder);
            System.out.println("Limit sell order placed: " + sellResponse.getOrder());

            // Wait for order created confirmation
            System.out.println("Waiting for order created...");
            waitForOrderCreated(webSocket, sellResponse.getOrder().getClientOrderId());

            AssetAmount walletQuoteBalance = evmWallet.getWalletBalance(quoteSymbol);
            if (walletQuoteBalance.getAmount().compareTo(takerQuoteAmount) < 0) {
                throw new RuntimeException(
                        "Need at least " + takerQuoteAmount + " " + quote + " in " +
                                (quoteIsOnBitcoin ? bitcoinClient.getAddress() : evmClient.getAddress()) +
                                " on chain " + (quoteIsOnBitcoin ? BITCOIN : evmChain.getId())
                );
            }

            // Deposit quote for the market buy
            if (quoteIsOnBitcoin) {
                bitcoinWallet.depositNative(toFundamentalUnits(takerQuoteAmount, 8));
            } else {
                evmWallet.deposit(new AssetAmount(quoteSymbol, takerQuoteAmount));
            }
            System.out.println("Deposited " + takerQuoteAmount + " " + quote);

            // Subscribe to balance updates for quote deposit
            webSocket.subscribeToBalances();
            System.out.println("Waiting for balance subscription confirmation...");
            waitForSubscription(webSocket, message -> message instanceof Balances);

            // Get initial quote balance
            BigInteger initialQuoteBalance = findBalance(evmClient.getBalances().getBalances(), quoteSymbol);
            System.out.println("Initial " + quote + " balance: " + initialQuoteBalance);

            // Wait for quote balance to increase
            waitForBalanceIncrease(webSocket, quoteSymbol, initialQuoteBalance);

            webSocket.unsubscribe(SubscriptionTopic.Balances.INSTANCE);

            // Subscribe to MyTrades topic for the market buy
            webSocket.subscribeToMyTrades();
            System.out.println("Waiting for trades subscription confirmation...");
            waitForSubscription(webSocket, message -> message instanceof MyTrades);

            // Place a market buy order
            BigDecimal halfMakerBaseAmount = makerBaseAmount.divide(BigDecimal.valueOf(2L), baseSymbol.getDecimals(), RoundingMode.HALF_UP);
            CreateOrderApiRequest.Market marketBuyOrder = new CreateOrderApiRequest.Market(
                    generateOrderNonce(),
                    market.getId(),
                    OrderSide.Buy,
                    new OrderAmount.Fixed(toFundamentalUnits(halfMakerBaseAmount, baseSymbol.getDecimals())),
                    EvmSignature.Companion.emptySignature(),
                    evmKeyPair.address().getValue(),
                    evmChain.getId(),
                    "example-market-buy-" + System.currentTimeMillis(),
                    "recaptcha-token",
                    null,
                    null
            );

            CreateOrderApiRequest signedMarketBuyOrder = evmWallet.signOrder(marketBuyOrder, false);
            System.out.println("Placing market buy order...");
            CreateOrderApiResponse buyResponse = evmClient.createOrder(signedMarketBuyOrder);
            System.out.println("Market buy order placed: " + buyResponse.getOrder());

            // Wait for market buy order created confirmation
            System.out.println("Waiting for market buy order created...");
            waitForOrderCreated(webSocket, buyResponse.getOrder().getClientOrderId());

            // Track state for both orders and trades
            boolean limitSellFilled = false;
            boolean marketBuyFilled = false;
            boolean tradeSettled = false;

            // Wait for all updates
            System.out.println("Waiting for trade updates...");
            while (!tradeSettled) {
                OutgoingWSMessage message = webSocket.receivedDecoded().iterator().next();
                if (message instanceof OutgoingWSMessage.Publish publish) {
                    Publishable data = publish.getData();

                    if (data instanceof MyOrdersUpdated ordersUpdated) {
                        for (Order order : ordersUpdated.getOrders()) {
                            if (order.getClientOrderId() != null && order.getClientOrderId().equals(sellResponse.getOrder().getClientOrderId())) {
                                System.out.println("Limit sell order updated: " + order);
                                if (order.getStatus() == OrderStatus.Filled) {
                                    limitSellFilled = true;
                                }
                            }
                        }
                    } else if (data instanceof MyTradesCreated tradesCreated) {
                        for (Trade trade : tradesCreated.getTrades()) {
                            if (trade.getExecutionRole() == ExecutionRole.Taker) {
                                System.out.println("Market buy trade created: " + trade);
                                marketBuyFilled = true;
                            }
                        }
                    } else if (data instanceof MyTradesUpdated tradesUpdated) {
                        for (Trade trade : tradesUpdated.getTrades()) {
                            if (trade.getExecutionRole() == ExecutionRole.Taker) {
                                System.out.println("Market buy trade updated: " + trade);
                                if (trade.getSettlementStatus() == SettlementStatus.Completed) {
                                    System.out.println("Trade settled successfully!");
                                    tradeSettled = true;
                                } else if (trade.getSettlementStatus() == SettlementStatus.Failed) {
                                    System.out.println("Trade settlement failed: " + trade.getError());
                                    throw new RuntimeException("Trade settlement failed");
                                }
                            }
                        }
                    }
                }
            }
            TradesApiResponse trades = evmClient.listTrades(null, null, null);
            System.out.println("Found " + trades.getTrades().size() + " trades");
            for (Trade trade : trades.getTrades()) {
                System.out.println("Trade " + trade.getId() + " (client order id " + trade.getClientOrderId() + "): " + trade.getSide() + " " + trade.getAmount() + " of market " + trade.getMarketId() + " at " + trade.getPrice());
            }


            // Cancel all orders
            System.out.println("Cancelling all orders...");
            List<Order> orders = evmClient.listOrders(Collections.emptyList(), market.getId()).getOrders();
            evmClient.cancelOpenOrders(List.of(market.getId()));

            List<String> openOrders = orders.stream().filter(order -> order.getStatus() == OrderStatus.Open).map(order -> order.getClientOrderId()).toList();
            waitForCancelledOrders(webSocket, openOrders);
            System.out.println("All orders cancelled");

            // websocket can have cancel-on-disconnect behavior set
            // note - passing an empty list of market ids will cancel all
            webSocket.setCancelOnDisconnect(Collections.singletonList(market.getId()));

            // add another order
            CreateOrderApiRequest.Limit cancelOnDisconnectOrder =
                    new CreateOrderApiRequest.Limit(
                            generateOrderNonce(),
                            market.getId(),
                            OrderSide.Sell,
                            new OrderAmount.Fixed(toFundamentalUnits(makerBaseAmount, baseSymbol.getDecimals())),
                            new BigDecimal(price),
                            EvmSignature.Companion.emptySignature(),
                            evmKeyPair.address().getValue(),
                            evmChain.getId(),
                            "example-cancel-on-disconnect-" + System.currentTimeMillis(),
                            "recaptcha-token",
                            false
                    );

            System.out.println("Placing cancel-on-disconnect order...");

            CreateOrderApiRequest signedCancelOnDisconnectOrder = evmWallet.signOrder(cancelOnDisconnectOrder, null);
            CreateOrderApiResponse cancelOnDisconnectOrderResponse = evmClient.createOrder(signedCancelOnDisconnectOrder);
            System.out.println("Cancel-on-disconnect order placed: " + cancelOnDisconnectOrderResponse.getOrder());

            waitForOrderCreated(webSocket, cancelOnDisconnectOrderResponse.getOrder().getClientOrderId());

            // subscribe to orders with a new websocket
            ReconnectingWebsocketClient webSocket2 = evmClient.newWebSocket();
            webSocket2.subscribeToMyOrders();
            waitForSubscription(webSocket2, message -> message instanceof MyOrders);

            // now close the original websocket
            System.out.println("now disconnect the websocket");
            webSocket.close(new WsStatus(WsStatus.Companion.getNORMAL().getCode(), ""));

            // check that the order gets canceled
            waitForCancelledOrders(
                    webSocket2,
                    Collections.singletonList(cancelOnDisconnectOrderResponse.getClientOrderId())
            );
            System.out.println("cancel-on-disconnect order was canceled");

            webSocket = evmClient.newWebSocket();
            // Get final balances
            System.out.println("Retrieving final balances...");
            List<Balance> balances = evmClient.getBalances().getBalances();
            System.out.println("Final balances:");
            for (Balance balance : balances) {
                System.out.println(balance.getSymbol() + ": " + balance.getTotal() +
                        " (" + balance.getAvailable() + " available)");
            }

            // Subscribe to balance updates
            webSocket.subscribeToBalances();
            System.out.println("Waiting for balance subscription confirmation...");
            waitForSubscription(webSocket, message -> message instanceof Balances);

            // Withdraw balances
            System.out.println("Withdrawing available balances...");
            for (SymbolInfo symbol : Arrays.asList(baseSymbol, quoteSymbol)) {
                Balance balance = findBalanceBySymbol(balances, symbol);
                if (balance != null && balance.getAvailable().compareTo(BigInteger.ZERO) > 0) {
                    System.out.println("Withdrawing " + balance.getAvailable() + " " + balance.getSymbol());
                    if ((baseIsOnBitcoin && symbol.equals(baseSymbol)) ||
                            (quoteIsOnBitcoin && symbol.equals(quoteSymbol))) {
                        bitcoinClient.createWithdrawal(
                                bitcoinWallet.signWithdraw(symbol, balance.getAvailable())
                        );
                    } else {
                        evmWallet.withdraw(new AssetAmount(symbol, balance.getAvailable()));
                    }
                }
            }
            System.out.println("All withdrawal requests sent");

            // Wait for balances to go to zero
            System.out.println("Waiting for balances to be withdrawn...");
            boolean baseWithdrawn = false;
            boolean quoteWithdrawn = false;

            while (!baseWithdrawn || !quoteWithdrawn) {
                OutgoingWSMessage message = webSocket.receivedDecoded().iterator().next();
                if (message instanceof OutgoingWSMessage.Publish publish) {
                    Publishable data = publish.getData();

                    if (data instanceof BalancesUpdated balancesUpdated) {
                        for (UpdatedBalance balance : balancesUpdated.getBalances()) {
                            System.out.println("Balance updated: " + balance.getSymbol() + ": " +
                                    balance.getValue() + " (" + balance.getType() + ")");

                            String symbolName = balance.getSymbol();
                            if (symbolName.equals(baseSymbol.getName())) {
                                if (balance.getType() == BalanceType.Available &&
                                        balance.getValue().equals(BigInteger.ZERO)) {
                                    baseWithdrawn = true;
                                }
                            } else if (symbolName.equals(quoteSymbol.getName())) {
                                if (balance.getType() == BalanceType.Available &&
                                        balance.getValue().equals(BigInteger.ZERO)) {
                                    quoteWithdrawn = true;
                                }
                            }
                        }
                    }
                }
            }
            System.out.println("All balances withdrawn");

            System.out.println("Final state:");
            System.out.println("Limit sell filled: " + limitSellFilled);
            System.out.println("Market buy filled: " + marketBuyFilled);
            System.out.println("Trade settled: " + tradeSettled);

        } catch (Exception e) {
            System.out.println("Error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            webSocket.close(new WsStatus(WsStatus.Companion.getNORMAL().getCode(), ""));
            System.out.println("Example completed");
        }
        System.exit(0);
    }

    private static SymbolInfo findSymbol(List<SymbolInfo> symbols, String name) {
        for (SymbolInfo symbol : symbols) {
            if (symbol.getName().equals(name)) {
                return symbol;
            }
        }
        throw new RuntimeException("Symbol not found: " + name);
    }

    private static Market findMarket(List<Market> markets, String baseSymbol, String quoteSymbol) {
        for (Market market : markets) {
            if (market.getBaseSymbol().equals(baseSymbol) &&
                    market.getQuoteSymbol().equals(quoteSymbol)) {
                return market;
            }
        }
        throw new RuntimeException("Market not found: " + baseSymbol + "/" + quoteSymbol);
    }

    private static BigInteger findBalance(List<Balance> balances, SymbolInfo symbol) {
        for (Balance balance : balances) {
            if (balance.getSymbol().equals(symbol.getName())) {
                return balance.getAvailable();
            }
        }
        return BigInteger.ZERO;
    }

    private static Balance findBalanceBySymbol(List<Balance> balances, SymbolInfo symbol) {
        for (Balance balance : balances) {
            if (balance.getSymbol().equals(symbol.getName())) {
                return balance;
            }
        }
        return null;
    }

    private static void waitForSubscription(
            ReconnectingWebsocketClient webSocket,
            Predicate<Publishable> predicate
    ) {
        while (true) {
            OutgoingWSMessage message = webSocket.receivedDecoded().iterator().next();
            if (message instanceof OutgoingWSMessage.Publish publish) {
                if (predicate.test(publish.getData())) {
                    System.out.println("Subscription confirmed");
                    break;
                }
            }
        }
    }

    private static void waitForOrderCreated(
            ReconnectingWebsocketClient webSocket,
            String orderId
    ) {
        while (true) {
            OutgoingWSMessage message = webSocket.receivedDecoded().iterator().next();
            if (message instanceof OutgoingWSMessage.Publish publish) {
                Publishable data = publish.getData();

                if (data instanceof MyOrdersCreated ordersCreated) {
                    for (Order order : ordersCreated.getOrders()) {
                        if (order.getClientOrderId().equals(orderId)) {
                            System.out.println("Order created: " + ordersCreated);
                            return;
                        }
                    }
                }
            }
        }
    }

    private static void waitForCancelledOrders(
            ReconnectingWebsocketClient webSocket,
            List<String> orderIds
    ) {
        HashSet<String> remainingOrderIds = new HashSet<>(orderIds);
        while (!remainingOrderIds.isEmpty()) {
            Iterator<OutgoingWSMessage> iterator = webSocket.receivedDecoded().iterator();
            if (iterator.hasNext()) {
                OutgoingWSMessage publish = iterator.next();
                if (publish instanceof OutgoingWSMessage.Pong) {
                    // Do nothing
                } else if (publish instanceof OutgoingWSMessage.Publish) {
                    Object data = ((OutgoingWSMessage.Publish) publish).getData();
                    if (data instanceof MyOrdersUpdated) {
                        HashSet<String> updatedOrderIds = ((MyOrdersUpdated) data).getOrders().stream()
                                .map(Order::getClientOrderId)
                                .filter(Objects::nonNull)
                                .collect(Collectors.toCollection(HashSet::new));
                        remainingOrderIds.removeAll(updatedOrderIds);
                    }
                }
            }
        }
    }

    private static void waitForBalanceIncrease(
            ReconnectingWebsocketClient webSocket,
            SymbolInfo symbol,
            BigInteger initialBalance
    ) {
        System.out.println("Waiting for " + symbol + " deposit to complete...");
        while (true) {
            OutgoingWSMessage message = webSocket.receivedDecoded().iterator().next();
            if (message instanceof OutgoingWSMessage.Publish publish) {
                Publishable data = publish.getData();

                if (data instanceof BalancesUpdated balancesUpdated) {
                    for (UpdatedBalance balance : balancesUpdated.getBalances()) {
                        if (balance.getSymbol().equals(symbol.getName()) &&
                                balance.getType() == BalanceType.Available) {

                            System.out.println(symbol + " balance updated: " + balance.getValue());
                            if (balance.getValue().compareTo(initialBalance) > 0) {
                                System.out.println(symbol + " deposit completed");
                                return;
                            }
                        }
                    }
                }
            }
        }
    }
}