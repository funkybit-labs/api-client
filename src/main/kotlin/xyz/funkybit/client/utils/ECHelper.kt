package xyz.funkybit.client.utils

import io.github.oshai.kotlinlogging.KotlinLogging
import org.web3j.crypto.Credentials
import org.web3j.crypto.ECDSASignature
import org.web3j.crypto.Hash
import org.web3j.crypto.Keys
import org.web3j.crypto.Sign
import xyz.funkybit.client.model.SequencerOrderId
import xyz.funkybit.client.model.address.EvmAddress
import xyz.funkybit.client.model.signature.EvmSignature
import xyz.funkybit.client.model.signature.toEvmSignature
import java.math.BigInteger

object ECHelper {
    val logger = KotlinLogging.logger {}

    fun tradeHash(
        buyOrderId: SequencerOrderId,
        sellOrderId: SequencerOrderId,
    ): String = tradeHash(buyOrderId.value, sellOrderId.value)

    fun tradeHash(
        buyOrderId: Long,
        sellOrderId: Long,
    ): String = sha3((buyOrderId.toString() + sellOrderId.toString()).toByteArray()).toHex()

    fun sha3(messageBytes: ByteArray): ByteArray = Hash.sha3(messageBytes)

    fun signData(
        credentials: Credentials,
        hash: ByteArray,
    ): EvmSignature {
        val signature = Sign.signMessage(hash, credentials.ecKeyPair, false)
        return (signature.r + signature.s + signature.v).toHex().toEvmSignature()
    }

    fun isValidSignature(
        messageHash: ByteArray,
        signature: EvmSignature,
        signerAddress: EvmAddress,
        linkedSignerAddress: EvmAddress? = null,
    ): Boolean {
        val (r, s, v) = decodeSignature(signature)
        val ecdsaSig = ECDSASignature(r, s).toCanonicalised()

        val recoveredAddress = Keys.toChecksumAddress(Keys.getAddress(Sign.recoverFromSignature(v.toInt() - 27, ecdsaSig, messageHash)))
        return recoveredAddress == Keys.toChecksumAddress(signerAddress.value) ||
            (linkedSignerAddress != null && recoveredAddress == Keys.toChecksumAddress(linkedSignerAddress.value))
    }

    private fun decodeSignature(signature: EvmSignature): Triple<BigInteger, BigInteger, BigInteger> {
        val bytes = signature.toByteArray()
        return Triple(
            BigInteger(1, bytes.slice(0..31).toByteArray()),
            BigInteger(1, bytes.slice(32..63).toByteArray()),
            BigInteger(1, bytes.slice(64..64).toByteArray()),
        )
    }
}
