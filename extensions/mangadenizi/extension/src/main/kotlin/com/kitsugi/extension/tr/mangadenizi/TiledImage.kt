package com.kitsugi.extension.tr.mangadenizi

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.coroutines.CoroutineContext

internal object TiledImage {
    // Prevent unbounded allocations from an invalid/hostile image response.
    private const val MAX_PIXELS = 32_000_000L

    fun restore(bytes: ByteArray, grid: Int?, seed: Long?, coroutine: CoroutineContext): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth.toLong() * bounds.outHeight > MAX_PIXELS) {
            throw IOException("MangaDenizi geçerli veya güvenli boyutta bir görsel döndürmedi")
        }
        coroutine.ensureActive()
        val input = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
            ?: throw IOException("MangaDenizi görseli çözülemedi")
        var output: Bitmap? = null
        try {
            if (grid == null && seed == null) return bytes // Full decoder validation above, not just header sniffing.
            if (grid == null || seed == null) throw IOException("Eksik görsel düzeni bilgisi")
            val restored = Bitmap.createBitmap(input.width, input.height, Bitmap.Config.ARGB_8888)
            output = restored
            val canvas = Canvas(restored)
            for (tile in TileLayout.restore(input.width, input.height, grid, seed)) {
                coroutine.ensureActive()
                canvas.drawBitmap(input, Rect(tile.sx, tile.sy, tile.sx + tile.width, tile.sy + tile.height), Rect(tile.dx, tile.dy, tile.dx + tile.width, tile.dy + tile.height), null)
            }
            coroutine.ensureActive()
            val buffer = ByteArrayOutputStream()
            if (!restored.compress(Bitmap.CompressFormat.PNG, 100, buffer)) throw IOException("Görsel birleştirilemedi")
            coroutine.ensureActive()
            return buffer.toByteArray()
        } finally {
            input.recycle()
            output?.recycle()
        }
    }
}
