package xyz.funkybit.client

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.web3j.crypto.Credentials
import org.web3j.crypto.ECKeyPair
import org.web3j.crypto.Sign
import xyz.funkybit.client.model.AuthorizeWalletAddressMessage
import xyz.funkybit.client.model.AuthorizeWalletApiRequest
import xyz.funkybit.client.model.BitcoinUtxoId
import xyz.funkybit.client.model.CancelOrderApiRequest
import xyz.funkybit.client.model.Chain
import xyz.funkybit.client.model.ChainId.Companion.BITCOIN
import xyz.funkybit.client.model.CreateDepositApiRequest
import xyz.funkybit.client.model.CreateOrderApiRequest
import xyz.funkybit.client.model.CreateWithdrawalApiRequest
import xyz.funkybit.client.model.DepositApiResponse
import xyz.funkybit.client.model.EIP712Transaction
import xyz.funkybit.client.model.MarketId
import xyz.funkybit.client.model.OrderAmount
import xyz.funkybit.client.model.OrderSide
import xyz.funkybit.client.model.SymbolInfo
import xyz.funkybit.client.model.TxHash
import xyz.funkybit.client.model.UnspentUtxo
import xyz.funkybit.client.model.address.BitcoinAddress
import xyz.funkybit.client.model.address.EvmAddress
import xyz.funkybit.client.model.baseAndQuoteSymbols
import xyz.funkybit.client.model.signature.EvmSignature
import xyz.funkybit.client.model.signature.Signature
import xyz.funkybit.client.model.signature.toEvmSignature
import xyz.funkybit.client.utils.BitcoinInputsSelector
import xyz.funkybit.client.utils.BitcoinTransactionUtils
import xyz.funkybit.client.utils.ECHelper
import xyz.funkybit.client.utils.EIP712Helper
import xyz.funkybit.client.utils.SelectionStrategy
import xyz.funkybit.client.utils.fromFundamentalUnits
import xyz.funkybit.client.utils.toHex
import xyz.funkybit.client.utils.toHexBytes
import java.math.BigInteger

val mempoolSpaceClient: MempoolSpaceClient = MempoolSpaceClient()
val bitcoinTransactionUtils = BitcoinTransactionUtils(mempoolSpaceClient)

