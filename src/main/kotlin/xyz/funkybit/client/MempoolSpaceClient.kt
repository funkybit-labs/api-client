package xyz.funkybit.client

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import xyz.funkybit.client.model.BlockHash
import xyz.funkybit.client.model.TxHash
import xyz.funkybit.client.model.address.BitcoinAddress
import xyz.funkybit.client.utils.BigIntegerJson
import java.math.BigInteger

sealed class MempoolSpaceApi {
    @Serializable
    data class Transaction(
        @SerialName("txid")
        val txId: TxHash,
        val version: Int,
        val size: Int,
        val weight: Int,
        @SerialName("vin")
        val vins: List<VIn>,
        @SerialName("vout")
        val vouts: List<VOut>,
        val status: Status,
    ) {
        fun outputsMatchingWallet(address: BitcoinAddress) = vouts.filter { it.scriptPubKeyAddress == address }

        fun numConfirms(currentHeight: Long): Long =
            if (status.confirmed && status.blockHeight != null) {
                maxOf(currentHeight - status.blockHeight + 1, 0)
            } else {
                0
            }
    }

    @Serializable
    data class UnspentUtxo(
        @SerialName("txid")
        val txId: TxHash,
        val vout: Long,
        val status: Status,
        val value: BigIntegerJson,
    )

    @Serializable
    data class Status(
        val confirmed: Boolean,
        @SerialName("block_height")
        val blockHeight: Long?,
    )

    @Serializable
    data class VOut(
        val value: BigIntegerJson,
        @SerialName("scriptpubkey_address")
        val scriptPubKeyAddress: BitcoinAddress?,
    )

    @Serializable
    data class VIn(
        @SerialName("txid")
        val txId: TxHash,
        val vout: Long,
        @SerialName("prevout")
        val prevOut: VOut?,
    )

    data class MempoolApiFailure(
        val error: String,
    )

    @Serializable
    data class Stats(
        @SerialName("funded_txo_sum")
        val fundedTxoSum: Long,
        @SerialName("spent_txo_sum")
        val spentTxoSum: Long,
    )

    @Serializable
    data class AddressStats(
        val address: String,
        @SerialName("chain_stats")
        val chainStats: Stats,
        @SerialName("mempool_stats")
        val mempoolStats: Stats,
    )

    @Serializable
    data class RecommendedFees(
        val fastestFee: Int,
        val halfHourFee: Int,
        val hourFee: Int,
        val economyFee: Int,
        val minimumFee: Int,
    )

    @Serializable
    data class BlockDetails(
        val id: BlockHash,
        val height: BigIntegerJson,
        @SerialName("previousblockhash")
        val previousBlockHash: BlockHash,
    )
}

