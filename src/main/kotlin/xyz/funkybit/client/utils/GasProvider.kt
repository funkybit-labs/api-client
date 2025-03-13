package xyz.funkybit.client.utils

import io.github.oshai.kotlinlogging.KotlinLogging
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.tx.gas.ContractEIP1559GasProvider
import java.math.BigInteger

open class GasProvider(
    private val gasLimit: BigInteger,
    private val defaultMaxPriorityFeePerGas: BigInteger,
    private val chainId: Long,
    val web3j: Web3j,
) : ContractEIP1559GasProvider {
    val logger = KotlinLogging.logger {}

    private var maxPriorityFeePerGas: BigInteger = defaultMaxPriorityFeePerGas

    override fun getGasLimit(contractFunc: String?) = gasLimit

    override fun getGasPrice(contractFunc: String?): BigInteger = BigInteger.ZERO

    override fun isEIP1559Enabled() = true

    override fun getChainId() = chainId

    override fun getMaxFeePerGas(contractFunc: String?): BigInteger = getMaxFeePerGas(getMaxPriorityFeePerGas(""))

    private fun getMaxFeePerGas(maxPriorityFeePerGas: BigInteger): BigInteger {
        val baseFeePerGas =
            web3j
                .ethGetBlockByNumber(DefaultBlockParameterName.LATEST, false)
                .send()
                .block.baseFeePerGas
        return baseFeePerGas.multiply(BigInteger.TWO).add(maxPriorityFeePerGas)
    }

    override fun getMaxPriorityFeePerGas(contractFunc: String?): BigInteger = maxPriorityFeePerGas

    override fun getGasLimit(): BigInteger = gasLimit

    override fun getGasPrice(): BigInteger = getGasPrice("")
}
