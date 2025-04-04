package xyz.funkybit.client

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Dispatcher
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.web3j.crypto.Credentials
import xyz.funkybit.client.model.AccountConfigurationApiResponse
import xyz.funkybit.client.model.ApiCallFailure
import xyz.funkybit.client.model.ApiError
import xyz.funkybit.client.model.ApiErrors
import xyz.funkybit.client.model.AuthorizeWalletApiRequest
import xyz.funkybit.client.model.BalancesApiResponse
import xyz.funkybit.client.model.BatchOrdersApiRequest
import xyz.funkybit.client.model.BatchOrdersApiResponse
import xyz.funkybit.client.model.CancelOrderApiRequest
import xyz.funkybit.client.model.ChainId.Companion.BITCOIN
import xyz.funkybit.client.model.ConfigurationApiResponse
import xyz.funkybit.client.model.CreateDepositApiRequest
import xyz.funkybit.client.model.CreateOrderApiRequest
import xyz.funkybit.client.model.CreateOrderApiResponse
import xyz.funkybit.client.model.CreateWithdrawalApiRequest
import xyz.funkybit.client.model.DepositApiResponse
import xyz.funkybit.client.model.DepositId
import xyz.funkybit.client.model.GetConsumptionsApiResponse
import xyz.funkybit.client.model.GetOrderBookApiResponse
import xyz.funkybit.client.model.ListDepositsApiResponse
import xyz.funkybit.client.model.ListWithdrawalsApiResponse
import xyz.funkybit.client.model.MarketId
import xyz.funkybit.client.model.Order
import xyz.funkybit.client.model.OrderId
import xyz.funkybit.client.model.OrderStatus
import xyz.funkybit.client.model.OrdersApiResponse
import xyz.funkybit.client.model.ReasonCode
import xyz.funkybit.client.model.SignInMessage
import xyz.funkybit.client.model.WithdrawalApiResponse
import xyz.funkybit.client.model.WithdrawalId
import xyz.funkybit.client.utils.ECHelper
import xyz.funkybit.client.utils.EIP712Helper
import java.net.HttpURLConnection

