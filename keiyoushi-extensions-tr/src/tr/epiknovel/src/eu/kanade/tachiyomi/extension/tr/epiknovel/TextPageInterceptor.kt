package eu.kanade.tachiyomi.extension.tr.epiknovel

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.Jsoup
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Epik Novel metin bölümlerini görüntü okuyucu için PNG sayfalara çevirir.
 *
 * Kitsugi/Keiyoushi okuyucusu görsel sayfa akışı bekler; Epik Novel bölümleri
 * ise düz metindir. `getPageList` bu sınıfın ürettiği sanal sayfa adreslerini
 * (`/__epiknovel_textpage?chapter=…&page=N`) döndürür; `imageRequest` ile aynı
 * adres istendiğinde buradaki interceptor bölümü yeniden indirip metni
 * 1080×1920 PNG'ye çizer ve uygulama katmanı hiç değişmeden okuyucu çalışır.
 *
 * Bölüm içeriği ve sayfa sınırları [EpikNovelContent] ile saf ve
 * deterministiktir; uygulama diske PNG önbelleklediğinden yeniden çizim
 * yalnız ilk açılışta olur.
 */
internal class TextPageInterceptor(private val baseUrl: String) : Interceptor {

    companion object {
        /** Sanal metin sayfası yolu (gerçek site isteği asla bu yola düşmez). */
        const val PATH = "/__epiknovel_textpage"

        private const val PAGE_WIDTH = 1080
        private const val PAGE_HEIGHT = 1920
        private const val PADDING_X = 64
        private const val PADDING_Y = 72
        private const val MAX_IMAGE_HEIGHT = 1300
        private const val PARAGRAPH_GAP = 20f
        private const val MAX_CACHED_CHAPTERS = 4

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        /** Belirli bir bölümün N. metin sayfasının sanal görüntü adresi. */
        fun pageUrl(baseUrl: String, chapterUrl: String, page: Int): String =
            baseUrl.trimEnd('/').toHttpUrl().newBuilder()
                .addPathSegment(PATH.removePrefix("/"))
                .addQueryParameter("chapter", chapterUrl)
                .addQueryParameter("page", page.toString())
                .build()
                .toString()
    }

    private data class CachedChapter(
        val blocks: List<ContentBlock>,
        val pages: List<List<ContentBlock>>,
    )

