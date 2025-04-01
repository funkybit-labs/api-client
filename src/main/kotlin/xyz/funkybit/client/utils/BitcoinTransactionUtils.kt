package xyz.funkybit.client.utils

import io.github.oshai.kotlinlogging.KotlinLogging
import org.bitcoinj.core.Coin
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionOutPoint
import org.bitcoinj.core.TransactionOutput
import org.bitcoinj.script.ScriptBuilder
import xyz.funkybit.client.MempoolSpaceClient
import xyz.funkybit.client.bitcoinConfig
import xyz.funkybit.client.model.UnspentUtxo
import xyz.funkybit.client.model.address.BitcoinAddress
import java.math.BigDecimal
import java.math.BigInteger

class BitcoinTransactionUtils(
    private val mempoolSpaceClient: MempoolSpaceClient,
) {
    val logger = KotlinLogging.logger {}

    private val zeroCoinValue = Coin.valueOf(0)

    fun buildAndSignDepositTx(
        accountAddress: BitcoinAddress,
        amount: BigInteger,
        utxos: List<UnspentUtxo>,
        ecKey: ECKey,
    ): Transaction {
        val params = bitcoinConfig.params
        val feeAmount = estimateDepositTxFee(ecKey, accountAddress, utxos)
        val rawTx = Transaction(params)
        rawTx.setVersion(2)
        rawTx.addOutput(
            TransactionOutput(
                params,
                rawTx,
                Coin.valueOf(amount.toLong()),
                accountAddress.toBitcoinCoreAddress(params),
            ),
        )
        val changeAmount = BigInteger.ZERO.max(utxos.sumOf { it.amount } - amount - feeAmount)
        if (changeAmount > bitcoinConfig.changeDustThreshold) {
            rawTx.addOutput(
                TransactionOutput(
                    params,
                    rawTx,
                    Coin.valueOf(changeAmount.toLong()),
                    BitcoinAddress.fromKey(params, ecKey).toBitcoinCoreAddress(params),
                ),
            )
        }
        utxos.forEach {
            rawTx.addSignedInput(
                TransactionOutPoint(
                    params,
                    it.utxoId.vout(),
                    Sha256Hash.wrap(it.utxoId.txId().toString()),
                ),
                ScriptBuilder.createP2WPKHOutputScript(ecKey),
                Coin.valueOf(it.amount.toLong()),
                ecKey,
                Transaction.SigHash.ALL,
                false,
            )
        }
        return rawTx
    }

    private fun estimateDepositTxFee(
        ecKey: ECKey,
        accountAddress: BitcoinAddress,
        utxos: List<UnspentUtxo>,
    ): BigInteger {
        val params = bitcoinConfig.params
        val rawTx = Transaction(params)
        rawTx.setVersion(2)
        rawTx.addOutput(
            TransactionOutput(
                params,
                rawTx,
                zeroCoinValue,
                accountAddress.toBitcoinCoreAddress(params),
            ),
        )
        rawTx.addOutput(
            TransactionOutput(
                params,
                rawTx,
                zeroCoinValue,
                BitcoinAddress.fromKey(params, ecKey).toBitcoinCoreAddress(params),
            ),
        )
        utxos.forEach {
            rawTx.addSignedInput(
                TransactionOutPoint(
                    params,
                    it.utxoId.vout(),
                    Sha256Hash.wrap(it.utxoId.txId().toString()),
                ),
                ScriptBuilder.createP2WPKHOutputScript(ecKey),
                Coin.valueOf(it.amount.toLong()),
                ecKey,
                Transaction.SigHash.ALL,
                true,
            )
        }
        return mempoolSpaceClient.calculateFee(rawTx.vsize)
    }
}

fun BigInteger.fromSatoshi(): BigDecimal = BigDecimal(this).setScale(8) / BigDecimal("1e8")

fun BigInteger.inSatsAsDecimalString(): String = this.fromSatoshi().toPlainString()

fun String.toArg(): String = this.replace(":", "|")