class FunkybitApiClient(
    val keyPair: WalletKeyPair = WalletKeyPair.EVM.generate(),
    apiUrl: String = DEFAULT_API_URL,
    chainId: String = "1337",
    val sessionKeyPair: WalletKeyPair = WalletKeyPair.EVM.generate(),
) {
    private val apiServerRootUrl = apiUrl
    private var currentChainId: String = chainId
    var authToken: String =
        issueAuthToken(keyPair = keyPair, chainId = currentChainId, sessionKeyAddress = sessionKeyPair.address().canonicalize().toString())
    val address = keyPair.address()

    companion object {
        @JvmStatic
        val DEFAULT_API_URL = System.getenv("FUNKYBIT_API_URL") ?: "https://prod-api.funkybit.fun"

        val httpClient =
            OkHttpClient
                .Builder()
                .dispatcher(
                    Dispatcher().apply {
                        maxRequests = 1000
                        maxRequestsPerHost = 1000
                    },
                ).build()
        private val applicationJson = "application/json".toMediaType()

        private fun listOrdersUrl(
            statuses: List<OrderStatus>,
            marketId: MarketId?,
        ) = "$DEFAULT_API_URL/v1/orders"
            .toHttpUrl()
            .newBuilder()
            .apply {
                addQueryParameter("statuses", statuses.joinToString(","))
                marketId?.let {
                    addQueryParameter("marketId", it)
                }
            }.build()

        internal fun authHeaders(authToken: String): Headers =
            Headers
                .Builder()
                .add(
                    "Authorization",
                    "Bearer $authToken",
                ).build()

        fun issueAuthToken(
            keyPair: WalletKeyPair = WalletKeyPair.EVM.generate(),
            address: String = keyPair.address().canonicalize().toString(),
            chainId: String = "1337",
            timestamp: Instant = Clock.System.now(),
            sessionKeyAddress: String = keyPair.address().canonicalize().toString(),
        ): String {
            val signInMessage =
                SignInMessage(
                    message =
                        "[funkybit] Please sign this message to verify your ownership of this wallet address." +
                            " This action will not cost any gas fees.",
                    address = address,
                    chainId = if (chainId == BITCOIN) 0L else chainId.toLong(),
                    timestamp = timestamp.toString(),
                    sessionKeyAddress = sessionKeyAddress,
                    ordinalsAddress = null,
                )

            val signature =
                when (keyPair) {
                    is WalletKeyPair.EVM -> {
                        ECHelper.signData(Credentials.create(keyPair.ecKeyPair), EIP712Helper.computeHash(signInMessage)).value
                    }
                    is WalletKeyPair.Bitcoin -> {
                        keyPair.ecKey.signMessage(
                            signInMessage.message + "\n" +
                                listOfNotNull(
                                    "Address: ${signInMessage.address}",
                                    "Timestamp: ${signInMessage.timestamp}",
                                    "Session Key Address: ${signInMessage.sessionKeyAddress}",
                                ).joinToString(", "),
                        )
                    }
                }

            val encodedSignInMessage =
                java.util.Base64
                    .getUrlEncoder()
                    .encodeToString(json.encodeToString(signInMessage).toByteArray())
            return "$encodedSignInMessage.$signature"
        }
    }

    fun newWebSocket(authToken: String) = ReconnectingWebsocketClient(this.apiServerRootUrl, authToken)

    fun switchChain(chainId: String) {
        currentChainId = chainId
        reissueAuthToken()
    }

    fun reissueAuthToken() {
        authToken =
            issueAuthToken(
                keyPair = keyPair,
                chainId = currentChainId,
                sessionKeyAddress = sessionKeyPair.address().canonicalize().toString(),
            )
    }

    fun tryGetConfiguration(): Either<ApiCallFailure, ConfigurationApiResponse> =
        execute(
            Request
                .Builder()
                .url("$apiServerRootUrl/v1/config")
                .get()
                .build(),
        ).toErrorOrPayload(expectedStatusCode = HttpURLConnection.HTTP_OK)

    fun tryCreateOrder(apiRequest: CreateOrderApiRequest): Either<ApiCallFailure, CreateOrderApiResponse> =
        execute(
            Request
                .Builder()
                .url("$apiServerRootUrl/v1/orders")
                .post(json.encodeToString(apiRequest).toRequestBody(applicationJson))
                .build()
                .withAuthHeaders(authToken),
        ).toErrorOrPayload(expectedStatusCode = HttpURLConnection.HTTP_CREATED)

    fun tryBatchOrders(apiRequest: BatchOrdersApiRequest): Either<ApiCallFailure, BatchOrdersApiResponse> =
        execute(
            Request
                .Builder()
                .url("$apiServerRootUrl/v1/batch/orders")
                .post(json.encodeToString(apiRequest).toRequestBody(applicationJson))
                .build()
                .withAuthHeaders(authToken),
        ).toErrorOrPayload(expectedStatusCode = HttpURLConnection.HTTP_OK)

    fun tryCancelOrder(apiRequest: CancelOrderApiRequest): Either<ApiCallFailure, Unit> =
        execute(
            Request
                .Builder()
                .url("$apiServerRootUrl/v1/orders/${apiRequest.orderId}")
                .delete(json.encodeToString(apiRequest).toRequestBody(applicationJson))
                .build()
                .withAuthHeaders(authToken),
        ).toErrorOrUnit(expectedStatusCode = HttpURLConnection.HTTP_NO_CONTENT)

    fun tryGetOrder(id: OrderId): Either<ApiCallFailure, Order> =
        execute(
            Request
                .Builder()
                .url("$apiServerRootUrl/v1/orders/$id")
                .get()
                .build()
                .withAuthHeaders(authToken),
        ).toErrorOrPayload(HttpURLConnection.HTTP_OK)

    fun tryListOrders(
        statuses: List<OrderStatus> = emptyList(),
        marketId: MarketId? = null,
    ): Either<ApiCallFailure, OrdersApiResponse> =
        execute(
            Request
                .Builder()
                .url(listOrdersUrl(statuses, marketId))
                .get()
                .build()
                .withAuthHeaders(authToken),
        ).toErrorOrPayload(HttpURLConnection.HTTP_OK)

    fun tryCancelOpenOrders(marketIds: List<MarketId>): Either<ApiCallFailure, Unit> =
        execute(
            Request
                .Builder()
                .url(
                    "$apiServerRootUrl/v1/orders"
                        .toHttpUrl()
                        .newBuilder()
                        .apply {
                            if (marketIds.isNotEmpty()) {
                                addQueryParameter("marketIds", marketIds.joinToString(","))
                            }
                        }.build(),
                ).delete()
                .build()
                .withAuthHeaders(authToken),
        ).toErrorOrUnit(expectedStatusCode = HttpURLConnection.HTTP_NO_CONTENT)

    fun tryGetOrderBook(marketId: MarketId): Either<ApiCallFailure, GetOrderBookApiResponse> =
        execute(
            Request
                .Builder()
                .url(
                    "$apiServerRootUrl/v1/order-book".toHttpUrl().newBuilder().addPathSegment(marketId).build(),
                ).get()
                .build()
                .withAuthHeaders(authToken),
        ).toErrorOrPayload(HttpURLConnection.HTTP_OK)

    fun tryGetConsumptions(): Either<ApiCallFailure, GetConsumptionsApiResponse> =
        execute(
            Request
                .Builder()
                .url("$apiServerRootUrl/v1/consumptions")
                .get()
                .build()
                .withAuthHeaders(authToken),
        ).toErrorOrPayload(HttpURLConnection.HTTP_OK)

    fun tryCreateDeposit(apiRequest: CreateDepositApiRequest): Either<ApiCallFailure, DepositApiResponse> =
        execute(
            Request
                .Builder()
                .url("$apiServerRootUrl/v1/deposits")
                .post(json.encodeToString(apiRequest).toRequestBody(applicationJson))
                .build()
                .withAuthHeaders(authToken),
        ).toErrorOrPayload(expectedStatusCode = HttpURLConnection.HTTP_CREATED)

    fun tryGetDeposit(id: DepositId): Either<ApiCallFailure, DepositApiResponse> =
        execute(
            Request
                .Builder()
                .url("$apiServerRootUrl/v1/deposits/$id")
                .get()
                .build()
                .withAuthHeaders(authToken),
        ).toErrorOrPayload(expectedStatusCode = HttpURLConnection.HTTP_OK)

    fun tryListDeposits(): Either<ApiCallFailure, ListDepositsApiResponse> =
        execute(
            Request
                .Builder()
                .url("$apiServerRootUrl/v1/deposits")
                .get()
                .build()
                .withAuthHeaders(authToken),
        ).toErrorOrPayload(expectedStatusCode = HttpURLConnection.HTTP_OK)

    fun tryCreateWithdrawal(apiRequest: CreateWithdrawalApiRequest): Either<ApiCallFailure, WithdrawalApiResponse> =
        execute(
            Request
                .Builder()
                .url("$apiServerRootUrl/v1/withdrawals")
                .post(json.encodeToString(apiRequest).toRequestBody(applicationJson))
                .build()
                .withAuthHeaders(authToken),
        ).toErrorOrPayload(expectedStatusCode = HttpURLConnection.HTTP_CREATED)

    fun tryGetWithdrawal(id: WithdrawalId): Either<ApiCallFailure, WithdrawalApiResponse> =
        execute(
            Request
                .Builder()
                .url("$apiServerRootUrl/v1/withdrawals/$id")
                .get()
                .build()
                .withAuthHeaders(authToken),
        ).toErrorOrPayload(expectedStatusCode = HttpURLConnection.HTTP_OK)

    fun tryListWithdrawals(): Either<ApiCallFailure, ListWithdrawalsApiResponse> =
        execute(
            Request
                .Builder()
                .url("$apiServerRootUrl/v1/withdrawals")
                .get()
                .build()
                .withAuthHeaders(authToken),
        ).toErrorOrPayload(expectedStatusCode = HttpURLConnection.HTTP_OK)

    fun tryGetBalances(): Either<ApiCallFailure, BalancesApiResponse> =
        execute(
            Request
                .Builder()
                .url("$apiServerRootUrl/v1/balances")
                .get()
                .build()
                .withAuthHeaders(authToken),
        ).toErrorOrPayload(expectedStatusCode = HttpURLConnection.HTTP_OK)

    fun tryAuthorizeWallet(apiRequest: AuthorizeWalletApiRequest): Either<ApiCallFailure, Unit> =
        execute(
            Request
                .Builder()
                .url("$apiServerRootUrl/v1/wallets/authorize")
                .post(json.encodeToString(apiRequest).toRequestBody(applicationJson))
                .build()
                .withAuthHeaders(authToken),
        ).toErrorOrUnit(expectedStatusCode = HttpURLConnection.HTTP_NO_CONTENT)

    fun tryGetAccountConfiguration(): Either<ApiCallFailure, AccountConfigurationApiResponse> =
        execute(
            Request
                .Builder()
                .url("$apiServerRootUrl/v1/account-config")
                .get()
                .build()
                .withAuthHeaders(authToken),
        ).toErrorOrPayload(expectedStatusCode = HttpURLConnection.HTTP_OK)

    fun getConfiguration(): ConfigurationApiResponse = tryGetConfiguration().throwOrReturn()

    fun createOrder(apiRequest: CreateOrderApiRequest): CreateOrderApiResponse = tryCreateOrder(apiRequest).throwOrReturn()

    fun batchOrders(apiRequest: BatchOrdersApiRequest): BatchOrdersApiResponse = tryBatchOrders(apiRequest).throwOrReturn()

    fun cancelOrder(apiRequest: CancelOrderApiRequest) = tryCancelOrder(apiRequest).throwOrReturn()

    fun getOrder(id: OrderId): Order = tryGetOrder(id).throwOrReturn()

    fun listOrders(
        statuses: List<OrderStatus> = emptyList(),
        marketId: MarketId? = null,
    ): OrdersApiResponse = tryListOrders(statuses, marketId).throwOrReturn()

    fun cancelOpenOrders(marketIds: List<MarketId> = emptyList()) = tryCancelOpenOrders(marketIds).throwOrReturn()

    fun getOrderBook(marketId: MarketId): GetOrderBookApiResponse = tryGetOrderBook(marketId).throwOrReturn()

    fun getConsumptions(): GetConsumptionsApiResponse = tryGetConsumptions().throwOrReturn()

    fun createDeposit(apiRequest: CreateDepositApiRequest): DepositApiResponse = tryCreateDeposit(apiRequest).throwOrReturn()

    fun getDeposit(id: DepositId): DepositApiResponse = tryGetDeposit(id).throwOrReturn()

    fun listDeposits(): ListDepositsApiResponse = tryListDeposits().throwOrReturn()

    fun createWithdrawal(apiRequest: CreateWithdrawalApiRequest): WithdrawalApiResponse = tryCreateWithdrawal(apiRequest).throwOrReturn()

    fun getWithdrawal(id: WithdrawalId): WithdrawalApiResponse = tryGetWithdrawal(id).throwOrReturn()

    fun listWithdrawals(): ListWithdrawalsApiResponse = tryListWithdrawals().throwOrReturn()

    fun getBalances(): BalancesApiResponse = tryGetBalances().throwOrReturn()

    fun authorizeWallet(apiRequest: AuthorizeWalletApiRequest) = tryAuthorizeWallet(apiRequest).throwOrReturn()

    fun getAccountConfiguration() = tryGetAccountConfiguration().throwOrReturn()

    // Helper methods
    private fun execute(request: Request): Response = httpClient.newCall(request).execute()
}

