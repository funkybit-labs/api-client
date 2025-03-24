package xyz.funkybit.client

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.awaitility.kotlin.await
import org.awaitility.kotlin.withAlias
import org.web3j.crypto.Credentials
import org.web3j.crypto.ECKeyPair
import org.web3j.crypto.Keys
import org.web3j.crypto.Sign
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameter
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.core.RemoteFunctionCall
import org.web3j.protocol.core.methods.request.EthFilter
import org.web3j.protocol.core.methods.response.EthBlock
import org.web3j.protocol.core.methods.response.EthLog
import org.web3j.protocol.core.methods.response.TransactionReceipt
import org.web3j.protocol.http.HttpService
import org.web3j.tx.RawTransactionManager
import org.web3j.tx.exceptions.ContractCallException
import org.web3j.tx.response.PollingTransactionReceiptProcessor
import org.web3j.utils.Async
import org.web3j.utils.Numeric
import xyz.funkybit.client.model.TxHash
import xyz.funkybit.client.model.address.Address
import xyz.funkybit.client.model.address.EvmAddress
import xyz.funkybit.client.model.signature.EvmSignature
import xyz.funkybit.client.model.signature.toEvmSignature
import xyz.funkybit.client.utils.GasProvider
import xyz.funkybit.client.utils.JsonRpcClientBase
import xyz.funkybit.client.utils.JsonRpcException
import xyz.funkybit.client.utils.toHex
import java.math.BigInteger
import java.util.UUID
import kotlin.jvm.optionals.getOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

data class EvmClientConfig(
    val url: String,
    val privateKeyHex: String,
    val pollingIntervalInMs: Long,
    val maxPollingAttempts: Long,
    val defaultMaxPriorityFeePerGasInWei: BigInteger,
    val enableWeb3jLogging: Boolean,
    val maxRpcNodeEventualConsistencyTolerance: Duration,
)

class EvmServerException(
    message: String,
) : Exception(message)

class EvmClientException(
    message: String,
) : Exception(message)

open class TransactionManagerWithNonceOverride(
    web3j: Web3j,
    val credentials: Credentials,
    val chainId: Long,
    private val nonceOverride: BigInteger,
) : RawTransactionManager(web3j, credentials, chainId) {
    override fun getNonce(): BigInteger = nonceOverride
}

fun Credentials.checksumAddress(): EvmAddress = EvmAddress(Keys.toChecksumAddress(this.address))

sealed class DefaultBlockParam {
    data object Earliest : DefaultBlockParam()

    data object Latest : DefaultBlockParam()

    data object Safe : DefaultBlockParam()

    data object Finalized : DefaultBlockParam()

    data object Pending : DefaultBlockParam()

    data class BlockNumber(
        val value: BigInteger,
    ) : DefaultBlockParam()

    fun toWeb3j(): DefaultBlockParameter =
        when (this) {
            is Earliest -> DefaultBlockParameter.valueOf("earliest")
            is Latest -> DefaultBlockParameter.valueOf("latest")
            is Safe -> DefaultBlockParameter.valueOf("safe")
            is Finalized -> DefaultBlockParameter.valueOf("finalized")
            is Pending -> DefaultBlockParameter.valueOf("pending")
            is BlockNumber -> DefaultBlockParameter.valueOf(this.value)
        }

    override fun toString(): String =
        when (this) {
            is Earliest -> "earliest"
            is Latest -> "latest"
            is Safe -> "safe"
            is Finalized -> "finalized"
            is Pending -> "pending"
            is BlockNumber -> this.value.toString()
        }
}

enum class ContractType {
    Exchange,
    CoinProxy,
}

