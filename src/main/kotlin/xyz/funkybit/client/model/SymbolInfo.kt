package xyz.funkybit.client.model

import kotlinx.serialization.Serializable
import xyz.funkybit.client.model.address.Address
import xyz.funkybit.client.utils.BigIntegerJson

@Serializable
data class SymbolInfo(
    val name: String,
    val description: String,
    val contractAddress: Address?,
    val decimals: Int,
    val faucetSupported: Boolean,
    val iconUrl: String,
    val withdrawalFee: BigIntegerJson,
    val chainId: String,
    val chainName: String,
)