class BitcoinWallet(
    val keyPair: WalletKeyPair.Bitcoin,
    val allChains: List<Chain>,
    val apiClient: FunkybitApiClient,
) : OrderSigner(keyPair.address()) {
    val logger = KotlinLogging.logger {}

    companion object {
        operator fun invoke(apiClient: FunkybitApiClient): BitcoinWallet {
            val config = apiClient.getConfiguration().chains
            return BitcoinWallet(apiClient.keyPair as WalletKeyPair.Bitcoin, config, apiClient)
        }
    }

    private val chain = allChains.first { it.id == BITCOIN }
    val walletAddress = keyPair.address()

    val exchangeNativeDepositAddress =
        chain.contracts
            .first {
                it.name == ContractType.CoinProxy.name
            }.nativeDepositAddress as BitcoinAddress
    val exchangeTokenDepositAddress = chain.contracts.first { it.name == ContractType.CoinProxy.name }.tokenDepositAddress as BitcoinAddress
    val nativeSymbol = chain.symbols.first { it.contractAddress == null }
    val runeSymbols = chain.symbols.filter { it.contractAddress != null }.toMutableSet()

    fun getWalletNativeBalance(): BigInteger = mempoolSpaceClient.getBalance(walletAddress).toBigInteger()

    fun authorize(evmApiClient: FunkybitApiClient) {
        apiClient.authorizeWallet(
            apiRequest =
                signAuthorizeBitcoinWalletRequest(
                    ecKeyPair = evmApiClient.keyPair.asEcKeyPair(),
                    address = evmApiClient.address as EvmAddress,
                    authorizedAddress = walletAddress,
                ),
        )
    }

    fun depositNative(amount: BigInteger): DepositApiResponse =
        apiClient.createDeposit(
            CreateDepositApiRequest(
                symbol = nativeSymbol.name,
                amount = amount,
                txHash = sendNativeDepositTx(amount),
            ),
        )

    private fun marketSymbols(marketId: MarketId): Pair<SymbolInfo, SymbolInfo> =
        baseAndQuoteSymbols(marketId)
            .let { (base, quote) ->
                Pair(
                    allChains.map { it.symbols.filter { s -> s.name == base } }.flatten().first(),
                    allChains.map { it.symbols.filter { s -> s.name == quote } }.flatten().first(),
                )
            }

    private val inputsSelector = BitcoinInputsSelector()

    fun sendNativeDepositTx(amount: BigInteger): TxHash {
        val unspentUtxos =
            mempoolSpaceClient.getUnspentUtxos(walletAddress).map {
                UnspentUtxo(BitcoinUtxoId.fromTxHashAndVout(it.txId, it.vout), it.value)
            }
        val selectedUtxos =
            inputsSelector.selectInputs(
                amount,
                unspentUtxos,
                mempoolSpaceClient.calculateFee(mempoolSpaceClient.estimateVSize(1, 2)),
                SelectionStrategy.RandomDraw,
            )

        val depositTx =
            bitcoinTransactionUtils.buildAndSignDepositTx(
                exchangeNativeDepositAddress,
                amount,
                selectedUtxos,
                keyPair.ecKey,
            )

        return mempoolSpaceClient.sendTransaction(depositTx.toHexString())
    }

    fun signWithdraw(
        symbol: SymbolInfo,
        amount: BigInteger,
    ): CreateWithdrawalApiRequest {
        val nonce = System.currentTimeMillis()
        val message = "[funkybit] Please sign this message to authorize withdrawal of ${if (amount == BigInteger.ZERO) {
            "100% of"
        } else {
            amount
                .fromFundamentalUnits(
                    symbol.decimals.toUByte(),
                ).toPlainString()
        }} ${symbol.name} from the exchange to your wallet."
        val bitcoinLinkAddressMessage = "$message\nAddress: ${walletAddress.value}, Timestamp: ${Instant.fromEpochMilliseconds(nonce)}"
        val signature = keyPair.ecKey.signMessage(bitcoinLinkAddressMessage)
        return CreateWithdrawalApiRequest(
            symbol.name,
            amount,
            nonce,
            Signature.auto(signature),
        )
    }

    override fun signOrder(
        request: CreateOrderApiRequest.Market,
        withSessionKey: Boolean,
    ): CreateOrderApiRequest.Market =
        request.copy(
            signature = if (withSessionKey) signOrderWithSessionKey(request) else sign(request),
        )

    override fun signOrder(
        request: CreateOrderApiRequest.Limit,
        linkedSignerKeyPair: WalletKeyPair?,
    ): CreateOrderApiRequest.Limit =
        request.copy(
            signature = sign(request),
        )

    override fun signCancelOrder(
        request: CancelOrderApiRequest,
        withSessionKey: Boolean,
    ): CancelOrderApiRequest =
        request.copy(
            signature = if (withSessionKey) signCancelOrderWithSessionKey(request) else sign(request),
        )

    private fun signWithSessionKey(hash: ByteArray): EvmSignature {
        val signature = Sign.signMessage(hash, apiClient.sessionKeyPair.asEcKeyPair(), false)
        return (signature.r + signature.s + signature.v).toHex().toEvmSignature()
    }

    val evmChains = allChains.filter { it.id != BITCOIN }
    private val evmExchangeContractAddressByChainId =
        evmChains.associate {
            it.id to
                it.contracts.first { it.name == ContractType.Exchange.name }.address
        }

    private fun chainId(symbol: SymbolInfo) =
        allChains
            .first {
                it.symbols.contains(symbol)
            }.id

    private fun signOrderWithSessionKey(request: CreateOrderApiRequest.Market): EvmSignature {
        val (baseSymbol, quoteSymbol) = marketSymbols(request.marketId)

        val quoteChainId = if (quoteSymbol.name.endsWith(":bitcoin")) chain.id else chainId(quoteSymbol)

        val tx =
            EIP712Transaction.Order(
                sender = apiClient.sessionKeyPair.address().toString(),
                baseChainId = if (baseSymbol.name.endsWith(":bitcoin")) chain.id else chainId(baseSymbol),
                baseToken = (baseSymbol.contractAddress ?: EvmAddress.zero).toString(),
                quoteChainId = quoteChainId,
                quoteToken = (quoteSymbol.contractAddress ?: EvmAddress.zero).toString(),
                amount = if (request.side == OrderSide.Buy) request.amount else request.amount.negate(),
                price = BigInteger.ZERO,
                nonce = BigInteger(1, request.nonce.toHexBytes()),
                signature = EvmSignature.emptySignature(),
            )
        val hashToSign = EIP712Helper.computeHash(tx, quoteChainId, evmExchangeContractAddressByChainId.getValue(quoteChainId))
        return signWithSessionKey(hashToSign)
    }

    private fun signCancelOrderWithSessionKey(request: CancelOrderApiRequest): EvmSignature {
        val (_, quoteSymbol) = marketSymbols(request.marketId)
        val quoteChainId = if (quoteSymbol.name.endsWith(":bitcoin")) chain.id else chainId(quoteSymbol)
        val tx =
            EIP712Transaction.CancelOrder(
                sender = apiClient.sessionKeyPair.address().toString(),
                marketId = request.marketId,
                amount = if (request.side == OrderSide.Buy) request.amount else request.amount.negate(),
                nonce = BigInteger(1, request.nonce.toHexBytes()),
                signature = EvmSignature.emptySignature(),
            )
        val hashToSign = EIP712Helper.computeHash(tx, quoteChainId, evmExchangeContractAddressByChainId.getValue(quoteChainId))
        return signWithSessionKey(hashToSign)
    }

    private fun sign(request: CreateOrderApiRequest): Signature {
        val (baseSymbol, quoteSymbol) = marketSymbols(request.marketId)
        val baseSymbolName = baseSymbol.name
        val quoteSymbolName = quoteSymbol.name

        val bitcoinAddress = BitcoinAddress.canonicalize(walletAddress.value)
        val swapMessage =
            when (request.amount) {
                is OrderAmount.Fixed -> {
                    val amount =
                        request.amount
                            .fixedAmount()
                            .fromFundamentalUnits(baseSymbol.decimals)
                            .toPlainString()
                    when (request.side) {
                        OrderSide.Buy -> "Swap $quoteSymbolName for $amount $baseSymbolName"
                        OrderSide.Sell -> "Swap $amount $baseSymbolName for $quoteSymbolName"
                    }
                }
                is OrderAmount.Percent -> {
                    val percent = "${request.amount.percentage()}% of your"
                    when (request.side) {
                        OrderSide.Buy -> "Swap $percent $quoteSymbolName for $baseSymbolName"
                        OrderSide.Sell -> "Swap $percent $baseSymbolName for $quoteSymbolName"
                    }
                }
            }
        val bitcoinOrderMessage =
            "[funkybit] Please sign this message to authorize a swap. This action will not cost any gas fees." +
                "\n$swapMessage" +
                when (request) {
                    is CreateOrderApiRequest.Limit -> "\nPrice: ${request.price.toPlainString()}"
                    else -> "\nPrice: Market"
                } + "\nAddress: ${bitcoinAddress.value}, Nonce: ${request.nonce}"
        logger.debug { "wallet - message to sign = [$bitcoinOrderMessage]" }
        return Signature.auto(keyPair.ecKey.signMessage(bitcoinOrderMessage))
    }

    private fun sign(request: CancelOrderApiRequest): Signature {
        val (baseSymbol, quoteSymbol) = marketSymbols(request.marketId)
        val baseAmount = request.amount.fromFundamentalUnits(baseSymbol.decimals).toPlainString()
        val bitcoinAddress = BitcoinAddress.canonicalize(walletAddress.value)

        val message =
            "[funkybit] Please sign this message to authorize order cancellation. This action will not cost any gas fees." +
                if (request.side == OrderSide.Buy) {
                    "\nSwap ${quoteSymbol.name} for $baseAmount ${baseSymbol.name}"
                } else {
                    "\nSwap $baseAmount ${baseSymbol.name} for ${quoteSymbol.name}"
                } + "\nAddress: ${bitcoinAddress.value}, Nonce: ${request.nonce}"
        logger.debug { "wallet - message to sign = [$message]" }
        return Signature.auto(keyPair.ecKey.signMessage(message))
    }

    fun signAuthorizeBitcoinWalletRequest(
        ecKeyPair: ECKeyPair,
        address: EvmAddress,
        authorizedAddress: BitcoinAddress,
        chainId: String = "1337",
        timestamp: Instant = Clock.System.now(),
    ): AuthorizeWalletApiRequest {
        val message =
            "[funkybit] Please sign this message to authorize Bitcoin wallet ${authorizedAddress.value}." +
                " This action will not cost any gas fees."
        val signature: EvmSignature =
            ECHelper.signData(
                Credentials.create(ecKeyPair),
                EIP712Helper.computeHash(
                    AuthorizeWalletAddressMessage(
                        message = message,
                        address = address.toString(),
                        authorizedAddress = authorizedAddress.toString(),
                        chainId = chainId,
                        timestamp = timestamp.toString(),
                    ),
                ),
            )

        return AuthorizeWalletApiRequest(
            authorizedAddress = authorizedAddress,
            address = address,
            chainId = chainId,
            timestamp = timestamp.toString(),
            signature = signature.value,
        )
    }
}
