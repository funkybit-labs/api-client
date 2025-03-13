package xyz.funkybit.client.model

import kotlinx.serialization.Serializable
import xyz.funkybit.client.model.address.Address
import java.math.BigInteger

@Serializable
enum class WalletNetworkType {
    Evm,
    Bitcoin,
}

sealed class ChainId(
    open val value: String,
) {
    override fun toString(): String = value

    data class Evm(
        override val value: String,
    ) : ChainId(value) {
        constructor(value: ULong) : this(value.toString())
    }

    data object Arch : ChainId(ARCH)

    data object CoinProxy : ChainId(COINPROXY)

    data object Bitcoin : ChainId(BITCOIN)

    fun isEvm(): Boolean =
        when (this) {
            is Evm -> true
            else -> false
        }

    fun walletNetworkType(): WalletNetworkType =
        when (this) {
            is Arch -> WalletNetworkType.Bitcoin
            is CoinProxy -> WalletNetworkType.Bitcoin
            is Bitcoin -> WalletNetworkType.Bitcoin
            is Evm -> WalletNetworkType.Evm
        }

    fun toLong(): Long =
        when (this) {
            is CoinProxy -> -2
            is Arch -> -1
            is Bitcoin -> 0
            is Evm -> value.toLong()
        }

    companion object {
        private const val ARCH = "arch"
        private const val COINPROXY = "coinproxy"
        const val BITCOIN = "bitcoin"

        fun fromString(value: String): ChainId =
            when (value) {
                ARCH -> Arch
                COINPROXY -> CoinProxy
                BITCOIN -> Bitcoin
                else -> Evm(value)
            }

        fun evm(evmChainId: ULong): ChainId = Evm(evmChainId.toString())

        fun evm(evmChainId: Long): ChainId = Evm(evmChainId.toString())

        fun evm(evmChainId: BigInteger): ChainId = Evm(evmChainId.toString())
    }
}

@Serializable
data class DeployedContract(
    val name: String,
    val address: Address,
    val nativeDepositAddress: Address,
    val tokenDepositAddress: Address,
)

@Serializable
data class Chain(
    val id: Id,
    val name: String,
    val contracts: List<DeployedContract>,
    val symbols: List<SymbolInfo>,
    val jsonRpcUrl: String,
    val blockExplorerNetName: String,
    val blockExplorerUrl: String,
) {
    @Serializable
    @JvmInline
    value class Id(
        val value: String,
    ) {
        override fun toString(): String = value

        fun isEvm(): Boolean = !isBitcoin()

        fun isBitcoin(): Boolean = value == ChainId.BITCOIN

        fun toDbId(): ChainId =
            when {
                value == ChainId.BITCOIN -> ChainId.Bitcoin
                value.toLongOrNull() != null -> ChainId.Evm(value)
                else -> throw RuntimeException("Can't convert $value to ChainId")
            }

        companion object {
            operator fun invoke(id: ChainId): Id =
                when (id) {
                    is ChainId.Bitcoin, is ChainId.Evm -> Id(id.value)
                    else -> throw RuntimeException("Unexpected chain id: $id")
                }
        }
    }
}
