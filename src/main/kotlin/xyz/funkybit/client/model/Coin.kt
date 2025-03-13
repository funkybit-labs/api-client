package xyz.funkybit.client.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import xyz.funkybit.client.utils.BigDecimalJson

@Serializable
enum class CoinStatus {
    Pending,
    BondingCurveAmm,
    Graduating,
    ConstantProductAmm,
}

@Serializable
data class CoinCreatorRef(
    val name: String,
    val userId: UserId,
)

@Serializable
data class Coin(
    val symbol: SymbolInfo,
    val createdBy: CoinCreatorRef,
    val currentPrice: BigDecimalJson,
    val marketCap: BigDecimalJson,
    val lastTradedAt: Instant?,
    val progress: BigDecimalJson,
    val status: CoinStatus,
    val sequenceNumber: Long,
    val h24Volume: BigDecimalJson,
    val h24Change: BigDecimalJson,
    val d7Change: BigDecimalJson,
    val tvl: BigDecimalJson,
    val h24PoolVolume: BigDecimalJson,
    val h24PoolFees: BigDecimalJson,
    val h24MinPoolYield: BigDecimalJson,
    val h24MaxPoolYield: BigDecimalJson,
    val lastPoolCreatedAt: Instant,
)
