package xyz.funkybit.client

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.Clock
import org.web3j.crypto.Sign
import org.web3j.protocol.core.methods.response.TransactionReceipt
import xyz.funkybit.client.model.AssetAmount
import xyz.funkybit.client.model.CancelOrderApiRequest
import xyz.funkybit.client.model.Chain
import xyz.funkybit.client.model.ChainId
import xyz.funkybit.client.model.CreateDepositApiRequest
import xyz.funkybit.client.model.CreateOrderApiRequest
import xyz.funkybit.client.model.CreateWithdrawalApiRequest
import xyz.funkybit.client.model.EIP712Transaction
import xyz.funkybit.client.model.MarketId
import xyz.funkybit.client.model.OrderAmount
import xyz.funkybit.client.model.OrderSide
import xyz.funkybit.client.model.Symbol
import xyz.funkybit.client.model.SymbolInfo
import xyz.funkybit.client.model.TokenAddressAndChain
import xyz.funkybit.client.model.TxHash
import xyz.funkybit.client.model.address.Address
import xyz.funkybit.client.model.address.EvmAddress
import xyz.funkybit.client.model.signature.EvmSignature
import xyz.funkybit.client.model.signature.toEvmSignature
import xyz.funkybit.client.utils.EIP712Helper
import xyz.funkybit.client.utils.fromFundamentalUnits
import xyz.funkybit.client.utils.toFundamentalUnits
import xyz.funkybit.client.utils.toHex
import xyz.funkybit.client.utils.toHexBytes
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

sealed class OrderSigner(
    val address: Address,
) {
    abstract fun signOrder(
        request: CreateOrderApiRequest.Market,
        withSessionKey: Boolean = false,
    ): CreateOrderApiRequest.Market

    abstract fun signOrder(
        request: CreateOrderApiRequest.Limit,
        linkedSignerKeyPair: WalletKeyPair? = null,
    ): CreateOrderApiRequest.Limit

    abstract fun signCancelOrder(
        request: CancelOrderApiRequest,
        withSessionKey: Boolean = false,
    ): CancelOrderApiRequest
}

