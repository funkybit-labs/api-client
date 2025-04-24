package xyz.funkybit.client.utils

import org.web3j.crypto.StructuredData
import org.web3j.crypto.StructuredDataEncoder
import xyz.funkybit.client.model.AuthorizeWalletAddressMessage
import xyz.funkybit.client.model.EIP712Transaction
import xyz.funkybit.client.model.SignInMessage
import xyz.funkybit.client.model.address.Address

object EIP712Helper {
    fun computeHash(
        tx: EIP712Transaction,
        chainId: String,
        verifyingContract: Address,
    ): ByteArray {
        //
        // Structured data contains both the schema and the data, the 'types' section holds the schema. The field ordering
        // in type definitions is important since that is the order in which the data in these fields is appended
        // before being hashed for signing.
        //
        val encoder =
            StructuredDataEncoder(
                StructuredData.EIP712Message(
                    // types =
                    hashMapOf(
                        "EIP712Domain" to
                            listOf(
                                StructuredData.Entry("name", "string"),
                                StructuredData.Entry("version", "string"),
                                StructuredData.Entry("chainId", "uint256"),
                                StructuredData.Entry("verifyingContract", "address"),
                            ),
                        tx.getTransactionType().name to tx.getModel(),
                    ),
                    // primaryType =
                    tx.getTransactionType().name,
                    // message =
                    tx.getMessage(),
                    StructuredData.EIP712Domain(
                        // name =
                        "funkybit",
                        // version =
                        "0.1.0",
                        // chainId =
                        chainId,
                        // verifyingContract =
                        verifyingContract.toString(),
                        // salt =
                        null,
                    ),
                ),
            )
        return encoder.hashStructuredData()
    }

    fun structuredDataAsJson(signInMessage: SignInMessage): String =
        StructuredDataEncoder.mapper.writeValueAsString(
            signInMessageEncoder(signInMessage).jsonMessageObject,
        )

    fun computeHash(signInMessage: SignInMessage): ByteArray = signInMessageEncoder(signInMessage).hashStructuredData()

    private fun signInMessageEncoder(signInMessage: SignInMessage) =
        StructuredDataEncoder(
            StructuredData.EIP712Message(
                // types =
                hashMapOf(
                    "EIP712Domain" to
                        listOf(
                            StructuredData.Entry("name", "string"),
                            StructuredData.Entry("chainId", "uint256"),
                        ),
                    "Sign In" to
                        listOf(
                            StructuredData.Entry("message", "string"),
                            StructuredData.Entry("address", "string"),
                            StructuredData.Entry("chainId", "uint256"),
                            StructuredData.Entry("timestamp", "string"),
                            StructuredData.Entry("sessionKeyAddress", "string"),
                        ),
                ),
                // primaryType =
                "Sign In",
                // message =
                mapOf(
                    "message" to signInMessage.message,
                    "address" to signInMessage.address,
                    "chainId" to signInMessage.chainId.toString(),
                    "timestamp" to signInMessage.timestamp,
                    "sessionKeyAddress" to signInMessage.sessionKeyAddress,
                ),
                // domain =
                StructuredData.EIP712Domain(
                    // name =
                    "funkybit",
                    // version =
                    null,
                    // chainId =
                    signInMessage.chainId.toString(),
                    // verifyingContract =
                    null,
                    // salt =
                    null,
                ),
            ),
        )

    fun structuredDataAsJson(authorizeMessage: AuthorizeWalletAddressMessage): String =
        StructuredDataEncoder.mapper.writeValueAsString(
            authorizeWalletMessageEncoder(authorizeMessage).jsonMessageObject,
        )

    fun computeHash(authorizeMessage: AuthorizeWalletAddressMessage): ByteArray =
        authorizeWalletMessageEncoder(authorizeMessage).hashStructuredData()

    private fun authorizeWalletMessageEncoder(authorizeMessage: AuthorizeWalletAddressMessage) =
        StructuredDataEncoder(
            StructuredData.EIP712Message(
                // types =
                hashMapOf(
                    "EIP712Domain" to
                        listOf(
                            StructuredData.Entry("name", "string"),
                            StructuredData.Entry("chainId", "uint256"),
                        ),
                    "Authorize" to
                        listOf(
                            StructuredData.Entry("message", "string"),
                            StructuredData.Entry("address", "string"),
                            StructuredData.Entry("authorizedAddress", "string"),
                            StructuredData.Entry("chainId", "uint256"),
                            StructuredData.Entry("timestamp", "string"),
                        ),
                ),
                // primaryType =
                "Authorize",
                // message =
                mapOf(
                    "message" to authorizeMessage.message,
                    "address" to authorizeMessage.address,
                    "authorizedAddress" to authorizeMessage.authorizedAddress,
                    "chainId" to authorizeMessage.chainId,
                    "timestamp" to authorizeMessage.timestamp,
                ),
                // domain =
                StructuredData.EIP712Domain(
                    // name =
                    "funkybit",
                    // version =
                    null,
                    // chainId =
                    authorizeMessage.chainId,
                    // verifyingContract =
                    null,
                    // salt =
                    null,
                ),
            ),
        )
}
