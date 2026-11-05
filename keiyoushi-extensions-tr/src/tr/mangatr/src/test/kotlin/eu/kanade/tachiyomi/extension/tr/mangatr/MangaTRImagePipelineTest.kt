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
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
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
        // This test checks dimensions; the NATIVE graphics test below checks
        // production output pixels for order and all flip combinations.
    }

    @Test
    fun `native graphics verify order and every flip on production output pixels`() {
        val upright = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
        val cases = listOf(
            PixelCase(
                "horizontal",
                "v4-100,1",
                listOf(Color.GREEN, Color.RED, Color.YELLOW, Color.BLUE),
                upright,
            ),
            PixelCase(
                "vertical",
                "v4-200,1",
                listOf(Color.BLUE, Color.YELLOW, Color.RED, Color.GREEN),
                upright,
            ),
            PixelCase(
                "180 degree",
                "v4-300,1",
                listOf(Color.YELLOW, Color.BLUE, Color.GREEN, Color.RED),
                upright,
            ),
            PixelCase(
                "destination order and 180 degree",
                "v4-1,300",
                listOf(Color.YELLOW, Color.BLUE, Color.GREEN, Color.RED),
                upright,
            ),
        )

        cases.forEach { case ->
            val bitmap = decodeTransformed(case.fragment, labelled = true)
            assertEquals(20, bitmap.width)
            assertEquals(40, bitmap.height)
            assertQuadrants(bitmap, case.top, 0, "${case.name} top")
            assertQuadrants(bitmap, case.bottom, 20, "${case.name} bottom")
            bitmap.recycle()
        }
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

    private data class PixelCase(
        val name: String,
        val fragment: String,
        val top: List<Int>,
        val bottom: List<Int>,
    )

    private fun assertQuadrants(bitmap: Bitmap, expected: List<Int>, top: Int, label: String) {
        val coordinates = listOf(5 to top + 5, 15 to top + 5, 5 to top + 15, 15 to top + 15)
        coordinates.zip(expected).forEach { (coordinate, color) ->
            val actual = bitmap.getPixel(coordinate.first, coordinate.second)
            assertTrue(
                "$label pixel $coordinate expected ${Integer.toHexString(color)}, " +
                    "got ${Integer.toHexString(actual)}",
                colorDistance(actual, color) < 90,
            )
        }
    }

    private fun colorDistance(first: Int, second: Int): Int {
        return listOf(Color::red, Color::green, Color::blue).sumOf { channel ->
            val difference = channel(first) - channel(second)
            difference * difference
        }
    }

    private fun decodeTransformed(
        fragment: String,
        sourceHeight: Int = 40,
        labelled: Boolean = false,
    ): Bitmap {
        val request = Request.Builder()
            .url("https://image.mngtr.site/img_part.php?key=test#$fragment")
            .build()
        val networkResponse = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .header("Content-Length", "999")
            .body(
                Buffer()
                    .write(if (labelled) labelledSourceImage() else sourceImage(sourceHeight))
                    .asResponseBody("image/png".toMediaType()),
            )
            .build()
        val chain = stubChain(request, networkResponse)

        val corrected = MangaTRImagePipeline.intercept(chain)
        assertEquals("image/jpeg", corrected.body.contentType()?.toString())
        assertNull(corrected.header("Content-Length"))
        return requireNotNull(
            corrected.use { BitmapFactory.decodeStream(it.body.byteStream()) },
        )
    }

    private fun labelledSourceImage(): ByteArray {
        val bitmap = Bitmap.createBitmap(20, 40, Bitmap.Config.ARGB_8888)
        for (y in 0 until 40) {
            for (x in 0 until 20) {
                val right = x >= 10
                val bottom = y % 20 >= 10
                val color = when {
                    !right && !bottom -> Color.RED
                    right && !bottom -> Color.GREEN
                    !right && bottom -> Color.BLUE
                    else -> Color.YELLOW
                }
                bitmap.setPixel(x, y, color)
            }
        }
        val output = ByteArrayOutputStream()
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        bitmap.recycle()
        return output.toByteArray()
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