class Wallet(
    val keyPair: WalletKeyPair.EVM,
    val allChains: List<Chain>,
    val apiClient: FunkybitApiClient,
) : OrderSigner(keyPair.address()) {
    val logger = KotlinLogging.logger {}

    companion object {
        operator fun invoke(apiClient: FunkybitApiClient): Wallet {
            val config = apiClient.getConfiguration().chains
            return Wallet(apiClient.keyPair as WalletKeyPair.EVM, config, apiClient)
        }
    }

    val chains = allChains.filter { it.id.isEvm() }
    private val bitcoinChain = allChains.first { it.id.isBitcoin() }

    private val evmClients =
        chains.map {
            EvmClient(
                EvmClientConfig(
                    url = it.jsonRpcUrl,
                    privateKeyHex = keyPair.privateKey.toByteArray().toHex(),
                    defaultMaxPriorityFeePerGasInWei =
                        (
                            System.getenv(
                                "DEFAULT_MAX_PRIORITY_FEE_PER_GAS_WEI",
                            ) ?: "5000000000"
                        ).toBigInteger(),
                    enableWeb3jLogging = (System.getenv("ENABLE_WEB3J_LOGGING") ?: "true") == "true",
                    maxRpcNodeEventualConsistencyTolerance =
                        System.getenv("MAX_RPC_NODE_EVENTUAL_CONSISTENCE_TOLERANCE_MS")?.toLongOrNull()?.milliseconds ?: 1.minutes,
                    pollingIntervalInMs = 1000L,
                    maxPollingAttempts = 120L,
                ),
            )
        }

    private val evmClientsByChainId = evmClients.associateBy { Chain.Id(it.chainId) }

    var currentChainId: Chain.Id = Chain.Id(evmClients.first().chainId)

    val evmAddress = keyPair.address()

    private fun signWithSessionKey(hash: ByteArray): EvmSignature {
        val signature = Sign.signMessage(hash, apiClient.sessionKeyPair.asEcKeyPair(), false)
        return (signature.r + signature.s + signature.v).toHex().toEvmSignature()
    }

    private val exchangeContractAddressByChainId =
        chains.associate {
            it.id to
                it.contracts.first { it.name == ContractType.Exchange.name }.address
        }
    private val exchangeContractByChainId =
        evmClients.associate {
            val chainId = Chain.Id(it.chainId)
            chainId to it.loadExchangeContract(exchangeContractAddressByChainId.getValue(chainId))
        }

    fun switchChain(chainId: ChainId) {
        currentChainId = Chain.Id(chainId)
    }

    fun switchChain(chainId: Chain.Id) {
        currentChainId = chainId
    }

    fun currentEvmClient(): EvmClient = evmClientsByChainId.getValue(currentChainId)

    fun waitForTransactionReceipt(txHash: TxHash): TransactionReceipt = currentEvmClient().waitForTransactionReceipt(txHash)

    fun getWalletERC20Balance(symbol: Symbol): BigInteger = loadErc20Contract(symbol.value).balanceOf(evmAddress.value).send()

    fun getWalletERC20Balance(symbol: String): BigInteger = loadErc20Contract(symbol).balanceOf(evmAddress.value).send()

    fun getWalletNativeBalance(): BigInteger = evmClientsByChainId.getValue(currentChainId).getNativeBalance(evmAddress)

    fun getWalletBalance(symbol: SymbolInfo): AssetAmount =
        AssetAmount(
            symbol,
            if (symbol.contractAddress == null) {
                getWalletNativeBalance()
            } else {
                getWalletERC20Balance(symbol.name)
            }.fromFundamentalUnits(symbol.decimals),
        )

    fun getExchangeBalance(symbol: SymbolInfo): AssetAmount =
        AssetAmount(
            symbol,
            if (symbol.contractAddress == null) {
                getExchangeNativeBalance(symbol.name)
            } else {
                getExchangeERC20Balance(symbol.name)
            }.fromFundamentalUnits(symbol.decimals),
        )

    fun getExchangeERC20Balance(symbol: String): BigInteger {
        val chainId = chains.first { c -> c.symbols.any { it.name == symbol } }.id
        return exchangeContractByChainId.getValue(chainId).balances(evmAddress.value, erc20TokenAddress(symbol, chainId)).send()
    }

    fun getExchangeNativeBalance(): BigInteger =
        exchangeContractByChainId.getValue(currentChainId).balances(evmAddress.value, EvmAddress.zero.value).send()

    private fun getExchangeNativeBalance(symbol: String): BigInteger {
        val chainId = chains.first { c -> c.symbols.any { it.name == symbol } }.id
        return exchangeContractByChainId.getValue(chainId).balances(evmAddress.value, EvmAddress.zero.value).send()
    }

    fun deposit(assetAmount: AssetAmount): TransactionReceipt {
        val txHash = sendDepositTx(assetAmount)
        apiClient.createDeposit(
            CreateDepositApiRequest(
                symbol = Symbol(assetAmount.symbol.name),
                amount = assetAmount.inFundamentalUnits,
                txHash = txHash,
            ),
        )
        return waitForTransactionReceipt(txHash)
    }

    fun sendDepositTx(assetAmount: AssetAmount): TxHash =
        if (assetAmount.symbol.contractAddress == null) {
            sendNativeDepositTx(assetAmount.inFundamentalUnits)
        } else {
            sendERC20DepositTx(assetAmount.symbol.name, assetAmount.inFundamentalUnits)
        }

    fun sendNativeDepositTx(amount: BigInteger): TxHash =
        evmClientsByChainId.getValue(currentChainId).sendNativeDepositTx(exchangeContractAddressByChainId.getValue(currentChainId), amount)

    fun sendERC20DepositTx(
        symbol: String,
        amount: BigInteger,
    ): TxHash {
        val erc20Contract = loadErc20Contract(symbol)

        evmClientsByChainId.getValue(currentChainId).sendTransaction(
            EvmAddress(erc20Contract.contractAddress),
            erc20Contract.approve(exchangeContractAddressByChainId.getValue(currentChainId).toString(), amount).encodeFunctionCall(),
            BigInteger.ZERO,
        )

        // when talking to an RPC node pool, we might have to try this a few times to get the proper nonce
        // try for up to a minute
        val start = Clock.System.now()
        while (Clock.System.now().minus(start) < Duration.parse("1m")) {
            try {
                return evmClientsByChainId.getValue(currentChainId).sendTransaction(
                    EvmAddress(exchangeContractByChainId.getValue(currentChainId).contractAddress),
                    exchangeContractByChainId
                        .getValue(currentChainId)
                        .deposit(erc20TokenAddress(symbol)?.value, amount)
                        .encodeFunctionCall(),
                    BigInteger.ZERO,
                )
            } catch (e: Exception) {
                logger.warn(e) { "ERC-20 deposit transaction failed, retrying" }
            }
        }
        throw RuntimeException("Unable to complete ERC-20 deposit")
    }

    fun withdraw(amount: AssetAmount) =
        apiClient.createWithdrawal(
            this.signWithdraw(
                amount.symbol.name,
                amount.inFundamentalUnits,
            ),
        )

    fun signWithdraw(
        symbol: String,
        amount: BigInteger,
        nonceOverride: Long? = null,
        linkedSignerKeyPair: WalletKeyPair? = null,
    ): CreateWithdrawalApiRequest {
        val nonce = nonceOverride ?: getWithdrawalNonce()
        val tx =
            EIP712Transaction.WithdrawTx(
                evmAddress,
                TokenAddressAndChain(erc20TokenAddress(symbol) ?: EvmAddress.zero, this.currentChainId),
                amount,
                nonce,
                amount == BigInteger.ZERO,
                EvmSignature.emptySignature(),
            )
        return CreateWithdrawalApiRequest(
            Symbol(symbol),
            amount,
            nonce,
            evmClientsByChainId.getValue(currentChainId).signData(
                EIP712Helper.computeHash(tx, this.currentChainId, exchangeContractAddressByChainId.getValue(currentChainId)),
                (linkedSignerKeyPair as? WalletKeyPair.EVM)?.ecKeyPair,
            ),
        )
    }

    override fun signOrder(
        request: CreateOrderApiRequest.Limit,
        linkedSignerKeyPair: WalletKeyPair?,
    ): CreateOrderApiRequest.Limit =
        request.copy(
            signature =
                limitOrderEip712TxSignature(
                    request.marketId,
                    request.amount,
                    request.price,
                    request.side,
                    request.nonce,
                    linkedSignerKeyPair,
                ),
            verifyingChainId = this.currentChainId,
        )

    private fun chainId(symbol: SymbolInfo) =
        allChains
            .first {
                it.symbols.contains(symbol)
            }.id

    override fun signOrder(
        request: CreateOrderApiRequest.Market,
        withSessionKey: Boolean,
    ): CreateOrderApiRequest.Market {
        val (baseSymbol, quoteSymbol) = marketSymbols(request.marketId)

        val tx =
            EIP712Transaction.Order(
                sender = if (withSessionKey) apiClient.sessionKeyPair.address().toString() else evmAddress.toString(),
                baseChainId = if (baseSymbol.name.endsWith(":bitcoin")) bitcoinChain.id else chainId(baseSymbol),
                baseToken = (baseSymbol.contractAddress ?: EvmAddress.zero).toString(),
                quoteChainId = chainId(quoteSymbol),
                quoteToken = (quoteSymbol.contractAddress ?: EvmAddress.zero).toString(),
                amount = if (request.side == OrderSide.Buy) request.amount else request.amount.negate(),
                price = BigInteger.ZERO,
                nonce = BigInteger(1, request.nonce.toHexBytes()),
                signature = EvmSignature.emptySignature(),
            )
        val hashToSign = EIP712Helper.computeHash(tx, this.currentChainId, exchangeContractAddressByChainId.getValue(currentChainId))
        return request.copy(
            signature =
                if (withSessionKey) {
                    signWithSessionKey(hashToSign)
                } else {
                    evmClientsByChainId.getValue(currentChainId).signData(
                        hashToSign,
                    )
                },
            verifyingChainId = this.currentChainId,
        )
    }

    override fun signCancelOrder(
        request: CancelOrderApiRequest,
        withSessionKey: Boolean,
    ): CancelOrderApiRequest {
        val tx =
            EIP712Transaction.CancelOrder(
                sender = if (withSessionKey) apiClient.sessionKeyPair.address().toString() else evmAddress.toString(),
                marketId = request.marketId,
                amount = if (request.side == OrderSide.Buy) request.amount else request.amount.negate(),
                nonce = BigInteger(1, request.nonce.toHexBytes()),
                signature = EvmSignature.emptySignature(),
            )
        val hashToSign = EIP712Helper.computeHash(tx, this.currentChainId, exchangeContractAddressByChainId.getValue(currentChainId))
        return request.copy(
            signature =
                if (withSessionKey) {
                    signWithSessionKey(
                        hashToSign,
                    )
                } else {
                    evmClientsByChainId.getValue(currentChainId).signData(hashToSign)
                },
            verifyingChainId = this.currentChainId,
        )
    }

    private fun limitOrderEip712TxSignature(
        marketId: MarketId,
        amount: OrderAmount,
        price: BigDecimal,
        side: OrderSide,
        nonce: String,
        linkedSignerKeyPair: WalletKeyPair? = null,
    ): EvmSignature {
        val (baseSymbol, quoteSymbol) = marketSymbols(marketId)
        val tx =
            EIP712Transaction.Order(
                evmAddress.toString(),
                baseChainId = chainId(baseSymbol),
                baseToken = (baseSymbol.contractAddress ?: EvmAddress.zero).toString(),
                quoteChainId = chainId(quoteSymbol),
                quoteToken = (quoteSymbol.contractAddress ?: EvmAddress.zero).toString(),
                amount = if (side == OrderSide.Buy) amount else amount.negate(),
                price = price.toFundamentalUnits(quoteSymbol.decimals),
                nonce = BigInteger(1, nonce.toHexBytes()),
                signature = EvmSignature.emptySignature(),
            )
        return evmClientsByChainId.getValue(currentChainId).signData(
            EIP712Helper.computeHash(tx, this.currentChainId, exchangeContractAddressByChainId.getValue(currentChainId)),
            (linkedSignerKeyPair as? WalletKeyPair.EVM)?.ecKeyPair,
        )
    }

    private fun getWithdrawalNonce(): Long = System.currentTimeMillis()

    private fun loadErc20Contract(symbol: String) = evmClientsByChainId.getValue(currentChainId).loadERC20(erc20TokenAddress(symbol)!!)

    private fun erc20TokenAddress(symbol: String): EvmAddress? =
        chains
            .first { it.id == currentChainId }
            .symbols
            .firstOrNull {
                (it.name == symbol || it.name == "$symbol:$currentChainId") &&
                    it.contractAddress != null
            }?.contractAddress as? EvmAddress

    private fun erc20TokenAddress(
        symbol: String,
        chainId: Chain.Id,
    ): String? =
        chains
            .first { it.id == chainId }
            .symbols
            .firstOrNull {
                (it.name == symbol || it.name == "$symbol:$currentChainId") &&
                    it.contractAddress != null
            }?.contractAddress
            ?.toString()

    private fun marketSymbols(marketId: MarketId): Pair<SymbolInfo, SymbolInfo> =
        marketId
            .baseAndQuoteSymbols()
            .let { (base, quote) ->
                Pair(
                    allChains.map { it.symbols.filter { s -> s.name == base } }.flatten().first(),
                    allChains.map { it.symbols.filter { s -> s.name == quote } }.flatten().first(),
                )
            }
}
