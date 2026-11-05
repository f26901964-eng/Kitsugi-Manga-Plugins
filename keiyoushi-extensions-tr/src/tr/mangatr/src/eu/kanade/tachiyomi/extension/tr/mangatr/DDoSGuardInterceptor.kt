package eu.kanade.tachiyomi.extension.tr.mangatr

import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

class DDoSGuardInterceptor(private val client: OkHttpClient) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val response = chain.proceed(originalRequest)
        if (!isDdosChallenge(response)) return response

        // Keep a useful response if the clearance service is unavailable. Challenge
        // pages are small; normal API and manga responses are never buffered here.
        val contentType = response.body.contentType()
        val challengeBody = response.body.bytes()
        response.close()

        val cleared = runCatching { clearDdosGuard(originalRequest) }.getOrDefault(false)
        if (cleared) {
            // In particular, do not rebuild a POST or replace its detail-page Referer
            // with the API URL. CookieJar supplies the newly cleared cookies.
            return chain.proceed(originalRequest)
        }

        return response.newBuilder()
            .body(challengeBody.toResponseBody(contentType))
            .build()
    }

    private fun clearDdosGuard(originalRequest: okhttp3.Request): Boolean {
        val checkHeaders = Headers.Builder()
            .set("User-Agent", originalRequest.header("User-Agent") ?: DEFAULT_USER_AGENT)
            .set("Accept", "*/*")
            .build()
        val checkJsBody = client.newCall(GET(WELL_KNOWN_URL, checkHeaders)).execute().use { response ->
            if (!response.isSuccessful) return false
            response.body.string()
        }
        val wellKnownPath = WELL_KNOWN_PATH_REGEX.find(checkJsBody)?.groupValues?.get(1)
            ?.takeIf { it.startsWith("/") && !it.startsWith("//") }
            ?: return false
        val checkUrl = originalRequest.url.resolve(wellKnownPath)
            ?.takeIf {
                it.scheme == originalRequest.url.scheme &&
                    it.host == originalRequest.url.host &&
                    it.port == originalRequest.url.port
            }
            ?: return false

        // Do not forward source cookies, authorization, POST content headers, or
        // AJAX navigation state to the third-party check host.
        val gateHeaders = originalRequest.headers.newBuilder()
            .removeAll("Cookie")
            .removeAll("Authorization")
            .removeAll("Content-Type")
            .removeAll("Content-Length")
            .removeAll("Origin")
            .removeAll("Sec-Fetch-User")
            .removeAll("Upgrade-Insecure-Requests")
            .set("Accept", "*/*")
            .set("Referer", originalRequest.header("Referer") ?: "${originalRequest.url.scheme}://${originalRequest.url.host}/")
            .set("Sec-Fetch-Dest", "empty")
            .set("Sec-Fetch-Mode", "cors")
            .set("Sec-Fetch-Site", "same-origin")
            .build()
        return client.newCall(GET(checkUrl, gateHeaders)).execute().use { it.isSuccessful }
    }

    private fun isDdosChallenge(response: Response): Boolean {
        if (response.code == 429) return false
        val contentType = response.header("Content-Type").orEmpty()
        if (contentType.isNotEmpty() && !contentType.contains("text/html", ignoreCase = true)) return false

        val body = runCatching { response.peekBody(CHALLENGE_PEEK_BYTES).string() }.getOrDefault("")
        if (body.contains("check.ddos-guard.net/check.js", ignoreCase = true)) return true
        if (response.code != 403) return false

        // A signed endpoint can reject a request with an ordinary empty 403.
        // That is not a DDoS challenge and must not trigger a cookie exchange.
        return body.length < CHALLENGE_PEEK_BYTES &&
            body.contains("DDoS-Guard", ignoreCase = true) &&
            (
                body.contains("Just a moment", ignoreCase = true) ||
                    body.contains("DDoS protection by DDoS-Guard", ignoreCase = true) ||
                    body.contains("Please Wait", ignoreCase = true) ||
                    body.contains("ddg-captcha", ignoreCase = true) ||
                    body.contains("__ddg", ignoreCase = true)
                )
    }

    companion object {
        private const val WELL_KNOWN_URL = "https://check.ddos-guard.net/check.js"
        private const val CHALLENGE_PEEK_BYTES = 8192L
        private const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/124.0.0.0 Mobile Safari/537.36"
        private val WELL_KNOWN_PATH_REGEX = Regex("""['"](/.well-known/ddos-guard/[^'"]+)['"]""")
    }
}