    // Bölüm HTML'i + sayfa sınırları; PNG'ler uygulama diskinde önbelleklenir.
    private val chapterCache = object : LinkedHashMap<String, CachedChapter>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedChapter>): Boolean =
            size > MAX_CACHED_CHAPTERS
    }
    private val cacheLock = Any()

    // Kategori istekleri CF/Compression korumalıdır; içerik indirme kendi
    // basit istemcisiyle interceptor zincirine yeniden girmeyi engeller.
    private val fetchClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        if (url.encodedPath != PATH) return chain.proceed(request)

        val chapterUrl = url.queryParameter("chapter")?.takeIf { it.startsWith("http") }
            ?: return errorResponse(request, "missing chapter", "chapter parametresi eksik")
        val pageIndex = url.queryParameter("page")?.toIntOrNull()?.takeIf { it >= 0 }
            ?: return errorResponse(request, "missing page", "page parametresi eksik")

        val cached = load(chapterUrl)
            ?: return errorResponse(request, "chapter unreadable", "bölüm metni okunamadı")
        val blocks = cached.pages.getOrNull(pageIndex)
            ?: cached.pages.lastOrNull()
            ?: return errorResponse(request, "empty page", "bölüm sayfası boş")

        val png = render(cached, blocks, pageIndex)
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .header("Content-Type", "image/png")
            .body(png.toResponseBody("image/png".toMediaType()))
            .build()
    }

    private fun load(chapterUrl: String): CachedChapter? {
        synchronized(cacheLock) { chapterCache[chapterUrl] }?.let { return it }

        val content = fetchChapter(chapterUrl) ?: return null
        val pages = EpikNovelContent.paginate(content.blocks)
        if (pages.isEmpty()) return null

        val cached = CachedChapter(content.blocks, pages)
        synchronized(cacheLock) { chapterCache[chapterUrl] = cached }
        return cached
    }

    private fun fetchChapter(chapterUrl: String): ChapterContent? {
        val request = Request.Builder()
            .url(chapterUrl)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "tr-TR,tr;q=0.9")
            .build()
        fetchClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val document = Jsoup.parse(response.body.byteStream(), null, chapterUrl)
            return EpikNovelParser.chapterContent(document).takeIf { !it.isEmpty }
        }
    }

    private fun errorResponse(request: Request, reason: String, detail: String): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(404)
            .message(reason)
            .body(detail.toResponseBody("text/plain; charset=utf-8".toMediaType()))
            .build()

    // ------------------------------------------------------------ render

    private fun render(cached: CachedChapter, blocks: List<ContentBlock>, pageIndex: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(PAGE_WIDTH, PAGE_HEIGHT, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)

            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(24, 24, 24)
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            }
            val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(32, 32, 32)
                typeface = Typeface.SANS_SERIF
            }
            val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(200, 200, 200)
                strokeWidth = 3f
            }

            val chapterTitle = cached.blocks.filterIsInstance<ContentBlock.Title>().firstOrNull()?.text
            val contentWidth = PAGE_WIDTH - 2 * PADDING_X
            val bottomLimit = PAGE_HEIGHT - PADDING_Y
            var y = PADDING_Y

            // Sayfa başlığı: bölüm adı + sayfa numarası (her sayfada okuma yönü için).
            val header = buildString {
                if (!chapterTitle.isNullOrBlank()) append(chapterTitle)
                if (cached.pages.size > 1) {
                    if (isNotEmpty()) append("  ·  ")
                    append("${pageIndex + 1}/${cached.pages.size}")
                }
            }
            if (header.isNotEmpty()) {
                titlePaint.textSize = 34f
                val headerLayout = layoutFor(header, titlePaint, contentWidth, 1.1f)
                canvas.save()
                canvas.translate(PADDING_X.toFloat(), y.toFloat())
                headerLayout.draw(canvas)
                canvas.restore()
                y += headerLayout.height + 14
                canvas.drawLine(
                    PADDING_X.toFloat(),
                    y.toFloat(),
                    (PAGE_WIDTH - PADDING_X).toFloat(),
                    y.toFloat(),
                    dividerPaint,
                )
                y += 26
            }

            for (block in blocks) {
                if (y >= bottomLimit) break
                when (block) {
                    // Bölüm başlığı üstbilgide çizildi; tekrarı önlenir.
                    is ContentBlock.Title -> Unit
                    is ContentBlock.Paragraph -> {
                        val layout = paragraphLayout(block.text, bodyPaint, contentWidth, bottomLimit - y)
                            ?: continue
                        canvas.save()
                        canvas.translate(PADDING_X.toFloat(), y.toFloat())
                        layout.draw(canvas)
                        canvas.restore()
                        y += layout.height + PARAGRAPH_GAP
                    }
                    is ContentBlock.Picture -> {
                        val image = loadImage(block.url) ?: continue
                        val scale = minOf(
                            contentWidth.toFloat() / image.width,
                            MAX_IMAGE_HEIGHT.toFloat() / image.height,
                        )
                        val targetW = (image.width * scale).toInt().coerceAtLeast(1)
                        val targetH = (image.height * scale).toInt().coerceAtLeast(1)
                        if (y + targetH > bottomLimit) {
                            image.recycle()
                            break
                        }
                        val left = (PAGE_WIDTH - targetW) / 2f
                        canvas.drawBitmap(
                            image,
                            null,
                            RectF(left, y.toFloat(), left + targetW, y + targetH),
                            null,
                        )
                        image.recycle()
                        y += targetH + 24f
                    }
                }
            }

            val out = java.io.ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            return out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    /** Metni sınıra sığdırmak için yazı boyutunu kademeli küçültür. */
    private fun paragraphLayout(text: String, paint: Paint, width: Int, available: Int): StaticLayout? {
        if (available <= 0) return null
        for (size in floatArrayOf(38f, 34f, 30f, 26f, 22f, 18f, 14f)) {
            paint.textSize = size
            val layout = layoutFor(text, paint, width, 1.3f)
            if (layout.height <= available) return layout
        }
        paint.textSize = 14f
        return layoutFor(text, paint, width, 1.3f)
    }

    private fun layoutFor(text: String, paint: Paint, width: Int, spacing: Float): StaticLayout =
        StaticLayout(text, paint, width, Layout.Alignment.ALIGN_NORMAL, spacing, 0f, false)

    private fun loadImage(url: String): Bitmap? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "$baseUrl/")
            .build()
        return try {
            fetchClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val bytes = response.body.bytes()
                if (bytes.isEmpty()) return null
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        } catch (_: IOException) {
            null
        }
    }
}
