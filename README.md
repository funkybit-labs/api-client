# funkybit API Client Example

This example demonstrates how to use the funkybit API client to:
1. Connect to the funkybit API
2. Deposit assets (both EVM and Bitcoin)
3. Place limit and market orders
4. Track order and trade updates
5. Cancel orders
6. Withdraw assets

## Prerequisites

- Java 17 or higher
- Gradle 8.0 or higher
- EVM and Bitcoin accounts with sufficient funds for deposits and gas fees

## Configuration

The example can be configured by modifying the following variables in `Example.kt`:

```kotlin
val endpoint = "https://prod-api.funkybit.fun"  // funkybit API endpoint
val base = "BTC"                        // Base asset symbol
val baseIsOnBitcoin = false            // Whether base asset is on Bitcoin
val quote = "BTC"                      // Quote asset symbol
val quoteIsOnBitcoin = true            // Whether quote asset is on Bitcoin
val makerBaseAmount = "0.0001"         // Amount for limit sell order
val price = "1.0"                      // Price for limit sell order
val takerQuoteAmount = "0.0001"        // Amount for market buy order
val privateKey = "0x..."               // Your private key (used for both evm and bitcoin addresses)
```

Alternatively, you can configure the client using environment variables:

```bash
# API Configuration
FUNKYBIT_API_URL=https://prod-api.funkybit.fun  # API endpoint URL
FUNKYBIT_PRIVATE_KEY=0x...

# Bitcoin Network Configuration
BITCOIN_NETWORK_NAME=org.bitcoin.production  # Bitcoin network name
BITCOIN_MIN_FEE_VALUE=5                # Minimum fee value in satoshis
BITCOIN_MAX_FEE_VALUE=50               # Maximum fee value in satoshis
BITCOIN_CHANGE_DUST_THRESHOLD=546      # Change dust threshold in satoshis
BLOCK_EXPLORER_NET_NAME_BITCOIN="Bitcoin Network"  # Block explorer network name
BLOCK_EXPLORER_URL_BITCOIN=https://mempool.space   # Block explorer URL

# Web3 Configuration
ENABLE_WEB3J_LOGGING=true              # Enable/disable Web3j logging
MAX_RPC_NODE_EVENTUAL_CONSISTENCE_TOLERANCE_MS=60000  # Max RPC node consistency tolerance in ms

# Mempool.space Configuration
MEMPOOL_SPACE_API_URL=https://mempool.space/api  # Mempool.space API URL
MEMPOOL_SPACE_RECOMMENDED_FEE_ROUTE=fees/recommended  # Recommended fee route

```

## Running the Example

1. Build the project:
```bash
./gradlew build
```

2. Run the example:
```bash
./gradlew runExample
```

## Notes

- Make sure to use valid private keys with sufficient funds
- Adjust amounts and prices based on your needs
- The example includes proper cleanup of orders and balances