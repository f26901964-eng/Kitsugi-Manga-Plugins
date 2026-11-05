package eu.kanade.tachiyomi.extension.tr.mangatr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import eu.kanade.tachiyomi.source.model.Page
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import java.io.IOException

/** Android request/bitmap path kept independent from HttpSource so it can be tested directly. */
internal object MangaTRImagePipeline {
    fun imageRequest(page: Page, headers: Headers, baseUrl: String): Request {
        val imageUrl = requireNotNull(page.imageUrl) { "Manga-TR page has no image URL" }
        return Request.Builder()
            .url(imageUrl)
            .headers(headers.newBuilder().mangaTrImageHeaders("$baseUrl/").build())
            .apply {
                if (imageUrl.substringAfter('#', "").startsWith(MangaTRReaderContract.CACHE_VERSION_PREFIX)) {
                    // Never let a cached combined v1/v2 bitmap bypass the v3
                    // crop interceptor. Corrected slices can still be cached
                    // by the reader after this request has been transformed.
                    cacheControl(CacheControl.FORCE_NETWORK)
                }
            }
            .build()
    }

    fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (!response.isSuccessful) return response
        val fragment = request.url.fragment ?: return response
        val selectedDestination: Int?
        val orderText: String
        if (fragment.startsWith(MangaTRReaderContract.CACHE_VERSION_PREFIX)) {
            val parts = fragment.removePrefix(MangaTRReaderContract.CACHE_VERSION_PREFIX).split('-', limit = 2)
            if (parts.size != 2) throw IOException("Manga-TR slice fragment is malformed")
            selectedDestination = parts[0].toIntOrNull() ?: throw IOException("Manga-TR slice destination is malformed")
            orderText = parts[1]
        } else {
            // v2 and unprefixed fragments reconstruct one combined bitmap for
            // older reader sessions and legacy encrypted order attributes.
            selectedDestination = null
            orderText = fragment.removePrefix("v2-")
        }
        val order = orderText
            .takeIf { it.isNotEmpty() && it.all { c -> c.isDigit() || c == ',' } }
            ?.split(',')
            ?.mapNotNull { it.toIntOrNull() }
            ?: return if (selectedDestination == null) response else throw IOException("Manga-TR slice order is malformed")
        if (order.isEmpty()) {
            if (selectedDestination != null) throw IOException("Manga-TR slice order is empty")
            return response
        }

        // Keep the original bytes so every failure path can return a readable response.
        val contentType = response.body.contentType()
        val imageBytes = response.use { it.body.bytes() }
        fun originalResponse(): Response {
            if (selectedDestination != null) {
                throw IOException("Manga-TR selected image slice could not be decoded")
            }
            return response.newBuilder()
                .body(Buffer().write(imageBytes).asResponseBody(contentType))
                .build()
        }

        val source = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            ?: return originalResponse()
        if (source.width <= 0 || source.height <= 0) {
            source.recycle()
            return originalResponse()
        }

        if (
            order.size > source.height ||
            order.any { it / 100 !in 0..3 || it % 100 !in order.indices } ||
            order.map { it % 100 }.distinct().size != order.size ||
            (selectedDestination != null && selectedDestination !in order.indices)
        ) {
            source.recycle()
            return originalResponse()
        }
        val stripHeight = source.height / order.size
        if (stripHeight <= 0) {
            source.recycle()
            return originalResponse()
        }
        val outputHeight = if (selectedDestination == null) stripHeight * order.size else stripHeight
        val output = Bitmap.createBitmap(source.width, outputHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val sourceIndexes = if (selectedDestination == null) {
            order.indices.toList()
        } else {
            listOf(order.indexOfFirst { it % 100 == selectedDestination })
        }

        sourceIndexes.forEach { sourceIndex ->
            val code = order[sourceIndex]
            val src = Rect(0, sourceIndex * stripHeight, source.width, (sourceIndex + 1) * stripHeight)
            val top = if (selectedDestination == null) (code % 100) * stripHeight else 0
            val dst = Rect(0, top, source.width, top + stripHeight)

            canvas.save()
            canvas.scale(
                if (MangaTRReaderContract.flipHorizontally(code)) -1f else 1f,
                if (MangaTRReaderContract.flipVertically(code)) -1f else 1f,
                source.width / 2f,
                top + stripHeight / 2f,
            )
            canvas.drawBitmap(source, src, dst, null)
            canvas.restore()
        }
        source.recycle()

        val buffer = Buffer().apply {
            output.compress(Bitmap.CompressFormat.JPEG, 95, outputStream())
        }
        output.recycle()

        return response.newBuilder()
            .body(buffer.asResponseBody("image/jpeg".toMediaType()))
            .build()
    }
}