open class EvmClient(
    val config: EvmClientConfig,
) {
    protected val logger = KotlinLogging.logger {}
    protected val web3jService: HttpService =
        HttpService(
            config.url,
            HttpClient.newBuilder().withLogging(logger).build(),
        )
    protected val web3j: Web3j =
        Web3j.build(
            web3jService,
            config.pollingIntervalInMs,
            Async.defaultExecutorService(),
        )
    protected val credentials = Credentials.create(config.privateKeyHex)
    val chainId =
        web3j
            .ethChainId()
            .send()
            .chainId
            .toString()
    protected val receiptProcessor =
        PollingTransactionReceiptProcessor(
            web3j,
            config.pollingIntervalInMs,
            config.maxPollingAttempts.toInt(),
        )
    protected val transactionManager =
        RawTransactionManager(
            web3j,
            credentials,
            chainId.toLong(),
            receiptProcessor,
        )

    private val jsonRpcClient = EvmJsonRpcClient(config.url)

    val gasProvider =
        GasProvider(
            gasLimit = BigInteger.valueOf(100000),
            defaultMaxPriorityFeePerGas = config.defaultMaxPriorityFeePerGasInWei,
            chainId = chainId.toLong(),
            web3j = web3j,
        )
    private val contractMap = mutableMapOf<ContractType, Address>()

    data class DeployedContract(
        val proxyAddress: EvmAddress,
        val implementationAddress: EvmAddress,
        val version: Int,
    )

    private fun <A> exchangeContractCall(
        block: DefaultBlockParam,
        f: Exchange.() -> RemoteFunctionCall<A>,
    ): A {
        val contract = loadExchangeContract(exchangeContractAddress)
        contract.setDefaultBlockParameter(block.toWeb3j())
        val startTime = Clock.System.now()
        while (true) {
            try {
                return f(contract).send()
            } catch (e: ContractCallException) {
                val errorMessage = e.message ?: ""
                val badBlockNumberErrors =
                    setOf(
                        // returned by Anvil
                        "BlockOutOfRangeError",
                        // returned by Bitlayer and Sepolia
                        "header not found",
                    )
                if (
                    badBlockNumberErrors.none { errorMessage.contains(it) } ||
                    Clock.System.now() - startTime >= config.maxRpcNodeEventualConsistencyTolerance
                ) {
                    throw e
                }
            }
        }
    }

    private fun <A> coinProxyContractCall(
        block: DefaultBlockParam,
        f: CoinProxy.() -> RemoteFunctionCall<A>,
    ): A {
        val contract = loadCoinProxyContract(coinProxyContractAddress)
        contract.setDefaultBlockParameter(block.toWeb3j())
        val startTime = Clock.System.now()
        while (true) {
            try {
                return f(contract).send()
            } catch (e: ContractCallException) {
                val errorMessage = e.message ?: ""
                val badBlockNumberErrors =
                    setOf(
                        // returned by Anvil
                        "BlockOutOfRangeError",
                        // returned by Bitlayer and Sepolia
                        "header not found",
                    )
                if (
                    badBlockNumberErrors.none { errorMessage.contains(it) } ||
                    Clock.System.now() - startTime >= config.maxRpcNodeEventualConsistencyTolerance
                ) {
                    throw e
                }
            }
        }
    }

    fun getExchangeBalance(
        address: Address,
        tokenAddress: Address,
        block: DefaultBlockParam,
    ): BigInteger =
        exchangeContractCall(block) {
            balances(address.toString(), tokenAddress.toString())
        }

    fun getCoinProxyBalance(
        address: Address,
        tokenAddress: Address,
        block: DefaultBlockParam,
    ): BigInteger =
        coinProxyContractCall(block) {
            balances(address.toString(), tokenAddress.toString())
        }

    fun loadExchangeContract(address: Address): Exchange = Exchange.load(address.toString(), web3j, transactionManager, gasProvider)

    fun loadCoinProxyContract(address: Address): CoinProxy = CoinProxy.load(address.toString(), web3j, transactionManager, gasProvider)

    fun setContractAddress(
        contractType: ContractType,
        address: Address,
    ) {
        contractMap[contractType] = address
    }

    fun getContractAddress(contractType: ContractType): Address = contractMap.getValue(contractType)

    fun getTransactionReceipt(txHash: TxHash): TransactionReceipt? = getTransactionReceipt(txHash.toString())

    fun getTransactionReceipt(txHash: String): TransactionReceipt? {
        val receipt = web3j.ethGetTransactionReceipt(txHash).send().transactionReceipt
        return if (receipt.isPresent) {
            return receipt.get()
        } else {
            null
        }
    }

    fun gasUsed(txHash: TxHash): BigInteger? = jsonRpcClient.getGasUsed(txHash)

    fun getTransactionByHash(txHash: String): org.web3j.protocol.core.methods.response.Transaction? =
        web3j
            .ethGetTransactionByHash(txHash)
            .send()
            .transaction
            .getOrNull()

    open fun getBlock(
        blockNumber: BigInteger,
        withFullTxObjects: Boolean,
    ): EthBlock.Block = web3j.ethGetBlockByNumber(DefaultBlockParameter.valueOf(blockNumber), withFullTxObjects).send().block

    open fun getBlockNumber(): BigInteger = web3j.ethBlockNumber().send().blockNumber

    fun getLogs(
        block: DefaultBlockParam,
        address: Address,
    ): EthLog =
        web3j
            .ethGetLogs(EthFilter(block.toWeb3j(), block.toWeb3j(), address.toString()))
            .send()

    fun getExchangeContractLogs(block: BigInteger): EthLog =
        getLogs(
            DefaultBlockParam.BlockNumber(block),
            getContractAddress(
                ContractType.Exchange,
            ),
        )

    fun getTxManager(nonceOverride: BigInteger): TransactionManagerWithNonceOverride =
        TransactionManagerWithNonceOverride(web3j, credentials, chainId.toLong(), nonceOverride)

    fun getNonce(address: String): BigInteger =
        web3j.ethGetTransactionCount(address, DefaultBlockParameterName.PENDING).send().transactionCount

    fun getConsistentNonce(address: String): BigInteger {
        // this logic handles the fact that all RPC nodes may not be in sync, so we try to get a consistent nonce
        // by making multiple calls until we get a consistent value. Subsequently, we keep track of it ourselves.
        var isConsistent = false
        var candidateNonce: BigInteger? = null
        while (!isConsistent) {
            candidateNonce = getNonce(address)
            isConsistent = (1..2).map { getNonce(address) }.all { it == candidateNonce }
            if (!isConsistent) {
                logger.error { "Got inconsistent nonces, retrying" }
                Thread.sleep(100)
            }
        }
        return candidateNonce!!
    }

    fun loadERC20(address: EvmAddress) = ERC20.load(address.value, web3j, transactionManager, gasProvider)

    fun signData(
        hash: ByteArray,
        linkedSignerEcKeyPair: ECKeyPair? = null,
    ): EvmSignature {
        val signature = Sign.signMessage(hash, linkedSignerEcKeyPair ?: credentials.ecKeyPair, false)
        return (signature.r + signature.s + signature.v).toHex().toEvmSignature()
    }

    fun getBalance(
        walletAddress: EvmAddress,
        contractAddress: EvmAddress?,
    ): BigInteger = runBlocking { asyncGetBalance(walletAddress, contractAddress) }

    suspend fun asyncGetBalance(
        walletAddress: EvmAddress,
        contractAddress: EvmAddress?,
    ): BigInteger =
        (
            contractAddress
                ?.let { asyncGetERC20Balance(it, walletAddress) }
                ?: asyncGetNativeBalance(walletAddress)
        )

    fun getNativeBalance(address: EvmAddress): BigInteger =
        web3j.ethGetBalance(address.value, DefaultBlockParameter.valueOf("latest")).send().balance

    suspend fun asyncGetNativeBalance(address: EvmAddress): BigInteger =
        web3j
            .ethGetBalance(address.value, DefaultBlockParameter.valueOf("latest"))
            .sendAsync()
            .await()
            .balance

    fun getERC20Balance(
        erc20Address: EvmAddress,
        walletAddress: EvmAddress,
    ): BigInteger = runBlocking { asyncGetERC20Balance(erc20Address, walletAddress) }

    suspend fun asyncGetERC20Balance(
        erc20Address: EvmAddress,
        walletAddress: EvmAddress,
    ): BigInteger = loadERC20(erc20Address).balanceOf(walletAddress.value).sendAsync().await()

    fun sendTransaction(
        address: Address,
        data: String,
        amount: BigInteger,
        nonceOverride: BigInteger? = null,
    ): TxHash {
        val txManager =
            nonceOverride
                ?.let { TransactionManagerWithNonceOverride(web3j, credentials, chainId.toLong(), nonceOverride) }
                ?: transactionManager

        val gasPrice = web3j.ethGasPrice().send().gasPrice
        logger.debug { "gas price is $gasPrice" }
        return txManager
            .sendTransaction(
                gasPrice,
                gasProvider.gasLimit,
                address.toString(),
                data,
                amount,
            ).transactionHash
            .let { TxHash.auto(it) }
    }

    fun sendNativeDepositTx(
        address: Address,
        amount: BigInteger,
        nonceOverride: BigInteger? = null,
    ): TxHash =
        sendTransaction(
            address = address,
            data = "",
            amount = amount,
            nonceOverride = nonceOverride,
        )

    val exchangeContractAddress: Address
        get() = contractMap.getValue(ContractType.Exchange)

    val coinProxyContractAddress: Address
        get() = contractMap.getValue(ContractType.CoinProxy)

    fun waitForTransactionReceipt(txHash: TxHash): TransactionReceipt {
        var receipt: TransactionReceipt? = null

        await
            .withAlias("Waiting for tx $txHash receipt")
            .pollInSameThread()
            .pollDelay(100.milliseconds.toJavaDuration())
            .pollInterval(100.milliseconds.toJavaDuration())
            .atMost(30.seconds.toJavaDuration())
            .until {
                receipt = getTransactionReceipt(txHash)
                receipt != null
            }

        return receipt!!
    }
}

