package com.tanveer.aiva.data.remote

import com.tanveer.aiva.data.remote.RetryPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

internal data class HttpResult(val connection: HttpURLConnection, val code: Int)

/**
 * Small HTTPS client. Redirects are refused so an API key is never forwarded.
 * Response bodies are capped. Authorization headers are never included in thrown messages.
 */
internal object Net {
    const val CONNECT_MS = 15_000
    const val READ_MS = 90_000

    fun open(
        url: String,
        method: String,
        body: ByteArray?,
        headers: Map<String, String>,
        readTimeoutMs: Int = READ_MS,
    ): HttpURLConnection {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            instanceFollowRedirects = false
            connectTimeout = CONNECT_MS
            readTimeout = readTimeoutMs
            setRequestProperty("Accept", "application/json, text/event-stream, */*")
            setRequestProperty("User-Agent", "AIVA-Android")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        if (body != null) {
            conn.outputStream.use { it.write(body) }
        }
        return conn
    }

    fun requireSuccess(conn: HttpURLConnection): Int {
        val code = conn.responseCode
        if (code in 300..399) {
            conn.disconnect()
            throw httpFailure(code, "Redirect refused.", conn.getHeaderField("Retry-After"))
        }
        if (code !in 200..299) {
            val detail = readCapped(conn.errorStream, 8_192)
            val failure = httpFailure(code, redactSecrets(detail), conn.getHeaderField("Retry-After"))
            conn.disconnect()
            throw failure
        }
        return code
    }

    fun readCapped(stream: InputStream?, limit: Int): String {
        if (stream == null) return ""
        return stream.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(4_096)
            var total = 0
            while (total < limit) {
                val n = input.read(buf, 0, minOf(buf.size, limit - total))
                if (n < 0) break
                out.write(buf, 0, n)
                total += n
            }
            out.toString(Charsets.UTF_8.name())
        }
    }

    fun isEventStream(conn: HttpURLConnection): Boolean =
        conn.contentType.orEmpty().contains("text/event-stream", ignoreCase = true)
}

internal suspend fun <T> callWithRetry(block: suspend () -> T): T {
    var attempt = 0
    var last: Exception? = null
    while (attempt < RetryPolicy.MAX_ATTEMPTS) {
        currentCoroutineContext().ensureActive()
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProviderException) {
            if (!RetryPolicy.retryable(e.httpCode, emitted = false) || attempt == RetryPolicy.MAX_ATTEMPTS - 1) throw e
            last = e
            delay(RetryPolicy.delayMs(attempt, e.retryAfterSeconds))
        } catch (e: IOException) {
            if (attempt == RetryPolicy.MAX_ATTEMPTS - 1) {
                throw ProviderException(null, "Couldn’t reach the provider. Check your connection and try again.")
            }
            last = e
            delay(RetryPolicy.delayMs(attempt, null))
        }
        attempt++
    }
    throw last ?: ProviderException(null, "Couldn’t reach the provider.")
}
