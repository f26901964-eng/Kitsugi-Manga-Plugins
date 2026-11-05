package eu.kanade.tachiyomi.extension.tr.mangatr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
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
        val fragment = imageUrl.substringAfter('#', "")
        return Request.Builder()
            .url(imageUrl)
            .headers(headers.newBuilder().mangaTrImageHeaders("$baseUrl/").build())
            .apply {
                if (
                    fragment.startsWith(MangaTRReaderContract.CACHE_VERSION_PREFIX) ||
                    fragment.startsWith(MangaTRReaderContract.LEGACY_SLICE_PREFIX)
                ) {
                    // Never let a cached v1/v2 bitmap bypass the current
                    // full-image (v4) or legacy-slice (v3) transformer.
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
        val strictTransform: Boolean
        when {
            fragment.startsWith(MangaTRReaderContract.CACHE_VERSION_PREFIX) -> {
                // Current contract: all strips become one full original image.
                selectedDestination = null
                orderText = fragment.removePrefix(MangaTRReaderContract.CACHE_VERSION_PREFIX)
                strictTransform = true
            }

            fragment.startsWith(MangaTRReaderContract.LEGACY_SLICE_PREFIX) -> {
                // v3 is accepted only for pages already opened by 1.6.31–1.6.33.
                val parts = fragment.removePrefix(MangaTRReaderContract.LEGACY_SLICE_PREFIX).split('-', limit = 2)
                if (parts.size != 2) throw IOException("Manga-TR slice fragment is malformed")
                selectedDestination = parts[0].toIntOrNull() ?: throw IOException("Manga-TR slice destination is malformed")
                orderText = parts[1]
                strictTransform = true
            }

            else -> {
                // v2 and unprefixed fragments reconstruct legacy reader pages.
                selectedDestination = null
                orderText = fragment.removePrefix("v2-")
                strictTransform = false
            }
        }
        val order = orderText
            .takeIf { it.isNotEmpty() && it.all { c -> c.isDigit() || c == ',' } }
            ?.split(',')
            ?.mapNotNull { it.toIntOrNull() }
            ?: return if (strictTransform) throw IOException("Manga-TR image order is malformed") else response
        if (order.isEmpty()) {
            if (strictTransform) throw IOException("Manga-TR image order is empty")
            return response
        }

        // Keep original bytes only for legacy fallback. Current v4/v3 failures
        // must never silently show a scrambled or incomplete source bitmap.
        val contentType = response.body.contentType()
        val imageBytes = response.use { it.body.bytes() }
        fun originalResponse(): Response {
            if (strictTransform) throw IOException("Manga-TR current image could not be decoded")
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

        fun stripTop(index: Int): Int = index * source.height / order.size

        fun stripBottom(index: Int): Int = if (index == order.lastIndex) {
            source.height
        } else {
            (index + 1) * source.height / order.size
        }

        fun stripHeight(index: Int): Int = stripBottom(index) - stripTop(index)

        // Preserve every source row, including the remainder when the source
        // height is not divisible by grid. Destination heights follow their
        // actual source strips instead of rounding every strip to one size.
        val destinationTops = IntArray(order.size)
        if (selectedDestination == null) {
            var nextTop = 0
            order.indices.forEach { destination ->
                val sourceIndex = order.indexOfFirst { it % 100 == destination }
                destinationTops[destination] = nextTop
                nextTop += stripHeight(sourceIndex)
            }
        }

        val selectedSourceIndex = selectedDestination?.let { destination ->
            order.indexOfFirst { it % 100 == destination }
        }
        val outputHeight = selectedSourceIndex?.let { stripHeight(it) } ?: source.height
        val output = Bitmap.createBitmap(source.width, outputHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val sourceIndexes = selectedSourceIndex?.let { listOf(it) } ?: order.indices.toList()

        sourceIndexes.forEach { sourceIndex ->
            val code = order[sourceIndex]
            val stripHeight = stripHeight(sourceIndex)
            val top = selectedDestination?.let { 0 } ?: destinationTops[code % 100]
            var strip = Bitmap.createBitmap(
                source,
                0,
                stripTop(sourceIndex),
                source.width,
                stripHeight,
            )
            val flipX = MangaTRReaderContract.flipHorizontally(code)
            val flipY = MangaTRReaderContract.flipVertically(code)
            if (flipX || flipY) {
                // Flip the isolated strip in local coordinates. Applying a
                // negative Canvas scale to a source Rect can flip the Rect
                // mapping as well and move content into the wrong strip.
                val matrix = Matrix().apply {
                    setScale(if (flipX) -1f else 1f, if (flipY) -1f else 1f)
                }
                val flipped = Bitmap.createBitmap(strip, 0, 0, strip.width, strip.height, matrix, true)
                if (flipped !== strip) strip.recycle()
                strip = flipped
            }
            canvas.drawBitmap(strip, 0f, top.toFloat(), null)
            if (strip !== source) strip.recycle()
        }
        source.recycle()

        val buffer = Buffer().apply {
            output.compress(Bitmap.CompressFormat.JPEG, 95, outputStream())
        }
        output.recycle()

        return response.newBuilder()
            .removeHeader("Content-Length")
            .removeHeader("Content-Encoding")
            .body(buffer.asResponseBody("image/jpeg".toMediaType()))
            .build()
    }
}
