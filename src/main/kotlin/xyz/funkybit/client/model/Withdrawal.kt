package xyz.funkybit.client.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import xyz.funkybit.client.model.signature.Signature
import xyz.funkybit.client.utils.BigIntegerJson

@Serializable
enum class WithdrawalStatus {
    Pending,
    Sequenced,
    Preparing,
    Prepared,
    Settling,
    Complete,
    PendingRollback,
    RollingBack,
    Failed,
    WaitingForUtxo,
    ;

    fun isFinal(): Boolean = this in listOf(Complete, Failed)
}

@Serializable
data class CreateWithdrawalApiRequest(
    val symbol: Symbol,
    val amount: BigIntegerJson,
    val nonce: Long,
    val signature: Signature,
)

@Serializable
data class Withdrawal(
    val id: WithdrawalId,
    val symbol: Symbol,
    val amount: BigIntegerJson,
    val status: WithdrawalStatus,
    val error: String?,
    val createdAt: Instant,
    val txHash: TxHash?,
    val fee: BigIntegerJson,
)

@Serializable
data class WithdrawalApiResponse(
    val withdrawal: Withdrawal,
)

@Serializable
data class ListWithdrawalsApiResponse(
    val withdrawals: List<Withdrawal>,
)