class MaintenanceMode : Exception()

// Helper extension functions
fun <T> Either<ApiCallFailure, T>.throwOrReturn(): T {
    if (this.isLeft()) {
        val apiCallFailure = this.leftOrNull()!!
        if (apiCallFailure.httpCode == 418) {
            throw MaintenanceMode()
        }
        throw Exception("HTTP ${apiCallFailure.httpCode}: ${apiCallFailure.error?.displayMessage ?: "Unknown Error"}")
    }
    return this.getOrNull()!!
}

inline fun <reified T> Response.toErrorOrPayload(expectedStatusCode: Int): Either<ApiCallFailure, T> =
    either {
        val bodyString = body?.string()
        val json = Json { ignoreUnknownKeys = true }

        ensure(code == expectedStatusCode) {
            val apiError =
                bodyString?.let {
                    try {
                        json.decodeFromString<ApiErrors>(bodyString).errors.single()
                    } catch (e: Exception) {
                        ApiError(ReasonCode.ApiErrorParsingError, "Unable to parse body: $it")
                    }
                }

            ApiCallFailure(code, apiError)
        }

        json.decodeFromString<T>(bodyString!!)
    }

fun Response.toErrorOrUnit(expectedStatusCode: Int): Either<ApiCallFailure, Unit> =
    either {
        val bodyString = body?.string()
        val json = Json { ignoreUnknownKeys = true }

        ensure(code == expectedStatusCode) {
            val apiError =
                bodyString?.let {
                    try {
                        json.decodeFromString<ApiErrors>(bodyString).errors.single()
                    } catch (e: Exception) {
                        null
                    }
                }

            ApiCallFailure(code, apiError)
        }
    }

fun Request.withAuthHeaders(authToken: String): Request =
    this
        .newBuilder()
        .headers(
            this
                .headers
                .newBuilder()
                .addAll(FunkybitApiClient.authHeaders(authToken))
                .build(),
        ).build()
