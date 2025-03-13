package xyz.funkybit.client.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import xyz.funkybit.client.utils.BigIntegerJson

@Serializable
data class CreateDepositApiRequest(
    val symbol: Symbol,
    val amount: BigIntegerJson,
    val txHash: TxHash,
)

@Serializable
data class Deposit(
    val id: DepositId,
    val symbol: Symbol,
    val amount: BigIntegerJson,
    val status: Status,
    val error: String?,
    val createdAt: Instant,
    val txHash: TxHash,
) {
    @Serializable
    enum class Status {
        Pending,
        Complete,
        Failed,
    }
}

@Serializable
data class DepositApiResponse(
    val deposit: Deposit,
)

@Serializable
data class ListDepositsApiResponse(
    val deposits: List<Deposit>,
)
