package xyz.funkybit.client.utils

import io.github.oshai.kotlinlogging.KLogger
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.Credentials
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import xyz.funkybit.client.HttpClient
import xyz.funkybit.client.withLogging

class BasicAuthInterceptor(
    user: String,
    password: String,
) : Interceptor {
    private val credentials: String

    init {
        credentials = Credentials.basic(user, password)
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request: Request = chain.request()
        val authenticatedRequest =
            request
                .newBuilder()
                .header("Authorization", credentials)
                .build()
        return chain.proceed(authenticatedRequest)
    }
}

class ApiKeyInterceptor(
    private val headerName: String,
    private val apiKey: String,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request: Request = chain.request()
        val authenticatedRequest =
            request
                .newBuilder()
                .header(headerName, apiKey)
                .build()
        return chain.proceed(authenticatedRequest)
    }
}

@Serializable
data class JsonRpcError(
    val code: Int,
    val message: String,
)

class JsonRpcException(
    val error: JsonRpcError,
) : Exception(error.message)

open class JsonRpcClientBase(
    val url: String,
    val logger: KLogger,
    interceptor: Interceptor? = null,
) {
    @OptIn(ExperimentalSerializationApi::class)
    val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            isLenient = true
            explicitNulls = false
        }

    private val httpClient =
        HttpClient
            .newBuilder()
            .withLogging(logger)
            .let { builder ->
                interceptor?.let { builder.addInterceptor(it) } ?: builder
            }.build()

    open val mediaType = "text/plain".toMediaTypeOrNull()

    fun call(requestString: String): JsonElement {
        val body = requestString.toRequestBody(mediaType)

        val httpRequest =
            Request
                .Builder()
                .url(url)
                .post(body)
                .build()
        val httpResponse =
            try {
                httpClient.newCall(httpRequest).execute()
            } catch (e: Exception) {
                throw Exception("send error: ${e.message}")
            }
        val httpResponseBody = httpResponse.body?.string() ?: ""
        return try {
            when (httpResponse.code) {
                in 200..204 -> {
                    when (val jsonBody = json.parseToJsonElement(httpResponseBody)) {
                        is JsonObject ->
                            if (jsonBody.containsKey("error") && jsonBody["error"] != JsonNull) {
                                throw JsonRpcException(json.decodeFromJsonElement<JsonRpcError>(jsonBody["error"]!!))
                            } else {
                                jsonBody["result"]
                            }
                        else -> null
                    } ?: throw Exception("Failed to extract response")
                }
                else -> {
                    // it seems to also give 500s with RPC errors or RPC errors embedded within a string
                    try {
                        (json.parseToJsonElement(httpResponseBody) as JsonObject)["error"]?.let {
                            json.decodeFromJsonElement<JsonRpcError>(it)
                        }
                    } catch (e: Exception) {
                        null
                    }?.let {
                        throw JsonRpcException(it)
                    }
                    throw Exception("Got an error (${httpResponse.code}) - $httpResponseBody")
                }
            }
        } catch (e: Exception) {
            throw e
        } finally {
            httpResponse.body?.close()
        }
    }
}
