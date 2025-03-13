package xyz.funkybit.client.model

import kotlinx.serialization.Serializable
import xyz.funkybit.client.utils.BigDecimalJson
import xyz.funkybit.client.utils.BigIntegerJson

enum class MarketType {
    Clob,
    BondingCurve,
    Amm,
}

@Serializable
data class Market(
    val id: MarketId,
    val baseSymbol: Symbol,
    val baseDecimals: Int,
    val quoteSymbol: Symbol,
    val quoteDecimals: Int,
    val tickSize: BigDecimalJson,
    val lastPrice: BigDecimalJson,
    val minFee: BigIntegerJson,
    val type: MarketType,
)
