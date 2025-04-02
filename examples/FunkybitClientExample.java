import kotlinx.datetime.Clock;
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
import xyz.funkybit.client.model.UpdatedBalance;
import xyz.funkybit.client.model.address.EvmAddress;
import xyz.funkybit.client.model.signature.EvmSignature;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

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
        
        WalletKeyPair.EVM evmKeyPair = WalletKeyPair.EVM.Companion.fromPrivateKeyHex(privateKey);
        FunkybitApiClient client = new FunkybitApiClient(
                evmKeyPair,
                endpoint,
                "1",
                WalletKeyPair.EVM.Companion.generate()
        );
        Wallet wallet = Wallet.Companion.invoke(client);
        System.out.println("Connected with address: " + client.getAddress());

        ConfigurationApiResponse config = client.getConfiguration();
        AccountConfigurationApiResponse accountConfig = client.getAccountConfiguration();

        // authorize bitcoin wallet
        WalletKeyPair.Bitcoin btcKeyPair = WalletKeyPair.Bitcoin.Companion.fromPrivateKeyHex(privateKey, getBitcoinConfig().getParams());
        FunkybitApiClient bitcoinClient = new FunkybitApiClient(
            btcKeyPair, 
            endpoint,
            BITCOIN,
            WalletKeyPair.EVM.Companion.generate()
        );
        BitcoinWallet bitcoinWallet = new BitcoinWallet(btcKeyPair, config.getChains(), bitcoinClient);
        bitcoinClient.authorizeWallet(
            bitcoinWallet.signAuthorizeBitcoinWalletRequest(
                evmKeyPair.getEcKeyPair(),
                (EvmAddress) client.getAddress(),
                bitcoinWallet.getWalletAddress(),
                wallet.getCurrentChainId(),
                Clock.System.INSTANCE.now()
            )
        );

        // Connect to WebSocket for real-time updates
        ReconnectingWebsocketClient webSocket = client.newWebSocket(client.getAuthToken());

        try {
            Chain evmChain = config.getEvmChains().get(0);
            client.switchChain(evmChain.getId());
            
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
            List<Balance> initialBalances = client.getBalances().getBalances();
            BigInteger initialBaseBalance = findBalance(initialBalances, baseSymbol);
            System.out.println("Initial " + base + " balance: " + initialBaseBalance);

            // Deposit base for the limit sell
            AssetAmount walletBaseBalance = wallet.getWalletBalance(baseSymbol);
            if (walletBaseBalance.getAmount().compareTo(makerBaseAmount) < 0) {
                throw new RuntimeException(
                    "Need at least " + makerBaseAmount + " " + base + " in " + 
                    (baseIsOnBitcoin ? bitcoinClient.getAddress() : client.getAddress()) + 
                    " on chain " + (baseIsOnBitcoin ? BITCOIN : evmChain.getId())
                );
            }
            
            if (baseIsOnBitcoin) {
                bitcoinWallet.depositNative(toFundamentalUnits(makerBaseAmount, 8));
            } else {
                wallet.deposit(new AssetAmount(baseSymbol, makerBaseAmount));
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
                "recaptcha-token"
            );

            CreateOrderApiRequest signedSellOrder = wallet.signOrder(sellOrder, null);
            System.out.println("Placing limit sell order...");
            CreateOrderApiResponse sellResponse = client.createOrder(signedSellOrder);
            System.out.println("Limit sell order placed: " + sellResponse.getOrder());

            // Wait for order created confirmation
            System.out.println("Waiting for order created...");
            waitForOrderCreated(webSocket, sellResponse.getOrder().getClientOrderId());
            
            AssetAmount walletQuoteBalance = wallet.getWalletBalance(quoteSymbol);
            if (walletQuoteBalance.getAmount().compareTo(takerQuoteAmount) < 0) {
                throw new RuntimeException(
                    "Need at least " + takerQuoteAmount + " " + quote + " in " + 
                    (quoteIsOnBitcoin ? bitcoinClient.getAddress() : client.getAddress()) + 
                    " on chain " + (quoteIsOnBitcoin ? BITCOIN : evmChain.getId())
                );
            }
            
            // Deposit quote for the market buy
            if (quoteIsOnBitcoin) {
                bitcoinWallet.depositNative(toFundamentalUnits(takerQuoteAmount, 8));
            } else {
                wallet.deposit(new AssetAmount(quoteSymbol, takerQuoteAmount));
            }
            System.out.println("Deposited " + takerQuoteAmount + " " + quote);

            // Subscribe to balance updates for quote deposit
            webSocket.subscribeToBalances();
            System.out.println("Waiting for balance subscription confirmation...");
            waitForSubscription(webSocket, message -> message instanceof Balances);

            // Get initial quote balance
            BigInteger initialQuoteBalance = findBalance(client.getBalances().getBalances(), quoteSymbol);
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

            CreateOrderApiRequest signedMarketBuyOrder = wallet.signOrder(marketBuyOrder, false);
            System.out.println("Placing market buy order...");
            CreateOrderApiResponse buyResponse = client.createOrder(signedMarketBuyOrder);
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

            // Cancel all orders
            System.out.println("Cancelling all orders...");
            List<Order> orders = client.listOrders(Collections.emptyList(), market.getId()).getOrders();
            client.cancelOpenOrders(List.of(market.getId()));
            
            for (Order order : orders) {
                if (order.getStatus() == OrderStatus.Open) {
                    System.out.println("Cancelling order: " + order.getClientOrderId());
                    // Wait for order cancelled confirmation
                    if (order.getClientOrderId() != null) waitForOrderCancelled(webSocket, order.getClientOrderId());
                }
            }
            System.out.println("All orders cancelled");

            // Get final balances
            System.out.println("Retrieving final balances...");
            List<Balance> balances = client.getBalances().getBalances();
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
                        wallet.withdraw(new AssetAmount(symbol, balance.getAvailable()));
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

    private static void waitForOrderCancelled(
        ReconnectingWebsocketClient webSocket,
        String orderId
    ) {
        while (true) {
            OutgoingWSMessage message = webSocket.receivedDecoded().iterator().next();
            if (message instanceof OutgoingWSMessage.Publish publish) {
                Publishable data = publish.getData();
                
                if (data instanceof MyOrdersUpdated ordersUpdated) {
                    for (Order order : ordersUpdated.getOrders()) {
                        if (order.getClientOrderId().equals(orderId) && 
                            order.getStatus() == OrderStatus.Cancelled) {
                            System.out.println("Order cancelled: " + ordersUpdated);
                            return;
                        }
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
