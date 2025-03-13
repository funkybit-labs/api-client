package xyz.funkybit.client.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import xyz.funkybit.client.utils.BigIntegerJson

@Serializable
data class Balance(
    val symbol: Symbol,
    val total: BigIntegerJson,
    val available: BigIntegerJson,
    val lastUpdated: Instant,
)

@Serializable
data class BalancesApiResponse(
    val balances: List<Balance>,
) {
    companion object {
        fun empty() = BalancesApiResponse(emptyList())
    }
}
