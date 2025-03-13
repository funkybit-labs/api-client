package xyz.funkybit.client

import io.github.oshai.kotlinlogging.KLogger
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

private val defaultHttpClient = OkHttpClient.Builder().build()

object HttpClient {
    private val quietMode = ThreadLocal.withInitial { false }

    fun setQuietModeForThread(value: Boolean) {
        quietMode.set(value)
    }

    fun inQuietMode(): Boolean = quietMode.get()

    // It is recommended to use have a single HTTP client instance and share it
    // across the application for better resource utilization
    // newBuilder() method creates another instance that can be customized separately
    // but would still use the same shared connection and thread pools internally
    fun newBuilder(): OkHttpClient.Builder = defaultHttpClient.newBuilder()
}

fun OkHttpClient.Builder.withLogging(logger: KLogger): OkHttpClient.Builder {
    return addInterceptor { chain ->
        if (HttpClient.inQuietMode()) {
            return@addInterceptor chain.proceed(chain.request())
        }

        val request = chain.request()

        // making a copy of request body since it can be consumed only once
        val requestBodyCopy =
            request.newBuilder().build().body?.let { body ->
                val requestBuffer = Buffer()
                body.writeTo(requestBuffer)
                requestBuffer.readUtf8()
            } ?: ""

        val response = chain.proceed(request)
        val contentType: MediaType? = response.body?.contentType()
        val responseBody = response.body?.string()

        logger.debug {
            "HTTP call: url=${request.url}, request=${abbreviateString(
                requestBodyCopy,
            )}, response code=${response.code}, response body=${responseBody?.let(::abbreviateString)}"
        }

        response.newBuilder().body(responseBody?.toResponseBody(contentType)).build()
    }
}

private fun abbreviateString(value: String): String =
    if (value.length < 4096) {
        value
    } else {
        "${value.substring(0, 2048)} ... ${value.substring(value.length - 2048, value.length)}"
    }
