package xyz.funkybit.client

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.bitcoinj.core.Coin
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionInput
import org.bitcoinj.core.TransactionOutPoint
import org.bitcoinj.core.TransactionOutput
import org.bitcoinj.core.Utils
import org.bitcoinj.script.Script
import org.bitcoinj.script.ScriptBuilder
import org.bitcoinj.script.ScriptOpCodes
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.jce.ECNamedCurveTable
import org.http4k.base64Encode
import org.web3j.crypto.Credentials
import org.web3j.crypto.ECKeyPair
import org.web3j.crypto.Sign
import xyz.funkybit.client.model.AuthorizeWalletAddressMessage
import xyz.funkybit.client.model.AuthorizeWalletApiRequest
import xyz.funkybit.client.model.BitcoinUtxoId
import xyz.funkybit.client.model.CancelOrderApiRequest
import xyz.funkybit.client.model.Chain
import xyz.funkybit.client.model.ChainId
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
import xyz.funkybit.client.utils.TaprootUtils
import xyz.funkybit.client.utils.doubleSha256
import xyz.funkybit.client.utils.fromFundamentalUnits
import xyz.funkybit.client.utils.sha256
import xyz.funkybit.client.utils.toHex
import xyz.funkybit.client.utils.toHexBytes
import xyz.funkybit.core.utils.schnorr.Schnorr
import java.math.BigInteger
import java.nio.ByteBuffer

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

    private val params = bitcoinConfig.params

    private val zero4 = ByteArray(4)
    private val zero8 = ByteArray(8)

    @OptIn(ExperimentalStdlibApi::class)
    fun generateMessageHashSegWit(
        address: BitcoinAddress,
        message: ByteArray,
        pubkey: String,
    ): ByteArray {
        val script = address.script()
        val txToSign = getVirtualTx(message, script.toHexBytes())
        val scriptCode = generateSingleSigScript(pubkey, address)
        // witness msg prefix for txSign:
        // ...versionByte -- 4 byte - 0
        // ...prevHash - sha256(sha256(reversed(txToSend.txHash) + 4 bytes(0))
        // ...sequenceHash - sha256(sha256(4 bytes(0)))
        val witnessMsgPrefix =
            zero4 +
                doubleSha256(
                    txToSign.inputs[0]
                        .outpoint.hash.reversedBytes + zero4,
                ) +
                doubleSha256(zero4)
        // witness msg suffix for txSign:
        // ...outputHash - sha256(sha256(8 bytes(0) + txToSign.scriptPubKey))
        // ...lockTimeByte -- 4 bytes = 0
        val outputScript = txToSign.outputs[0].scriptPubKey.program
        val witnessMsgSuffix =
            doubleSha256(zero8 + getVarInt(outputScript.size.toLong()).toHexBytes() + outputScript) +
                zero4
        return doubleSha256(
            witnessMsgPrefix +
                // outpoint
                txToSign.inputs[0]
                    .outpoint.hash.reversedBytes + zero4 +
                // script code
                getVarInt(scriptCode.size.toLong()).toHexBytes() + scriptCode +
                // value
                zero8 +
                // sequence
                zero4 +
                witnessMsgSuffix +
                // sig hash
                "01000000".hexToByteArray(),
        )
    }

    fun generateMessageHashTaproot(
        address: BitcoinAddress,
        message: ByteArray,
        sigHash: Transaction.SigHash = Transaction.SigHash.UNSET,
    ): ByteArray {
        val script = address.script()
        val txToSign = getVirtualTx(message, script.toHexBytes())
        val txToSend = txToSign.inputs[0]
        val outputScript = txToSign.outputs[0].scriptPubKey.program
        val sigMsg =
            // hashType
            byteArrayOf(sigHash.byteValue()) +
                // transaction
                // version
                zero4 +
                // locktime
                zero4 +
                // prevoutHash
                sha256(
                    txToSign.inputs[0]
                        .outpoint.hash.reversedBytes + zero4,
                ) +
                // amountHash
                sha256(zero8) +
                // scriptPubKeyHash
                sha256(getVarInt(txToSend.scriptBytes.size.toLong()).toHexBytes() + txToSend.scriptBytes) +
                // sequenceHash
                sha256(zero4) +
                // outputHash
                sha256(zero8 + getVarInt(outputScript.size.toLong()).toHexBytes() + outputScript) +
                // inputs
                // spend type
                ByteArray(1) +
                // input idx
                zero4

        return sha256(
            getTapTag("TapSighash".toByteArray()) + byteArrayOf(0) + sigMsg,
        )
    }

    @OptIn(ExperimentalStdlibApi::class)
    fun generateSingleSigScript(
        pubkey: String,
        address: BitcoinAddress,
    ): ByteArray {
        return when (address) {
            is BitcoinAddress.Taproot -> {
                val pubkeyBytes = Utils.HEX.decode(pubkey)
                val script =
                    ScriptBuilder()
                        .data(pubkeyBytes)
                        .op(ScriptOpCodes.OP_CHECKSIG)
                        .build()
                script.program
            }

            else -> {
                val pubkeyHash = Utils.sha256hash160(Utils.HEX.decode(pubkey))
                return (
                    ScriptOpCodes.OP_DUP.toString(16) +
                        ScriptOpCodes.OP_HASH160.toString(16) +
                        opPushData(pubkeyHash.toHexString()) +
                        ScriptOpCodes.OP_EQUALVERIFY.toString(16) +
                        ScriptOpCodes.OP_CHECKSIG.toString(16)
                ).toHexBytes()
            }
        }
    }

    fun getVarInt(num: Long): String =
        when {
            num <= 252 -> padZeroHexN(num.toString(16), 2)
            num <= 65535 -> "fd" + reverseHex(padZeroHexN(num.toString(16), 4))
            num <= 4294967295 -> "fe" + reverseHex(padZeroHexN(num.toString(16), 8))
            else -> "ff" + reverseHex(padZeroHexN(num.toString(16), 16))
        }

    fun padZeroHexN(
        hex: String,
        length: Int,
    ): String = hex.padStart(length, '0')

    fun reverseHex(hex: String): String = hex.chunked(2).reversed().joinToString("")

    fun opPushData(data: String): String {
        val length = data.length / 2 // Hex string length is twice the byte length
        return when {
            length < 0x4c -> {
                // length byte only
                String.format("%02x", length)
            }

            length <= 0xff -> {
                // OP_PUSHDATA1 format
                String.format("%02x%02x", ScriptOpCodes.OP_PUSHDATA1, length)
            }

            length <= 0xffff -> {
                // OP_PUSHDATA2 format
                String.format("%02x%04x", ScriptOpCodes.OP_PUSHDATA2, length)
            }

            else -> {
                // OP_PUSHDATA4 format
                String.format("%02x%08x", ScriptOpCodes.OP_PUSHDATA4, length)
            }
        } + data
    }

    @OptIn(ExperimentalStdlibApi::class)
    fun getVirtualTx(
        messageBytes: ByteArray,
        script: ByteArray,
    ): Transaction {
        // Build transaction to spend
        val txToSpend = Transaction(params)
        txToSpend.setVersion(0)

        // Add input to txToSpend
        val dummyTxHash = Sha256Hash.ZERO_HASH
        val input =
            TransactionInput(
                params,
                txToSpend,
                byteArrayOf(),
                TransactionOutPoint(params, 0xffffffffL, dummyTxHash),
            )
        input.sequenceNumber = 0x00000000L // Sequence number

        // Add output to txToSpend
        val outputScript = Script(script)
        val output = TransactionOutput(params, txToSpend, Coin.ZERO, outputScript.program)
        txToSpend.addOutput(output)

        // Build the message hash
        val bip0322Tag = "BIP0322-signed-message".toByteArray()
        val msgHash =
            sha256(
                getTapTag(bip0322Tag) + messageBytes,
            ).toHexString()

        // Sign the input
        val scriptSig = ("00" + opPushData(msgHash)).toHexBytes()
        input.scriptSig = Script(scriptSig)

        txToSpend.addInput(input)

        // Build transaction to sign
        val txToSign = Transaction(params)
        txToSign.setVersion(0)

        // Add input to txToSign
        val inputToSign =
            TransactionInput(
                params,
                txToSign,
                script,
                TransactionOutPoint(params, 0L, txToSpend.txId),
            )
        inputToSign.sequenceNumber = 0x00000000L

        txToSign.addInput(inputToSign)

        // Add OP_RETURN output to txToSign
        val opReturnScript = ScriptOpCodes.OP_RETURN
        val opReturnOutput = TransactionOutput(params, txToSign, Coin.ZERO, byteArrayOf(opReturnScript.toByte()))
        txToSign.addOutput(opReturnOutput)

        return txToSign
    }

    fun getTapTag(tag: ByteArray): ByteArray {
        val hashed = sha256(tag)
        return hashed + hashed
    }

    fun signPrehashedMessage(
        privateKey: BigInteger,
        preHashedMessage: ByteArray,
    ): ByteArray {
        // Get the parameters for the secp256k1 curve (used in Bitcoin)
        val ecSpec = ECNamedCurveTable.getParameterSpec("secp256k1")
        val domainParams = ECDomainParameters(ecSpec.curve, ecSpec.g, ecSpec.n, ecSpec.h)

        // Create the EC private key parameters
        val privKey = ECPrivateKeyParameters(privateKey, domainParams)

        // Create an ECDSA signer
        val signer = ECDSASigner()
        signer.init(true, privKey)

        // Sign the pre-hashed message
        val signature = signer.generateSignature(preHashedMessage)

        // The signature consists of two components: r and s
        val r = signature[0]
        val s = signature[1]

        // Encode the signature in DER format
        val derSignature = DERSequence(arrayOf(ASN1Integer(r), ASN1Integer(s)))

        return derSignature.encoded
    }

    fun signMessage(
        address: BitcoinAddress,
        message: ByteArray,
        privateKey: ECKey,
        sigHash: Transaction.SigHash = Transaction.SigHash.UNSET,
    ): ByteArray {
        // Segwit P2WPKH and Taproot P2TR use BIP322 signing process
        return when (address) {
            is BitcoinAddress.SegWit -> {
                val msgHash = generateMessageHashSegWit(address, message, privateKey.publicKeyAsHex)
                val signature = signPrehashedMessage(privateKey.privKey, msgHash)
                val pubKey = privateKey.pubKey
                val buffer = ByteBuffer.allocate(signature.size + pubKey.size + 4)
                buffer.put(2)
                buffer.put((signature.size + 1).toByte())
                buffer.put(signature)
                buffer.put(1)
                buffer.put(pubKey.size.toByte())
                buffer.put(pubKey)
                buffer.array()
            }

            is BitcoinAddress.Taproot -> {
                val msgHash = this.generateMessageHashTaproot(address, message, sigHash)
                val signature = Schnorr.sign(msgHash, TaprootUtils.tweakSeckey(privateKey.privKeyBytes))
                val sigSize = if (sigHash != Transaction.SigHash.UNSET) 65 else 64
                val buffer = ByteBuffer.allocate(2 + sigSize)
                buffer.put(1)
                buffer.put(sigSize.toByte())
                buffer.put(signature)
                if (sigSize == 65) {
                    buffer.put(sigHash.byteValue())
                }
                buffer.array()
            }

            else -> throw IllegalArgumentException("Only P2WPKH and P2TR addresses are supported")
        }
    }

    fun signAuthorizeEvmWalletRequest(
        ecKey: ECKey,
        address: BitcoinAddress,
        authorizedAddress: EvmAddress,
        chainId: String = ChainId.BITCOIN,
        timestamp: Instant = Clock.System.now(),
        bip322: Boolean = false,
    ): AuthorizeWalletApiRequest {
        val message = "[funkybit] Please sign this message to authorize EVM wallet ${
            authorizedAddress.value.lowercase()
        }. This action will not cost any gas fees."
        val bitcoinLinkAddressMessage = "$message\nAddress: ${address.value}, Timestamp: $timestamp"
        val signature =
            if (bip322) {
                this.signMessage(address, bitcoinLinkAddressMessage.toByteArray(), ecKey).base64Encode()
            } else {
                ecKey.signMessage(bitcoinLinkAddressMessage)
            }

        return AuthorizeWalletApiRequest(
            authorizedAddress = authorizedAddress,
            chainId = chainId,
            address = address,
            timestamp = timestamp.toString(),
            signature = signature,
        )
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
