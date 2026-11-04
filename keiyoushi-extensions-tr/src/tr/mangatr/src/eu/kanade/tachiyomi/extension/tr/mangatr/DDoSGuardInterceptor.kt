package eu.kanade.tachiyomi.extension.tr.mangatr

import eu.kanade.tachiyomi.network.GET
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.util.concurrent.TimeUnit

class DDoSGuardInterceptor(private val client: OkHttpClient) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        var response = chain.proceed(originalRequest)

        if (!isDdosChallenge(response)) {
            return response
        }

        response.close()

        // 1) Hızlı fallback: random __ddg2 cookie ekle (gallery-dl kanıtlı, tek başına yeterli)
        //    Bu, check.js'e gitmeden challenge'ı bypass eder.
        tryInjectRandomCookie(originalRequest.url.host)

        // 2) Resmi 2-istek flow: check.ddos-guard.net/check.js -> /.well-known/ddos-guard/id/...
        //    Başarılı olursa CookieJar'a doğru __ddg2/8/9/10 düşer.
        try {
            val checkJsBody = client.newCall(
                GET(WELL_KNOWN_URL, originalRequest.headers.newBuilder().set("Referer", originalRequest.url.toString()).build())
            ).execute().use { it.body.string() }

            val wellKnownPath = Regex("""['"](/.well-known/ddos-guard/[^'"]+)['"]""").find(checkJsBody)
                ?.groupValues?.get(1)

            if (!wellKnownPath.isNullOrBlank()) {
                val path = if (wellKnownPath.startsWith("/")) wellKnownPath else "/$wellKnownPath"
                val checkUrl = "${originalRequest.url.scheme}://${originalRequest.url.host}$path"
                // Referer ve Origin ekle, yoksa bazı Protection Rules blokluyor
                val checkHeaders = originalRequest.headers.newBuilder()
                    .set("Referer", originalRequest.url.toString())
                    .set("Origin", "${originalRequest.url.scheme}://${originalRequest.url.host}")
                    .build()
                client.newCall(GET(checkUrl, checkHeaders)).execute().use { r ->
                    // Başarılı clearance 200 dönmeli, body önemsiz
                    if (r.code != 200) {
                        // Retry with www subdomain if needed
                        val altHost = if (originalRequest.url.host.startsWith("www.")) originalRequest.url.host.removePrefix("www.") else "www.${originalRequest.url.host}"
                        val altUrl = "${originalRequest.url.scheme}://$altHost$path"
                        client.newCall(GET(altUrl, checkHeaders)).execute().close()
                    }
                }
            }
        } catch (_: Exception) {
            // network fail -> random cookie fallback zaten denendi, devam et
        }

        // Önceki istekten kalan __ddg* cookie'leri CookieJar'da biriktiyse retry başarılı olur
        // Referer'ı koruyarak tekrar dene
        val retryRequest = originalRequest.newBuilder()
            .header("Referer", originalRequest.url.toString())
            .build()
        return chain.proceed(retryRequest)
    }

    private fun isDdosChallenge(response: Response): Boolean {
        // 403 = kesin challenge
        if (response.code == 403) return true
        if (response.code == 429) return false // rate limit, değil DDoS-Guard challenge

        // 200 ama body içinde challenge sayfası varsa
        // Her başarılı response'da Server: ddos-guard var, o yüzden sadece header'a bakma!
        // Sadece küçük body + challenge marker varsa challenge say.
        try {
            val contentType = response.header("Content-Type") ?: ""
            // Sadece HTML challenge'ları
            if (!contentType.contains("text/html", true) && contentType.isNotBlank()) {
                // JSON/API response ise challenge değildir
                return false
            }
            // Body çok büyükse (normal manga listesi 100K+) kesinlikle challenge değil
            val bodyPeek = response.peekBody(8192).string()
            if (bodyPeek.contains("check.ddos-guard.net/check.js", true)) return true
            if (bodyPeek.contains("DDoS-Guard", true) && bodyPeek.length < 5000) {
                // Küçük challenge sayfası: "DDoS-Guard" + "Just a moment" + "Enable JavaScript"
                if (bodyPeek.contains("Just a moment", true) ||
                    bodyPeek.contains("DDoS protection by DDoS-Guard", true) ||
                    bodyPeek.contains("Please Wait", true) ||
                    bodyPeek.contains("ddg-captcha", true) ||
                    bodyPeek.contains("__ddg", true)
                ) return true
            }
            // Ek: 200 ama body tamamen boş ve ddos-guard header'ı var + content-length < 2K => challenge
            if (response.body.contentLength() in 0..3000 && response.header("Server")?.contains("ddos-guard", true) == true) {
                if (bodyPeek.contains("ddos-guard", true)) return true
            }
        } catch (_: Exception) {}
        return false
    }

    private fun tryInjectRandomCookie(host: String) {
        try {
            val url = "https://$host/".toHttpUrl()
            val existing = client.cookieJar.loadForRequest(url).any { it.name == "__ddg2" || it.name == "__ddg2_" }
            if (existing) return
            // gallery-dl kanıtlı: herhangi bir random değer yeterli
            val randomToken = generateRandomToken(16)
            val cookie = Cookie.Builder()
                .name("__ddg2")
                .value(randomToken)
                .domain(host.removePrefix("www."))
                .path("/")
                .expiresAt(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1))
                .build()
            // CookieJar'a elle eklemek için saveFromResponse simüle et
            // OkHttp'in default CookieJar'ı InMemory, saveFromResponse ile eklenir
            // En basit: request header'a ekle - ama CookieJar'a eklemek daha kalıcı
            try {
                client.cookieJar.saveFromResponse(url, listOf(cookie))
            } catch (_: Exception) {
                // fallback: www domain'e de ekle
                try {
                    val altCookie = cookie.newBuilder().domain(host).build()
                    client.cookieJar.saveFromResponse(url, listOf(altCookie))
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    private fun generateRandomToken(len: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (1..len).map { chars.random() }.joinToString("")
    }

    companion object {
        private const val WELL_KNOWN_URL = "https://check.ddos-guard.net/check.js"
    }
}
