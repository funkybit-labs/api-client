package xyz.funkybit.client

import org.bitcoinj.core.ECKey
import org.bitcoinj.core.NetworkParameters
import org.web3j.crypto.Credentials
import org.web3j.crypto.ECKeyPair
import org.web3j.crypto.Keys
import org.web3j.utils.Numeric
import xyz.funkybit.client.model.address.Address
import xyz.funkybit.client.model.address.BitcoinAddress
import xyz.funkybit.client.model.address.EvmAddress
import xyz.funkybit.client.utils.toHexBytes

sealed class WalletKeyPair {
    data class EVM(
        val ecKeyPair: ECKeyPair,
    ) : WalletKeyPair() {
        companion object {
            fun generate(): EVM = EVM(Keys.createEcKeyPair())

            fun fromPrivateKeyHex(privKeyHex: String): EVM = EVM(ECKeyPair.create(Numeric.toBigInt(privKeyHex)))
        }

        val privateKey = ecKeyPair.privateKey
        val credentials = Credentials.create(ecKeyPair)

        override fun address(): EvmAddress = EvmAddress.canonicalize(Credentials.create(ecKeyPair).address)
    }

    data class Bitcoin(
        val ecKey: ECKey,
        val networkParams: NetworkParameters,
    ) : WalletKeyPair() {
        companion object {
            fun generate(networkParams: NetworkParameters): Bitcoin = Bitcoin(ECKey(), networkParams)

            fun fromPrivateKeyHex(
                privKeyHex: String,
                networkParams: NetworkParameters,
            ): Bitcoin = Bitcoin(ECKey.fromPrivate(privKeyHex.toHexBytes()), networkParams)
        }

        override fun address(): BitcoinAddress = BitcoinAddress.fromKey(networkParams, ecKey)
    }

    abstract fun address(): Address

    fun asEcKeyPair(): ECKeyPair = (this as EVM).ecKeyPair
}
