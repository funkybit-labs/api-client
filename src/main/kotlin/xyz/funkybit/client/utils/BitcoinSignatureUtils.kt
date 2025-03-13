package xyz.funkybit.client.utils

import org.bitcoinj.core.Coin
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionInput
import org.bitcoinj.core.TransactionOutPoint
import org.bitcoinj.core.TransactionOutput
import org.bitcoinj.core.Utils
import org.bitcoinj.script.Script
import org.bitcoinj.script.ScriptBuilder
import org.bitcoinj.script.ScriptOpCodes
import xyz.funkybit.client.bitcoinConfig
import xyz.funkybit.client.model.address.BitcoinAddress

object BitcoinSignatureUtils {
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
}
