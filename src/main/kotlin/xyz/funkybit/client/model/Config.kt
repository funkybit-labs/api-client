package xyz.funkybit.client.model

import kotlinx.serialization.Serializable
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
