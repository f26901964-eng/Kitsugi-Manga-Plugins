package eu.kanade.tachiyomi.extension.tr.mangatr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import eu.kanade.tachiyomi.source.model.Page
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class MangaTRImagePipelineTest {

    @Test
    fun `image request retains the current full image fragment`() {
        val imageUrl = "https://image.mngtr.site/img_part.php?key=test#v4-300,1"
        val request = MangaTRImagePipeline.imageRequest(
            Page(0, imageUrl = imageUrl),
            Headers.Builder().build(),
            "https://manga-tr.com",
        )
        assertEquals("v4-300,1", request.url.fragment)
        assertEquals("key=test", request.url.query)
        assertEquals("no-cache", request.header("Cache-Control"))
    }

    @Test
    fun `current interceptor returns one bitmap with the original full dimensions`() {
        val corrected = decodeTransformed("v4-300,1")

        assertEquals(20, corrected.width)
        assertEquals(40, corrected.height)
        // Robolectric does not prove real Canvas pixels or orientation. The
        // production size contract is nevertheless one uncropped 20x40 image.
    }

    @Test
    fun `odd source height keeps the final row and original dimensions`() {
        val corrected = decodeTransformed("v4-1,0", sourceHeight = 41)

        assertEquals(20, corrected.width)
        assertEquals(41, corrected.height)
    }

    @Test(expected = IOException::class)
    fun `invalid current full image order never returns the scrambled source`() {
        decodeTransformed("v4-0,2")
    }

    @Test
    fun `legacy v3 requests still return their selected strip only`() {
        val first = decodeTransformed("v3-0-0,1")
        val second = decodeTransformed("v3-1-0,1")

        assertEquals(20, first.width)
        assertEquals(20, first.height)
        assertEquals(20, second.width)
        assertEquals(20, second.height)
    }

    @Test(expected = IOException::class)
    fun `invalid legacy slice never returns the combined source bitmap`() {
        decodeTransformed("v3-2-0,1")
    }

    private fun decodeTransformed(fragment: String, sourceHeight: Int = 40): Bitmap {
        val request = Request.Builder()
            .url("https://image.mngtr.site/img_part.php?key=test#$fragment")
            .build()
        val networkResponse = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .header("Content-Length", "999")
            .body(Buffer().write(sourceImage(sourceHeight)).asResponseBody("image/png".toMediaType()))
            .build()
        val chain = stubChain(request, networkResponse)

        val corrected = MangaTRImagePipeline.intercept(chain)
        assertEquals("image/jpeg", corrected.body.contentType()?.toString())
        assertNull(corrected.header("Content-Length"))
        return requireNotNull(
            corrected.use { BitmapFactory.decodeStream(it.body.byteStream()) },
        )
    }

    private fun sourceImage(height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(20, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) {
            for (x in 0 until 20) {
                bitmap.setPixel(x, y, if (y < height / 2) Color.RED else Color.BLUE)
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
}
