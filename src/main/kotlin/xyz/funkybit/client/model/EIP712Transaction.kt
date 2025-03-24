package xyz.funkybit.client.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import org.web3j.abi.DefaultFunctionEncoder
import org.web3j.abi.datatypes.DynamicBytes
import org.web3j.abi.datatypes.DynamicStruct
import org.web3j.abi.datatypes.StaticStruct
import org.web3j.abi.datatypes.generated.Uint256
import org.web3j.abi.datatypes.generated.Uint64
import org.web3j.crypto.StructuredData
import xyz.funkybit.client.model.ChainId.Companion.BITCOIN
import xyz.funkybit.client.model.address.Address
import xyz.funkybit.client.model.signature.EvmSignature
import xyz.funkybit.client.utils.BigIntegerJson
import xyz.funkybit.client.utils.toHexBytes
import java.math.BigInteger

enum class EIP712TransactionType {
    Withdraw,
    Order,
    PercentageOrder,
    Trade,
    CancelOrder,
}

@Serializable
data class TokenAddressAndChain(
    val address: Address,
    val chainId: String,
)

@Serializable
@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
sealed class EIP712Transaction {
    abstract val signature: EvmSignature

    @Serializable
    @SerialName("withdraw")
    data class WithdrawTx(
        val sender: Address,
        val token: TokenAddressAndChain,
        val amount: BigIntegerJson,
        val nonce: Long,
        val withdrawAll: Boolean,
        override val signature: EvmSignature,
        val fee: BigIntegerJson = BigInteger.ZERO,
    ) : EIP712Transaction() {
        override fun getTransactionType(): EIP712TransactionType = EIP712TransactionType.Withdraw

        override fun getModel(): List<StructuredData.Entry> =
            listOf(
                StructuredData.Entry("sender", "address"),
                StructuredData.Entry("token", "address"),
                StructuredData.Entry("amount", "uint256"),
                StructuredData.Entry("nonce", "uint64"),
            )

        override fun getMessage(): Map<String, String> {
            val message = mutableMapOf<String, String>()
            message["sender"] = sender.toString()
            message["token"] = token.address.toString()
            message["amount"] = if (withdrawAll) "0" else amount.toString()
            message["nonce"] = nonce.toString()
            return message
        }

        private object Abi {
            enum class TransactionType {
                Withdraw,
                WithdrawAll,
            }

            class Withdraw(
                sequence: Long,
                sender: String,
                token: String,
                amount: BigInteger,
                nonce: BigInteger,
                fee: BigInteger,
            ) : StaticStruct(
                    Uint256(sequence),
                    org.web3j.abi.datatypes
                        .Address(160, sender),
                    org.web3j.abi.datatypes
                        .Address(160, token),
                    Uint256(amount),
                    Uint64(nonce),
                    Uint256(fee),
                )

            class WithdrawWithSignature(
                tx: Withdraw,
                signature: ByteArray,
            ) : DynamicStruct(
                    tx,
                    DynamicBytes(signature),
                )
        }

        override fun getTxData(sequence: Long): ByteArray {
            val txType = if (withdrawAll) Abi.TransactionType.WithdrawAll else Abi.TransactionType.Withdraw
            val struct =
                Abi.WithdrawWithSignature(
                    Abi.Withdraw(sequence, sender.toString(), token.address.toString(), amount, nonce.toBigInteger(), fee),
                    signature.toByteArray(),
                )
            return listOf(txType.ordinal.toByte()).toByteArray() + DefaultFunctionEncoder().encodeParameters(listOf(struct)).toHexBytes()
        }
    }

    @Serializable
    @SerialName("order")
    data class Order(
        val sender: String,
        val baseChainId: String,
        val baseToken: String,
        val quoteChainId: String,
        val quoteToken: String,
        val amount: OrderAmount,
        val price: BigIntegerJson,
        val nonce: BigIntegerJson,
        override val signature: EvmSignature,
    ) : EIP712Transaction() {
        override fun getTransactionType(): EIP712TransactionType =
            when (amount) {
                is OrderAmount.Fixed -> EIP712TransactionType.Order
                is OrderAmount.Percent -> EIP712TransactionType.PercentageOrder
            }

        override fun getModel(): List<StructuredData.Entry> =
            listOf(
                StructuredData.Entry("sender", "string"),
                StructuredData.Entry("baseChainId", "uint256"),
                StructuredData.Entry("baseToken", "string"),
                StructuredData.Entry("quoteChainId", "uint256"),
                StructuredData.Entry("quoteToken", "string"),
                when (amount) {
                    is OrderAmount.Fixed -> StructuredData.Entry("amount", "int256")
                    is OrderAmount.Percent -> StructuredData.Entry("percentage", "int256")
                },
                StructuredData.Entry("price", "uint256"),
                StructuredData.Entry("nonce", "int256"),
            )

        override fun getMessage(): Map<String, String> {
            fun formatChainId(chainId: String): String = if (chainId == BITCOIN) "0" else chainId

            return mapOf(
                "sender" to sender,
                "baseChainId" to formatChainId(baseChainId),
                "baseToken" to baseToken,
                "quoteChainId" to formatChainId(quoteChainId),
                "quoteToken" to quoteToken,
                when (amount) {
                    is OrderAmount.Fixed -> "amount" to amount.value.toString()
                    is OrderAmount.Percent -> "percentage" to amount.value.toString()
                },
                "price" to price.toString(),
                "nonce" to nonce.toString(),
            )
        }

        override fun getTxData(sequence: Long): ByteArray = ByteArray(0)
    }

    @Serializable
    @SerialName("cancelOrder")
    data class CancelOrder(
        val sender: String,
        val marketId: MarketId,
        val amount: BigIntegerJson,
        val nonce: BigIntegerJson,
        override val signature: EvmSignature,
    ) : EIP712Transaction() {
        override fun getTransactionType(): EIP712TransactionType = EIP712TransactionType.CancelOrder

        override fun getModel(): List<StructuredData.Entry> =
            listOf(
                StructuredData.Entry("sender", "string"),
                StructuredData.Entry("marketId", "string"),
                StructuredData.Entry("amount", "int256"),
                StructuredData.Entry("nonce", "int256"),
            )

        override fun getMessage(): Map<String, String> =
            mapOf(
                "sender" to sender,
                "marketId" to marketId,
                "amount" to amount.toString(),
                "nonce" to nonce.toString(),
            )

        override fun getTxData(sequence: Long): ByteArray = ByteArray(0)
    }

    abstract fun getModel(): List<StructuredData.Entry>

    abstract fun getTransactionType(): EIP712TransactionType

    abstract fun getMessage(): Map<String, String>

    abstract fun getTxData(sequence: Long): ByteArray
}
