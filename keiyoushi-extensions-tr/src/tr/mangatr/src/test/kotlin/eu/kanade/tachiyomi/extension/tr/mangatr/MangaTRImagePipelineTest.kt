package eu.kanade.tachiyomi.extension.tr.mangatr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import eu.kanade.tachiyomi.source.model.Page
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class MangaTRImagePipelineTest {

    private val source = TestMangaTR()

    @Test
    fun `image request retains the selected slice fragment`() {
        val imageUrl = "https://image.mngtr.site/img_part.php?key=test#v3-1-300,1"
        val request = source.requestFor(Page(0, imageUrl = imageUrl))
        assertEquals("v3-1-300,1", request.url.fragment)
        assertEquals("key=test", request.url.query)
    }

    @Test
    fun `interceptor returns two separate bitmaps instead of the combined source`() {
        val first = decodeSlice("v3-0-300,1")
        val second = decodeSlice("v3-1-300,1")

        assertEquals(20, first.width)
        assertEquals(20, first.height)
        assertEquals(20, second.width)
        assertEquals(20, second.height)

        val firstPixel = first.getPixel(10, 10)
        assertTrue(Color.red(firstPixel) > 180)
        assertTrue(Color.green(firstPixel) < 80)
        assertTrue(Color.blue(firstPixel) < 80)

        val secondPixel = second.getPixel(10, 10)
        assertTrue(Color.red(secondPixel) < 80)
        assertTrue(Color.green(secondPixel) < 80)
        assertTrue(Color.blue(secondPixel) > 180)
    }

    private fun decodeSlice(fragment: String): Bitmap {
        val request = Request.Builder()
            .url("https://image.mngtr.site/img_part.php?key=test#$fragment")
            .build()
        val networkResponse = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(Buffer().write(sourceImage()).asResponseBody("image/png".toMediaType()))
            .build()
        val chain = stubChain(request, networkResponse)

        val corrected = source.scrambledImageInterceptor(chain)
        assertEquals("image/jpeg", corrected.body.contentType()?.toString())
        return requireNotNull(
            corrected.use { BitmapFactory.decodeStream(it.body.byteStream()) },
        )
    }

    private fun sourceImage(): ByteArray {
        val bitmap = Bitmap.createBitmap(20, 40, Bitmap.Config.ARGB_8888)
        for (y in 0 until 40) {
            for (x in 0 until 20) {
                bitmap.setPixel(x, y, if (y < 20) Color.RED else Color.BLUE)
            }
        }
        val output = ByteArrayOutputStream()
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        bitmap.recycle()
        return output.toByteArray()
    }

    private fun stubChain(request: Request, response: Response): Interceptor.Chain = Proxy.newProxyInstance(
        Interceptor.Chain::class.java.classLoader,
        arrayOf(Interceptor.Chain::class.java),
    ) { proxy, method, arguments ->
        when (method.name) {
            "request" -> request
            "proceed" -> response
            "connection" -> null
            "connectTimeoutMillis", "readTimeoutMillis", "writeTimeoutMillis" -> 0
            "withConnectTimeout", "withReadTimeout", "withWriteTimeout" -> proxy
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === arguments?.get(0)
            "toString" -> "MangaTR test chain"
            else -> throw UnsupportedOperationException(method.name)
        }
    } as Interceptor.Chain

    private class TestMangaTR : MangaTR() {
        override val baseUrl: String = "https://manga-tr.com"
        override val lang: String = "tr"
        override val name: String = "Manga-TR test"

        fun requestFor(page: Page): Request = imageRequest(page)
    }
}
