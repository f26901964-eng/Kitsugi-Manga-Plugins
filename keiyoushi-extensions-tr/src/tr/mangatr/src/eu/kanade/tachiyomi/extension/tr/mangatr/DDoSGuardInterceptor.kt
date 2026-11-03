package eu.kanade.tachiyomi.extension.tr.mangatr

import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException

class DDoSGuardInterceptor(private val client: OkHttpClient) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val response = chain.proceed(originalRequest)
        // Ordinary 403s are not necessarily DDoS-Guard challenges. For successful
        // pages, a Server header alone also isn't enough (every page may have it).
        val challengeScript = response.peekBody(4096).string().contains("check.ddos-guard.net/check.js")
        val isChallenge = challengeScript ||
            (response.code == 403 && response.header("Server")?.contains("ddos-guard", ignoreCase = true) == true)
        if (!isChallenge) return response

        // Do not forward source cookies, authorization, or a source-specific Referer
        // to the third-party check host. Keep the UA consistent with the original request.
        val checkHeaders = Headers.Builder().apply {
            originalRequest.header("User-Agent")?.let { add("User-Agent", it) }
            originalRequest.header("Accept-Language")?.let { add("Accept-Language", it) }
        }.build()

        try {
            val path = client.newCall(GET(WELL_KNOWN_URL, checkHeaders)).execute().use {
                if (!it.isSuccessful) return response
                it.body.string().substringAfter("'", "").substringBefore("'", "")
            }
            // Only allow the relative path returned by the check script on the same origin.
            if (!path.startsWith('/') || path.startsWith("//")) return response
            val checkUrl = originalRequest.url.resolve(path) ?: return response
            if (checkUrl.scheme != originalRequest.url.scheme || checkUrl.host != originalRequest.url.host || checkUrl.port != originalRequest.url.port) {
                return response
            }
            val clearanceOk = client.newCall(GET(checkUrl.toString(), checkHeaders)).execute().use { it.isSuccessful }
            if (!clearanceOk) return response
        } catch (_: IOException) {
            // Preserve the original response, rather than masking it with a check-host failure.
            return response
        }

        response.close()
        return chain.proceed(originalRequest)
    }

    companion object {
        private const val WELL_KNOWN_URL = "https://check.ddos-guard.net/check.js"
    }
}