class EvmJsonRpcClient(
    url: String,
) : JsonRpcClientBase(
        url,
        KotlinLogging.logger {},
    ) {
    override val mediaType = "application/json".toMediaTypeOrNull()

    @Serializable
    data class RpcRequest(
        val method: String,
        val params: List<String>,
        val id: String = UUID.randomUUID().toString(),
        val jsonrpc: String = "2.0",
    )

    @Serializable
    data class GasReceipt(
        val gasUsed: String?,
        val effectiveGasPrice: String,
        val l1Fee: String?,
    )

    fun String.toBigInteger(): BigInteger = Numeric.decodeQuantity(this)

    fun getGasUsed(txHash: TxHash): BigInteger? {
        return try {
            val receipt: GasReceipt =
                getValue(
                    RpcRequest(
                        "eth_getTransactionReceipt",
                        listOf(txHash.toString()),
                    ),
                )
            return receipt.gasUsed?.let { gasUsed ->
                (gasUsed.toBigInteger() * receipt.effectiveGasPrice.toBigInteger()) +
                    (receipt.l1Fee?.toBigInteger() ?: BigInteger.ZERO)
            }
        } catch (e: JsonRpcException) {
            if (e.error.code != 404) {
                throw e
            }
            null
        }
    }

    inline fun <reified T> getValue(request: RpcRequest): T {
        val jsonElement = call(json.encodeToString(request))
        return json.decodeFromJsonElement(jsonElement)
    }
}
