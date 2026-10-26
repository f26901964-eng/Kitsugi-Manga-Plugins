package com.kitsugi.animelist.data.manga.golgebahcesi

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.kitsugi.animelist.data.manga.CaptchaRequiredException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Uses the site's own reader for accessible secure chapters; never unlocks paid chapters. */
internal class GolgeBahcesiReader(private val context: Context, private val userAgent: () -> String) {
    private val captureMutex = Mutex()
    private val cacheRoot = File(context.cacheDir, "golgebahcesi-reader-v1")

    fun pageUrl(readerUrl: String, index: Int): String = "https://$PAGE_HOST/${cacheKey(readerUrl)}/$index"

    suspend fun pages(readerUrl: String, expectedPageCount: Int? = null): List<File> = captureMutex.withLock {
        validateReaderUrl(readerUrl)
        withContext(Dispatchers.IO) { cached(readerUrl, expectedPageCount) }?.let { return@withLock it }
        val temporary = withContext(Dispatchers.IO) {
            cacheRoot.mkdirs()
            // A killed app process cannot run finally; remove its unfinished captures.
            cacheRoot.listFiles()?.filter { it.isDirectory && it.name.startsWith(".pending-") }
                ?.forEach { it.deleteRecursively() }
            File(cacheRoot, ".pending-${UUID.randomUUID()}").apply {
                if (!mkdir()) throw IOException("Okuyucu önbelleği oluşturulamadı")
            }
        }
        try {
            val files = capture(readerUrl, temporary, expectedPageCount)
            withContext(Dispatchers.IO) {
                File(temporary, "count").writeText(files.size.toString())
                val destination = File(cacheRoot, cacheKey(readerUrl))
                if (destination.exists()) destination.deleteRecursively()
                if (!temporary.renameTo(destination)) throw IOException("Okuyucu önbelleği kaydedilemedi")
                // Keep only a small number of recent chapters; the app has its own image cache.
                cacheRoot.listFiles()?.filter { it.isDirectory && it != destination }
                    ?.sortedByDescending { it.lastModified() }?.drop(KEEP_OTHER_CHAPTERS)
                    ?.forEach { it.deleteRecursively() }
                files.indices.map { File(destination, "$it.img") }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { temporary.deleteRecursively() }
        }
    }

    suspend fun openImage(readerUrl: String, index: Int, imageUrl: String): InputStream {
        if (imageUrl != pageUrl(readerUrl, index)) throw IOException("Geçersiz okuyucu sayfası")
        val files = pages(readerUrl) // Re-capture if Android evicted the temporary reader cache.
        return withContext(Dispatchers.IO) {
            files.getOrNull(index)?.inputStream() ?: throw IOException("Okuyucu sayfası bulunamadı")
        }
    }

    private fun cached(readerUrl: String, expectedPageCount: Int?): List<File>? {
        val directory = File(cacheRoot, cacheKey(readerUrl))
        val count = runCatching { File(directory, "count").readText().toInt() }.getOrNull() ?: return null
        if (count !in 1..MAX_PAGES || (expectedPageCount != null && count != expectedPageCount)) return null
        val pages = (0 until count).map { File(directory, "$it.img") }
        if (pages.any { !it.isFile || it.length() == 0L }) return null
        directory.setLastModified(System.currentTimeMillis())
        return pages
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun capture(readerUrl: String, directory: File, expectedPageCount: Int?): List<File> = coroutineScope {
        val result = CompletableDeferred<List<File>>()
        val count = AtomicInteger()
        val received = ConcurrentHashMap.newKeySet<Int>()
        val written = ConcurrentHashMap<Int, File>()
        val byteCount = AtomicLong()
        val bridge = "kitsugi" + UUID.randomUUID().toString().replace("-", "")
        val channel = "kitsugi" + UUID.randomUUID().toString().replace("-", "")
        val bootstrap = withContext(Dispatchers.IO) {
            fun asset(name: String): String = context.assets.open("golgebahcesi/$name").bufferedReader().use { it.readText() }
            val worker = "(${asset("worker_hook.js")})(${JSONObject.quote(channel)});\n"
            "(${asset("reader_capture.js")})(window.$bridge, ${JSONObject.quote(channel)}, ${JSONObject.quote(worker)});"
        }
        val writerJob = SupervisorJob(coroutineContext[kotlinx.coroutines.Job])
        val writerScope = CoroutineScope(coroutineContext + writerJob + Dispatchers.IO)
        var webView: WebView? = null
        try {
            withTimeoutOrNull(120_000) {
                withContext(Dispatchers.Main.immediate) {
                    if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) ||
                        !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
                    ) throw IOException("Android System WebView güncellenmeli (belge başlangıcı desteği gerekiyor)")
                    val view = WebView(context)
                    webView = view
                    view.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        userAgentString = userAgent()
                        allowFileAccess = false
                        allowContentAccess = false
                        mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    }
                    // A detached WebView still needs a viewport for the site's reader layout.
                    view.layout(0, 0, 1080, 1920)
                    view.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            if (!request.isForMainFrame) return false
                            val allowed = request.url.scheme == "https" && request.url.host == SITE_HOST
                            if (!allowed) result.completeExceptionally(IOException("Okuyucu dış siteye yönlendirildi"))
                            return !allowed
                        }

                        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                            if (Uri.parse(url).host != SITE_HOST) {
                                result.completeExceptionally(IOException("Okuyucu alan adı değişti"))
                            }
                        }

                        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                            if (request.isForMainFrame) result.completeExceptionally(IOException("Okuyucu yüklenemedi: ${error.errorCode}"))
                        }

                        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                            result.completeExceptionally(IOException("Android WebView okuyucu işlemi kapandı"))
                            return true
                        }
                    }
                    WebViewCompat.addWebMessageListener(view, bridge, setOf(ORIGIN)) { _, message, origin, mainFrame, _ ->
                        // No wildcard origin and no addJavascriptInterface fallback on untrusted frames.
                        if (!mainFrame || origin.scheme != "https" || origin.host != SITE_HOST ||
                            origin.port !in listOf(-1, 443) || result.isCompleted
                        ) return@addWebMessageListener
                        try {
                            val text = message.data ?: throw IOException("Boş okuyucu mesajı")
                            when (text.substringBefore(':')) {
                                "count" -> {
                                    val value = text.substringAfter(':').toInt()
                                    if (value !in 1..MAX_PAGES || (count.get() != 0 && count.get() != value) ||
                                        (expectedPageCount != null && expectedPageCount != value)
                                    ) {
                                        throw IOException("Geçersiz okuyucu sayfa sayısı")
                                    }
                                    count.set(value)
                                }
                                "page" -> {
                                    val value = text.substringAfter(':')
                                    val index = value.substringBefore(',').toInt()
                                    val data = value.substringAfter(',', "")
                                    if (index !in 0 until count.get() || !data.startsWith("data:") ||
                                        ";base64," !in data || data.length > MAX_PAGE_BASE64
                                    ) throw IOException("Geçersiz okuyucu görseli")
                                    if (!received.add(index)) return@addWebMessageListener
                                    writerScope.launch {
                                        try {
                                            val bytes = Base64.decode(data.substringAfter("base64,"), Base64.DEFAULT)
                                            if (bytes.isEmpty() || byteCount.addAndGet(bytes.size.toLong()) > MAX_CHAPTER_BYTES) {
                                                throw IOException("Okuyucu görsel boyutu sınırı aşıldı")
                                            }
                                            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                                            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                                                throw IOException("Okuyucu geçerli veya desteklenen bir görsel döndürmedi")
                                            }
                                            val file = File(directory, "$index.img")
                                            file.writeBytes(bytes)
                                            written[index] = file
                                            if (written.size == count.get()) result.complete((0 until count.get()).map { written.getValue(it) })
                                        } catch (e: Exception) {
                                            result.completeExceptionally(e)
                                        }
                                    }
                                }
                                "error" -> result.completeExceptionally(IOException(text.substringAfter(':')))
                                else -> throw IOException("Bilinmeyen okuyucu mesajı")
                            }
                        } catch (e: Exception) {
                            result.completeExceptionally(e)
                        }
                    }
                    WebViewCompat.addDocumentStartJavaScript(view, bootstrap, setOf(ORIGIN))
                    view.loadUrl(readerUrl)
                }
                result.await()
            } ?: throw CaptchaRequiredException("Gölge Bahçesi", "Okuyucu zaman aşımı; siteyi tarayıcıda açıp doğrulamayı tamamlayın")
        } finally {
            withContext(NonCancellable) { writerJob.cancelAndJoin() }
            // Cancellation and renderer failure must not leak Chromium instances.
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                webView?.let { view ->
                    // A dead renderer can throw here; cleanup must not hide the original error/cancellation.
                    runCatching { view.stopLoading() }
                    runCatching { WebViewCompat.removeWebMessageListener(view, bridge) }
                    runCatching { view.destroy() }
                }
            }
        }
    }

    private fun validateReaderUrl(url: String) {
        val uri = Uri.parse(url)
        if (uri.scheme != "https" || uri.host != SITE_HOST || uri.port !in listOf(-1, 443) ||
            uri.userInfo != null || !uri.path.orEmpty().matches(Regex("/manga/[^/]+/bolum/[^/]+"))
        ) throw IOException("Geçersiz güvenli okuyucu bağlantısı")
    }

    private fun cacheKey(url: String): String = MessageDigest.getInstance("SHA-256")
        .digest(url.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    companion object {
        const val PAGE_HOST = "golgebahcesi-pages.local"
        private const val SITE_HOST = "golgebahcesi.com"
        private const val ORIGIN = "https://golgebahcesi.com"
        private const val MAX_PAGES = 2000
        private const val MAX_PAGE_BASE64 = 32 * 1024 * 1024
        private const val MAX_CHAPTER_BYTES = 256L * 1024 * 1024
        private const val KEEP_OTHER_CHAPTERS = 2
    }
}
