# FunkyBit API Client Example

This example demonstrates how to use the FunkyBit API client to:
1. Connect to the FunkyBit API
2. Deposit assets (both EVM and Bitcoin)
3. Place limit and market orders
4. Track order and trade updates
5. Cancel orders
6. Withdraw assets

## Prerequisites

- Java 17 or higher
- Gradle 7.0 or higher
- A running instance of the FunkyBit API server (default: https://prod-api.funkybit.fun)
- EVM private key with sufficient funds for deposits and gas fees
- Bitcoin private key with sufficient funds (if trading Bitcoin)

## Configuration

The example can be configured by modifying the following variables in `Example.kt`:

```kotlin
val endpoint = "https://prod-api.funkybit.fun"  // FunkyBit API endpoint
val base = "BTC"                        // Base asset symbol
val baseIsOnBitcoin = false            // Whether base asset is on Bitcoin
val quote = "BTC"                      // Quote asset symbol
val quoteIsOnBitcoin = true            // Whether quote asset is on Bitcoin
val makerBaseAmount = "0.0001"         // Amount for limit sell order
val price = "1.0"                      // Price for limit sell order
val takerQuoteAmount = "0.0001"        // Amount for market buy order
val privateKey = "0x..."               // Your EVM private key
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
- The example uses a BTC/BTC market for demonstration
- Adjust amounts and prices based on your needs
- The example includes proper cleanup of orders and balances 