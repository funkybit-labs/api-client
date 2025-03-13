package xyz.funkybit.client

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.decodeFromJsonElement
import xyz.funkybit.client.model.BitcoinRpcParams
import xyz.funkybit.client.model.BitcoinRpcRequest
import xyz.funkybit.client.model.TxHash
import xyz.funkybit.client.model.address.BitcoinAddress
import xyz.funkybit.client.utils.BasicAuthInterceptor
import xyz.funkybit.client.utils.JsonRpcClientBase
import xyz.funkybit.client.utils.inSatsAsDecimalString
import java.math.BigInteger

object BitcoinClient : JsonRpcClientBase(
    System.getenv("BITCOIN_NETWORK_RPC_URL") ?: "http://localhost:18443/wallet/testwallet",
    KotlinLogging.logger {},
    if ((System.getenv("BITCOIN_NETWORK_ENABLE_BASIC_AUTH") ?: "true").toBoolean()) {
        BasicAuthInterceptor(
            System.getenv("BITCOIN_NETWORK_RPC_USER") ?: "user",
            System.getenv("BITCOIN_NETWORK_RPC_PASSWORD") ?: "password",
        )
    } else {
        null
    },
) {
    fun sendToAddress(
        address: BitcoinAddress,
        amount: BigInteger,
    ): TxHash =
        getValue<TxHash>(
            BitcoinRpcRequest(
                "sendtoaddress",
                BitcoinRpcParams(listOf(address.value, amount.inSatsAsDecimalString())),
            ),
        )

    inline fun <reified T> getValue(request: BitcoinRpcRequest): T {
        val jsonElement = call(json.encodeToString(request))
        return json.decodeFromJsonElement(jsonElement)
    }
}
