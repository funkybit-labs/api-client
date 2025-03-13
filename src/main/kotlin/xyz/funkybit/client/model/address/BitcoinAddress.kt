package xyz.funkybit.client.model.address

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.bitcoinj.core.Base58
import org.bitcoinj.core.Bech32
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.SegwitAddress
import org.bitcoinj.core.Utils
import org.bitcoinj.script.Script
import org.bitcoinj.script.ScriptOpCodes
import xyz.funkybit.client.utils.BitcoinSignatureUtils.opPushData
import xyz.funkybit.client.utils.BitcoinSignatureUtils.padZeroHexN
import xyz.funkybit.client.utils.TaprootUtils
import xyz.funkybit.client.utils.schnorr.Point

object BitcoinAddressSerializer : KSerializer<BitcoinAddress> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("BitcoinAddress", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: BitcoinAddress,
    ) = encoder.encodeString(value.value)

    override fun deserialize(decoder: Decoder) = BitcoinAddress.canonicalize(decoder.decodeString())
}

@Serializable(with = BitcoinAddressSerializer::class)
sealed class BitcoinAddress(
    val value: String,
) : Address() {
    companion object {
        fun canonicalize(value: String) = Unrecognized(value).canonicalize()

        fun fromKey(
            params: NetworkParameters,
            key: ECKey,
        ): SegWit {
            val value =
                org.bitcoinj.core.Address
                    .fromKey(params, key, Script.ScriptType.P2WPKH)
                    .toString()
            return if (value.startsWith("bc1q")) {
                SegWit(value, false)
            } else if (value.startsWith("tb1q") || value.startsWith("bcrt1q")) {
                SegWit(value, true)
            } else {
                throw Exception("Not a segwit address")
            }
        }

        fun taprootFromKey(
            params: NetworkParameters,
            key: ECKey,
        ): Taproot {
            val value =
                SegwitAddress
                    .fromProgram(
                        params,
                        1,
                        TaprootUtils.tweakPubkey(Point.genPubKey(key.privKeyBytes)),
                    ).toBech32()
            return if (value.startsWith("bc1p")) {
                Taproot(value, false)
            } else if (value.startsWith("tb1p") || value.startsWith("bcrt1p")) {
                Taproot(value, true)
            } else {
                throw Exception("Not a taproot address")
            }
        }
    }

    override fun canonicalize() =
        when {
            this.value.startsWith("bc1q") -> SegWit(this.value, false)
            this.value.startsWith("tb1q") -> SegWit(this.value, true)
            this.value.startsWith("bcrt1q") -> SegWit(this.value, true)
            this.value.startsWith("bc1p") -> Taproot(this.value, false)
            this.value.startsWith("bcrt1p") -> Taproot(this.value, true)
            this.value.startsWith("tb1p") -> Taproot(this.value, true)
            this.value.startsWith("3") -> P2SH(this.value, false)
            this.value.startsWith("2") -> P2SH(this.value, true)
            this.value.startsWith("1") -> P2PKH(this.value, false)
            this.value.startsWith("m") || this.value.startsWith("n") -> P2PKH(this.value, true)
            else -> Unrecognized(this.value)
        }

    override fun toString() = this.value

    override fun abbreviated(): String = this.value.take(5) + "..." + this.value.takeLast(5)

    abstract fun script(): String

    fun isTest() =
        when (this) {
            is SegWit -> this.testnet
            is Taproot -> this.testnet
            is P2SH -> this.testnet
            is P2PKH -> this.testnet
            is Unrecognized -> false
        }

    data class SegWit(
        val raw: String,
        val testnet: Boolean,
    ) : BitcoinAddress(raw) {
        override fun script(): String {
            val decoded = Bech32.decode(raw)
            val hash = Utils.HEX.encode(convertBits(decoded.data.copyOfRange(1, decoded.data.size), 5, 8, false))
            return padZeroHexN(ScriptOpCodes.OP_0.toString(16), 2) + opPushData(hash)
        }

        companion object {
            fun generate(networkParameters: NetworkParameters): SegWit = fromKey(networkParameters, ECKey())
        }

        private fun alternateHrp() = if (testnet) "bc1q" else "tb1q"

        fun alternateAddress() = SegWit(Bech32.encode(Bech32.Encoding.BECH32, alternateHrp(), Bech32.decode(raw).data), !testnet)

        override fun toString(): String = this.raw
    }

    data class Taproot(
        val raw: String,
        val testnet: Boolean,
    ) : BitcoinAddress(raw) {
        override fun script(): String {
            val decoded = Bech32.decode(raw)
            val tapTweakedPubkey = Utils.HEX.encode(convertBits(decoded.data.copyOfRange(1, decoded.data.size), 5, 8, false))
            return padZeroHexN(ScriptOpCodes.OP_1.toString(16), 2) + opPushData(tapTweakedPubkey)
        }

        private fun alternateHrp() = if (testnet) "bc1p" else "tb1p"

        fun alternateAddress() = Taproot(Bech32.encode(Bech32.Encoding.BECH32M, alternateHrp(), Bech32.decode(raw).data), !testnet)

        override fun toString(): String = this.raw
    }

    data class P2SH(
        val raw: String,
        val testnet: Boolean,
    ) : BitcoinAddress(raw) {
        override fun script(): String {
            val decoded = Base58.decode(raw.substring(1))
            val hash = Utils.HEX.encode(decoded)
            return padZeroHexN(ScriptOpCodes.OP_HASH160.toString(16), 2) + opPushData(hash) +
                padZeroHexN(ScriptOpCodes.OP_EQUAL.toString(16), 2)
        }

        override fun toString(): String = this.raw
    }

    data class P2PKH(
        val raw: String,
        val testnet: Boolean,
    ) : BitcoinAddress(raw) {
        override fun script(): String {
            val decoded = Base58.decode(raw)
            val hash = Utils.HEX.encode(decoded.copyOfRange(1, 21))
            return padZeroHexN(ScriptOpCodes.OP_DUP.toString(16), 2) +
                padZeroHexN(ScriptOpCodes.OP_HASH160.toString(16), 2) +
                opPushData(hash) +
                padZeroHexN(ScriptOpCodes.OP_EQUALVERIFY.toString(16), 2) +
                padZeroHexN(ScriptOpCodes.OP_CHECKSIG.toString(16), 2)
        }

        override fun toString(): String = this.raw
    }

    data class Unrecognized(
        val raw: String,
    ) : BitcoinAddress(raw) {
        override fun script() = this.raw

        override fun toString(): String = this.raw
    }

    protected fun convertBits(
        data: ByteArray,
        fromBits: Int,
        toBits: Int,
        pad: Boolean,
    ): ByteArray {
        var acc = 0
        var bits = 0
        val maxv = (1 shl toBits) - 1
        val result = mutableListOf<Byte>()

        for (value in data) {
            acc = (acc shl fromBits) or (value.toInt() and 0xff)
            bits += fromBits
            while (bits >= toBits) {
                bits -= toBits
                result.add(((acc shr bits) and maxv).toByte())
            }
        }

        if (pad) {
            if (bits > 0) {
                result.add(((acc shl (toBits - bits)) and maxv).toByte())
            }
        } else if (bits >= fromBits || ((acc shl (toBits - bits)) and maxv) != 0) {
            throw IllegalArgumentException("Invalid bit conversion")
        }

        return result.toByteArray()
    }

    fun toBitcoinCoreAddress(params: NetworkParameters): org.bitcoinj.core.Address =
        org.bitcoinj.core.Address
            .fromString(params, value)
}
