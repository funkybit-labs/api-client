package xyz.funkybit.client.model

import kotlinx.serialization.Serializable

typealias MarketId = String

fun baseAndQuoteSymbols(marketId: MarketId): Pair<String, String> = marketId.split('/', limit = 2).let { Pair(it[0], it[1]) }

typealias Symbol = String
typealias TradeId = String
typealias Percentage = Int
typealias ClientOrderId = String
typealias OrderId = String
typealias DepositId = String
typealias WithdrawalId = String
typealias BlockHash = String
typealias UserId = String

@Serializable(with = BitcoinRpcParamsSerializer::class)
data class BitcoinRpcParams(
    val value: Any,
)

@Serializable
data class BitcoinUtxoId(
    val value: String,
) {
    init {
        require(value.split(":").size == 2) {
            "Invalid utxoId format"
        }
    }

    companion object {
        fun fromTxHashAndVout(
            txId: TxHash,
            vout: Long,
        ): BitcoinUtxoId = BitcoinUtxoId("$txId:$vout")
    }

    fun txId(): TxHash = TxHash.auto(value.split(":")[0])

    fun vout(): Long = value.split(":")[1].toLong()

    override fun toString(): String = value
}
