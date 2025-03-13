package xyz.funkybit.integrationtests.utils

/*
class BitcoinWallet(
    val keyPair: WalletKeyPair.Bitcoin,
    val ordinalsKeyPair: WalletKeyPair.Bitcoin,
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

    private val chain = allChains.first { it.id.isBitcoin() }
    val walletAddress = keyPair.address()
    val ordinalsAddress = ordinalsKeyPair.address()

    val exchangeNativeDepositAddress = chain.contracts.first { it.name == ContractType.CoinProxy.name }.nativeDepositAddress as BitcoinAddress
    val exchangeTokenDepositAddress = chain.contracts.first { it.name == ContractType.CoinProxy.name }.tokenDepositAddress as BitcoinAddress
    val nativeSymbol = chain.symbols.first { it.contractAddress == null }
    val runeSymbols = chain.symbols.filter { it.contractAddress != null }.toMutableSet()
    private val runeUtils = RuneUtils(
        mempoolSpaceClient,
        maestroClient,
        utxoManager,
        keyPair.ecKey,
        walletAddress,
        ordinalsKeyPair.ecKey,
        ordinalsAddress,
    )

    fun getWalletNativeBalance(): BigInteger = mempoolSpaceClient.getBalance(walletAddress).toBigInteger()

    fun getRuneBalance(contractAddress: RuneIdAddress): BigInteger =
        maestroClient.getBalance(ordinalsAddress, contractAddress.value)

    override fun addRuneSymbol(rune: SymbolInfo) {
        runeSymbols.add(rune)
    }

    private fun isRuneSymbol(symbolName: String) =
        runeSymbols.firstOrNull { it.name == symbolName } != null

    fun authorize(evmApiClient: ApiClient) {
        apiClient.authorizeWallet(
            apiRequest = signAuthorizeBitcoinWalletRequest(
                ecKeyPair = evmApiClient.keyPair.asEcKeyPair(),
                address = evmApiClient.address as EvmAddress,
                authorizedAddress = walletAddress,
            ),
        )
    }

    fun setOrdinalsAddress() {
        apiClient.setOrdinalsAddress(
            apiRequest = signSetOrdinalsAddressRequest(
                paymentKeyPair = keyPair,
                ordinalsKeyPair = ordinalsKeyPair,
            ),
        )
    }

    fun depositNative(amount: BigInteger): DepositApiResponse = apiClient.createDeposit(
        CreateDepositApiRequest(
            symbol = Symbol(nativeSymbol.name),
            amount = amount,
            txHash = sendNativeDepositTx(amount),
        ),
    )

    fun depositRune(assetAmount: AssetAmount): DepositApiResponse = apiClient.createDeposit(
        CreateDepositApiRequest(
            symbol = Symbol(assetAmount.symbol.name),
            amount = assetAmount.inFundamentalUnits,
            txHash = sendRuneDepositTx(assetAmount),
        ),
    )

    fun getExchangeBalance(symbol: SymbolInfo): AssetAmount =
        AssetAmount(
            symbol,
            readExchangeBalance(symbol.name).fromFundamentalUnits(symbol.decimals),
        )

    private fun readExchangeBalance(symbol: String): BigInteger = transaction {
        val symbolEntity = SymbolEntity.forName(symbol)
        evmChainManager.coinProxyEvmClient.getCoinProxyBalance(
            walletAddress.toCoinProxyAddress(),
            symbolEntity.coinProxyAddress ?: EvmAddress.zero,
            DefaultBlockParam.Latest,
        )
    }

    private fun marketSymbols(marketId: MarketId): Pair<SymbolInfo, SymbolInfo> =
        marketId
            .baseAndQuoteSymbols()
            .let { (base, quote) ->
                Pair(
                    allChains.map { it.symbols.filter { s -> s.name == base } }.flatten().firstOrNull() ?: runeSymbols.first { r -> r.name == base },
                    allChains.map { it.symbols.filter { s -> s.name == quote } }.flatten().first(),
                )
            }

    fun sendNativeDepositTx(amount: BigInteger): TxHash {
        val unspentUtxos = mempoolSpaceClient.getUnspentUtxos(walletAddress).map { UnspentUtxo(BitcoinUtxoId.fromTxHashAndVout(it.txId, it.vout), it.value) }
        val selectedUtxos = utxoManager.selectUtxos(
            walletAddress,
            amount,
            mempoolSpaceClient.calculateFee(mempoolSpaceClient.estimateVSize(1, 2)),
            unspentUtxos = unspentUtxos,
        )

        val depositTx = bitcoinTransactionUtils.buildAndSignDepositTx(
            exchangeNativeDepositAddress,
            amount,
            selectedUtxos,
            keyPair.ecKey,
        )

        return mempoolSpaceClient.sendTransaction(depositTx.toHexString())
    }

    fun sendRuneDepositTx(assetAmount: AssetAmount): TxHash = runeUtils.transfer(
        (assetAmount.symbol.contractAddress as RuneIdAddress).toRuneId(),
        assetAmount.inFundamentalUnits,
        exchangeTokenDepositAddress,
    )

    fun transferRunes(assetAmount: AssetAmount, destinationAddress: BitcoinAddress): TxHash = runeUtils.transfer(
        (assetAmount.symbol.contractAddress as RuneIdAddress).toRuneId(),
        assetAmount.inFundamentalUnits,
        destinationAddress,
    )

    fun airdropNative(amount: BigInteger): TxHash = BitcoinClient.sendToAddress(
        walletAddress,
        amount,
    )

    fun signWithdraw(symbol: String, amount: BigInteger, decimals: UByte? = null): CreateWithdrawalApiRequest {
        val isRune = isRuneSymbol(symbol)
        val nonce = System.currentTimeMillis()
        val message = "[funkybit] Please sign this message to authorize withdrawal of ${if (amount == BigInteger.ZERO) "100% of" else amount.fromFundamentalUnits(decimals ?: 8u).toPlainString()} $symbol from the exchange to your wallet."
        val bitcoinLinkAddressMessage = "$message\nAddress: ${if (isRune) ordinalsAddress.value else walletAddress.value}, Timestamp: ${Instant.fromEpochMilliseconds(nonce)}"
        val signature = (if (isRune) ordinalsKeyPair.ecKey else keyPair.ecKey).signMessage(bitcoinLinkAddressMessage)
        return CreateWithdrawalApiRequest(
            Symbol(symbol),
            amount,
            nonce,
            Signature.auto(signature),
        )
    }

    override fun signRuneInitialOrder(
        runeInitialOrder: RuneInitialOrder,
        baseSymbolName: String,
        quoteChainId: Chain.Id,
        quoteToken: String,
        withSessionKey: Boolean,
    ) = runeInitialOrder.copy(
        signature = if (withSessionKey) {
            signRuneInitialOrderWithSessionKey(runeInitialOrder, quoteChainId, quoteToken)
        } else {
            signRuneInitialOrder(
                runeInitialOrder,
                baseSymbolName,
                BondingCurveUtils.quoteSymbolName,
            )
        },
    )

    override fun signOrder(request: CreateOrderApiRequest.Market, withSessionKey: Boolean): CreateOrderApiRequest.Market = request.copy(
        signature = if (withSessionKey) signOrderWithSessionKey(request) else sign(request),
    )

    override fun signOrder(request: CreateOrderApiRequest.Limit, linkedSignerKeyPair: WalletKeyPair?): CreateOrderApiRequest.Limit = request.copy(
        signature = sign(request),
    )

    override fun signOrder(request: CreateOrderApiRequest.BackToBackMarket): CreateOrderApiRequest.BackToBackMarket = request.copy(
        signature = sign(request),
    )

    override fun signCancelOrder(request: CancelOrderApiRequest, withSessionKey: Boolean): CancelOrderApiRequest = request.copy(
        signature = if (withSessionKey) signCancelOrderWithSessionKey(request) else sign(request),
    )

    private fun signWithSessionKey(hash: ByteArray): EvmSignature {
        val signature = Sign.signMessage(hash, apiClient.sessionKeyPair.asEcKeyPair(), false)
        return (signature.r + signature.s + signature.v).toHex().toEvmSignature()
    }

    val evmChains = allChains.filter { it.id.isEvm() }
    private val evmExchangeContractAddressByChainId = evmChains.associate { it.id to it.contracts.first { it.name == ContractType.Exchange.name }.address }

    private fun chainId(symbol: SymbolInfo) = allChains.first {
        it.symbols.contains(symbol)
    }.id

    private fun signOrderWithSessionKey(request: CreateOrderApiRequest.Market): EvmSignature {
        val (baseSymbol, quoteSymbol) = marketSymbols(request.marketId)

        val quoteChainId = if (quoteSymbol.name.endsWith(":bitcoin")) chain.id else chainId(quoteSymbol)

        val tx = EIP712Transaction.Order(
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

    private fun signRuneInitialOrderWithSessionKey(runeInitialOrder: RuneInitialOrder, quoteChainId: Chain.Id, quoteToken: String): EvmSignature {
        val tx = EIP712Transaction.Order(
            sender = apiClient.sessionKeyPair.address().toString(),
            baseChainId = Chain.Id.bitcoin,
            baseToken = "0:0",
            quoteChainId = quoteChainId,
            quoteToken = quoteToken,
            amount = runeInitialOrder.amount,
            price = BigInteger.ZERO,
            nonce = BigInteger(1, runeInitialOrder.nonce.toHexBytes()),
            signature = EvmSignature.emptySignature(),
        )
        val hashToSign = EIP712Helper.computeHash(tx, quoteChainId, evmExchangeContractAddressByChainId.getValue(quoteChainId))
        return signWithSessionKey(hashToSign)
    }

    private fun signCancelOrderWithSessionKey(request: CancelOrderApiRequest): EvmSignature {
        val (_, quoteSymbol) = marketSymbols(request.marketId)
        val quoteChainId = if (quoteSymbol.name.endsWith(":bitcoin")) chain.id else chainId(quoteSymbol)
        val tx = EIP712Transaction.CancelOrder(
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
        val swapMessage = when (request.amount) {
            is OrderAmount.Fixed -> {
                val amount = request.amount.fixedAmount().fromFundamentalUnits(baseSymbol.decimals).toPlainString()
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
        val bitcoinOrderMessage = "[funkybit] Please sign this message to authorize a swap. This action will not cost any gas fees." +
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

        val message = "[funkybit] Please sign this message to authorize order cancellation. This action will not cost any gas fees." +
            if (request.side == OrderSide.Buy) {
                "\nSwap ${quoteSymbol.name} for $baseAmount ${baseSymbol.name}"
            } else {
                "\nSwap $baseAmount ${baseSymbol.name} for ${quoteSymbol.name}"
            } + "\nAddress: ${bitcoinAddress.value}, Nonce: ${request.nonce}"
        logger.debug { "wallet - message to sign = [$message]" }
        return Signature.auto(keyPair.ecKey.signMessage(message))
    }

    private fun signRuneInitialOrder(runeInitialOrder: RuneInitialOrder, baseSymbolName: String, quoteSymbolName: String): Signature {
        val bitcoinAddress = BitcoinAddress.canonicalize(walletAddress.value)
        val amount = runeInitialOrder.amount.fixedAmount().fromFundamentalUnits(BondingCurveUtils.baseSymbolDecimals).toPlainString()
        val swapMessage = "Swap $quoteSymbolName for $amount $baseSymbolName"
        val bitcoinOrderMessage = "[funkybit] Please sign this message to authorize a swap. This action will not cost any gas fees." +
            "\n$swapMessage" +
            "\nPrice: Market" +
            "\nAddress: ${bitcoinAddress.value}, Nonce: ${runeInitialOrder.nonce}"
        return Signature.auto(keyPair.ecKey.signMessage(bitcoinOrderMessage))
    }
}
*/