class MempoolSpaceClient(
    private val apiServerRootUrl: String = System.getenv("MEMPOOL_SPACE_API_URL") ?: "https://mempool.space/api",
) {
    val logger = KotlinLogging.logger {}
    private val recommendedFeeRoute = System.getenv("MEMPOOL_SPACE_RECOMMENDED_FEE_ROUTE") ?: "fees/recommended"
    private val httpClient = HttpClient.newBuilder().withLogging(logger).build()

    val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            isLenient = true
            explicitNulls = false
        }

    private val mediaType = "text/plain".toMediaTypeOrNull()

    private val minFee = bitcoinConfig.feeSettings.minValue.toBigInteger()
    private val maxFee = bitcoinConfig.feeSettings.maxValue.toBigInteger()

    private val maxAttempts: Int = 5
    private val retryCodes = listOf(502, 429)

    private fun execute(request: Request): Response = executeWithRetryBackoff(request, 1)

    private fun executeWithRetryBackoff(
        request: Request,
        numAttempts: Long,
    ): Response {
        val response = httpClient.newCall(request).execute()
        if (retryCodes.contains(response.code) && numAttempts <= maxAttempts) {
            logger.warn { "Retry $numAttempts since we received ${response.code}" }
            Thread.sleep(100 * numAttempts)
            return executeWithRetryBackoff(request, numAttempts + 1)
        }
        return response
    }

    fun getUnspentUtxos(walletAddress: BitcoinAddress): List<MempoolSpaceApi.UnspentUtxo> =
        execute(
            Request
                .Builder()
                .url("$apiServerRootUrl/address/${walletAddress.value}/utxo".toHttpUrl().newBuilder().build())
                .get()
                .build(),
        ).toPayload()

    fun getTransaction(txId: TxHash): MempoolSpaceApi.Transaction? {
        val response =
            execute(
                Request
                    .Builder()
                    .url("$apiServerRootUrl/tx/$txId".toHttpUrl().newBuilder().build())
                    .get()
                    .build(),
            )
        return when (response.code) {
            404 -> null
            200 -> response.toPayload()
            else -> throw Exception("Error getting tx - ${response.code}, ${response.body}")
        }
    }

    fun sendTransaction(rawTransactionHex: String): TxHash =
        execute(
            Request
                .Builder()
                .url("$apiServerRootUrl/tx".toHttpUrl().newBuilder().build())
                .post(rawTransactionHex.toRequestBody(mediaType))
                .build(),
        ).toPayload()

    fun getBalance(walletAddress: BitcoinAddress): Long {
        val stats: MempoolSpaceApi.AddressStats =
            execute(
                Request
                    .Builder()
                    .url("$apiServerRootUrl/address/${walletAddress.value}".toHttpUrl().newBuilder().build())
                    .get()
                    .build(),
            ).toPayload()
        return stats.chainStats.fundedTxoSum + stats.mempoolStats.fundedTxoSum - stats.chainStats.spentTxoSum -
            stats.mempoolStats.spentTxoSum
    }

    fun getRecommendedFees(): MempoolSpaceApi.RecommendedFees =
        execute(
            Request
                .Builder()
                .url("$apiServerRootUrl/$recommendedFeeRoute".toHttpUrl().newBuilder().build())
                .get()
                .build(),
        ).toPayload()

    fun getCurrentBlock(): Long =
        execute(
            Request
                .Builder()
                .url("$apiServerRootUrl/blocks/tip/height".toHttpUrl().newBuilder().build())
                .get()
                .build(),
        ).toPayload()

    fun getBlockHash(blockHeight: Long): BlockHash =
        execute(
            Request
                .Builder()
                .url("$apiServerRootUrl/block-height/$blockHeight".toHttpUrl().newBuilder().build())
                .get()
                .build(),
        ).toPayload()

    fun getBlockDetails(blockHash: BlockHash): MempoolSpaceApi.BlockDetails =
        execute(
            Request
                .Builder()
                .url("$apiServerRootUrl/block/${blockHash.value}".toHttpUrl().newBuilder().build())
                .get()
                .build(),
        ).toPayload()

    fun getBlockTransactionIds(blockHash: BlockHash): List<TxHash> =
        execute(
            Request
                .Builder()
                .url("$apiServerRootUrl/block/${blockHash.value}/txids".toHttpUrl().newBuilder().build())
                .get()
                .build(),
        ).toPayload()

    fun getTransactionAtIndex(
        blockHash: BlockHash,
        index: Int,
    ): TxHash =
        execute(
            Request
                .Builder()
                .url("$apiServerRootUrl/block/${blockHash.value}/txid/$index".toHttpUrl().newBuilder().build())
                .get()
                .build(),
        ).toPayload()

    fun calculateFee(
        vsize: Int,
        recommendedFee: MempoolSpaceApi.RecommendedFees = getRecommendedFees(),
    ) = maxFee.min(minFee.max(recommendedFee.fastestFee.toBigInteger())) * vsize.toBigInteger()

    fun estimateVSize(
        numIn: Int,
        numOut: Int,
    ): Int = 11 + numIn * 63 + numOut * 41

    fun estimateEtchingPostage(recommendedFees: MempoolSpaceApi.RecommendedFees): Pair<BigInteger, BigInteger> {
        // this is for 1 taproot input, 1 out (for initial mint) plus 1 runestone with a maximum length rune (26 chars)
        val etchingVSize = 185
        val feeEstimateEtch = calculateFee(etchingVSize, recommendedFees)
        // 1 input and 1 out
        val nameCommitmentVSize = 122
        val feeEstimateCommit = calculateFee(nameCommitmentVSize, recommendedFees)
        return Pair(feeEstimateEtch + bitcoinConfig.changeDustThreshold, feeEstimateCommit)
    }

    private inline fun <reified T> Response.toPayload(): T {
        val bodyString = body?.string()
        if (!isSuccessful) {
            logger.warn { "API call failed with code=$code, body=$bodyString" }
            throw Exception(bodyString ?: "Unknown Error")
        }
        return json.decodeFromString<T>(bodyString!!)
    }

    fun getNetworkFeeForTx(txId: TxHash): Long = getTransaction(txId)?.let { getNetworkFeeForTx(it) } ?: throw Exception("Tx not found")

    fun getNetworkFeeForTx(tx: MempoolSpaceApi.Transaction): Long =
        (
            tx.vins.sumOf {
                it.prevOut?.value ?: BigInteger.ZERO
            } -
                tx.vouts.sumOf {
                    it.value
                }
        ).toLong()
}
