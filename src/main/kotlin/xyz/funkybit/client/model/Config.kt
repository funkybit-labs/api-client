package xyz.funkybit.client.model

import kotlinx.serialization.Serializable
import xyz.funkybit.client.model.address.Address
import xyz.funkybit.client.model.address.BitcoinAddress
import xyz.funkybit.client.utils.BigDecimalJson

@Serializable
data class FeeRates(
    val maker: BigDecimalJson,
    val taker: BigDecimalJson,
)

@Serializable
data class ConfigurationApiResponse(
    val chains: List<Chain>,
    val markets: List<Market>,
    val feeRates: FeeRates,
    val minimumRune: String,
) {
    val evmChains: List<Chain>
        get() = chains.filter { it.id.isEvm() }

    val bitcoinChain: Chain
        get() = chains.first { it.id.isBitcoin() }
}

enum class Role {
    User,
    Admin,
}

@Serializable
data class AuthorizedAddress(
    val address: Address,
    val networkType: WalletNetworkType,
)

@Serializable
data class AccountConfigurationApiResponse(
    val id: UserId,
    val newSymbols: List<SymbolInfo>,
    val associatedCoins: List<Coin>,
    val role: Role,
    val authorizedAddresses: List<AuthorizedAddress>,
    val nickName: String?,
    val avatarUrl: String?,
    val inviteCode: String,
    val ordinalsAddress: BitcoinAddress?,
)
