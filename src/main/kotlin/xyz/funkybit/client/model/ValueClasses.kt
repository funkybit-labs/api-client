package xyz.funkybit.client.model

import kotlinx.serialization.Serializable

@JvmInline
@Serializable
value class SequencerAccountId(
    val value: Long,
)

@JvmInline
@Serializable
value class SequencerWalletId(
    val value: Long,
)

@JvmInline
@Serializable
value class SequencerOrderId(
    val value: Long,
)

@Serializable
@JvmInline
value class MarketId(
    val value: String,
) {
    override fun toString(): String = value

    fun baseAndQuoteSymbols(): Pair<String, String> = this.value.split('/', limit = 2).let { Pair(it[0], it[1]) }

    fun baseSymbol() = baseAndQuoteSymbols().first

    fun quoteSymbol() = baseAndQuoteSymbols().second

    fun hasSymbol(symbol: String) = baseAndQuoteSymbols().let { it.first == symbol || it.second == symbol }
}

@Serializable
@JvmInline
value class Symbol(
    val value: String,
)

@Serializable
@JvmInline
value class TradeId(
    val value: String,
)

@Serializable
@JvmInline
value class Percentage(
    val value: Int,
) {
    init {
        require(value in 1..MAX_VALUE) { "Invalid percentage" }
    }

    companion object {
        const val MAX_VALUE = 100
    }
}

@Serializable
@JvmInline
value class ClientOrderId(
    val value: String,
)

@Serializable
@JvmInline
value class OrderId(
    val value: String,
)

@Serializable
@JvmInline
value class ExecutionId(
    val value: String,
)

@Serializable
@JvmInline
value class DepositId(
    val value: String,
)

@Serializable
@JvmInline
value class WithdrawalId(
    val value: String,
)
