package ch.rhosys.sbb.data.log

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.time.Instant

// Records every call made through the app's shared OkHttpClient in [AppLog]. Bodies are
// read only for failed calls, and capped — successful responses (a whole GTFS feed, say)
// are never buffered.
class AppLogInterceptor(private val log: AppLog) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val startNanos = System.nanoTime()
        val url = request.url.redacted()
        val response = try {
            chain.proceed(request)
        } catch (e: IOException) {
            log.add(
                AppLogEntry.Http(Instant.now(), request.method, url, null, elapsedMs(startNanos), e.toString())
            )
            throw e
        }
        val detail = if (response.isSuccessful) null else {
            runCatching { response.peekBody(MAX_BODY_BYTES).string() }.getOrNull()
                ?.ifBlank { null }
                ?: response.message.ifBlank { null }
        }
        log.add(
            AppLogEntry.Http(Instant.now(), request.method, url, response.code, elapsedMs(startNanos), detail)
        )
        return response
    }

    private fun elapsedMs(startNanos: Long) = (System.nanoTime() - startNanos) / 1_000_000

    // Query parameters that may carry a credential are masked before they're stored.
    private fun HttpUrl.redacted(): String {
        val builder = newBuilder()
        queryParameterNames.filter { name -> SECRET_PARAMS.any { name.contains(it, ignoreCase = true) } }
            .forEach { builder.setQueryParameter(it, "***") }
        return builder.build().toString()
    }

    private companion object {
        const val MAX_BODY_BYTES = 16L * 1024
        val SECRET_PARAMS = listOf("key", "token", "secret", "password", "auth")
    }
}
