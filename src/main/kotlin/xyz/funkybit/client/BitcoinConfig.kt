package xyz.funkybit.client

import org.bitcoinj.core.NetworkParameters
import java.math.BigInteger

data class BitcoinConfig(
    val enabled: Boolean,
    val net: String,
    val feeSettings: FeeEstimationSettings,
    val blockExplorerNetName: String,
    val blockExplorerUrl: String,
    val changeDustThreshold: BigInteger,
) {
    enum class SmartFeeMode {
        ECONOMICAL,
        CONSERVATIVE,
    }

    data class FeeEstimationSettings(
        val blocks: Int,
        val mode: SmartFeeMode,
        val minValue: Int,
        val maxValue: Int,
    )

    val params: NetworkParameters = NetworkParameters.fromID(net)!!
    val isTestnet = net == NetworkParameters.ID_TESTNET || net == NetworkParameters.ID_REGTEST
    val isMainnet = net == NetworkParameters.ID_MAINNET
    val runeUtxoSats = changeDustThreshold
}

val bitcoinConfig =
    BitcoinConfig(
        enabled = (System.getenv("BITCOIN_NETWORK_ENABLED") ?: "true").toBoolean(),
        net = System.getenv("BITCOIN_NETWORK_NAME") ?: "org.bitcoin.regtest",
        feeSettings =
            BitcoinConfig.FeeEstimationSettings(
                blocks = 1,
                mode = BitcoinConfig.SmartFeeMode.CONSERVATIVE,
                minValue = System.getenv("BITCOIN_MIN_FEE_VALUE")?.toIntOrNull() ?: 5,
                maxValue = System.getenv("BITCOIN_MAX_FEE_VALUE")?.toIntOrNull() ?: 50,
            ),
        blockExplorerNetName = System.getenv("BLOCK_EXPLORER_NET_NAME_BITCOIN") ?: "Bitcoin Network",
        blockExplorerUrl = System.getenv("BLOCK_EXPLORER_URL_BITCOIN") ?: "http://localhost:1080",
        changeDustThreshold = BigInteger(System.getenv("BITCOIN_CHANGE_DUST_THRESHOLD") ?: "546"),
    )
