package xyz.funkybit.client.model

import kotlinx.serialization.Serializable
import xyz.funkybit.client.model.address.Address

@Serializable
data class SignInMessage(
    val message: String,
    val address: String,
    val chainId: Long,
    val timestamp: String,
    val sessionKeyAddress: String,
    val ordinalsAddress: OrdinalsAddress?,
) {
    @Serializable
    data class OrdinalsAddress(
        val address: String,
        val ownershipProof: OwnershipProof?,
    ) {
        @Serializable
        data class OwnershipProof(
            val signature: String,
            val timestamp: String,
        )
    }
}

@Serializable
data class AuthorizeWalletAddressMessage(
    val message: String,
    val authorizedAddress: String,
    val address: String,
    val chainId: Chain.Id,
    val timestamp: String,
)

@Serializable
data class AuthorizeWalletApiRequest(
    val authorizedAddress: Address,
    val chainId: Chain.Id,
    val address: Address,
    val timestamp: String,
    val signature: String,
)
