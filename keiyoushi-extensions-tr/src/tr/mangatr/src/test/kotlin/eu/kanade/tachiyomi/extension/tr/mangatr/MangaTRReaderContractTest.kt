package eu.kanade.tachiyomi.extension.tr.mangatr

import keiyoushi.utils.toJsonRequestBody
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.Buffer
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class MangaTRReaderContractTest {
    @Test
    fun `xorshift shuffle and flips match cap js vectors`() {
        assertCodes(seed = 1, grid = 2, expected = listOf(100, 101))
        assertCodes(seed = 2, grid = 2, expected = listOf(201, 200))
        assertCodes(seed = 123456789, grid = 4, expected = listOf(2, 203, 0, 201))
        assertCodes(seed = 4294967295, grid = 5, expected = listOf(101, 2, 0, 304, 203))

        // This vector catches implementations that rotate every strip together.
        assertCodes(seed = 305419896, grid = 2, expected = listOf(300, 1))
    }

    @Test
    fun `zero uses the site default seed`() {
        assertCodes(seed = 0, grid = 3, expected = listOf(101, 0, 202))
        assertEquals(
            MangaTRReaderContract.scrambleCodes(123456789, 3),
            MangaTRReaderContract.scrambleCodes(0, 3),
        )
    }

    @Test
    fun `labelled strip distinguishes upright horizontal vertical and 180 degree flips`() {
        val upright = listOf(listOf("A", "B"), listOf("C", "D"))
        val expected = mapOf(
            0 to upright,
            100 to listOf(listOf("B", "A"), listOf("D", "C")),
            200 to listOf(listOf("C", "D"), listOf("A", "B")),
            300 to listOf(listOf("D", "C"), listOf("B", "A")),
        )

        expected.forEach { (code, transformed) ->
            val actual = (0..1).map { row ->
                (0..1).map { column ->
                    val sourceRow = if (MangaTRReaderContract.flipVertically(code)) 1 - row else row
                    val sourceColumn = if (MangaTRReaderContract.flipHorizontally(code)) 1 - column else column
                    upright[sourceRow][sourceColumn]
                }
            }
            assertEquals("Wrong orientation for code $code", transformed, actual)
        }
    }

    @Test
    fun `page seed selection follows cap js fallback order`() {
        val seeds = mapOf("0" to 11L, "1" to 22L, "7" to 33L)
        assertEquals(22L, MangaTRReaderContract.seedForPage(seeds, "1"))
        assertEquals(33L, MangaTRReaderContract.seedForPage(seeds, "7"))
        assertEquals(11L, MangaTRReaderContract.seedForPage(seeds, "99"))
        assertEquals(0L, MangaTRReaderContract.seedForPage(emptyMap(), "99"))
    }

    @Test
    fun `current config keeps seeds and grid while legacy order stays optional`() {
        val current = json.decodeFromString<ReaderConfigDto>(
            """{"cls":"reader","data":{"parts":"data-payload","pageIndex":"data-page"},"seeds":{"0":305419896,"1":2},"grid":2}""",
        )
        assertEquals(2, current.grid)
        assertEquals(mapOf("0" to 305419896L, "1" to 2L), current.seeds)
        assertNull(current.data.order)

        val legacy = json.decodeFromString<ReaderConfigDto>(
            """{"data":{"parts":"data-payload","order":"data-order","pageIndex":"data-page"}}""",
        )
        assertTrue(legacy.seeds.isEmpty())
        assertEquals(2, legacy.grid)
        assertEquals("data-order", legacy.data.order)
    }

    @Test
    fun `array seeds use their numeric page positions`() {
        val config = json.decodeFromString<ReaderConfigDto>(
            """{"data":{"parts":"data-payload","pageIndex":"data-page"},"seeds":[305419896,2,123456789],"grid":2}""",
        )
        assertEquals(mapOf("0" to 305419896L, "1" to 2L, "2" to 123456789L), config.seeds)
        assertEquals(2L, MangaTRReaderContract.seedForPage(config.seeds, "1"))
        assertEquals(305419896L, MangaTRReaderContract.seedForPage(config.seeds, "99"))
    }

    @Test
    fun `gate request serializes the nonce and canvas fingerprint fields`() {
        val body = ReaderGateRequestDto("test-nonce", "1,2,3|4,5,6").toJsonRequestBody()
        val buffer = Buffer()
        body.writeTo(buffer)
        assertEquals("""{"n":"test-nonce","cv":"1,2,3|4,5,6"}""", buffer.readUtf8())
    }

    @Test
    fun `cache version survives in the image fragment without changing the request target`() {
        val url = "https://image.mngtr.site/img_part.php?key=test&p=1".toHttpUrl().newBuilder()
            .fragment(MangaTRReaderContract.CACHE_VERSION_PREFIX + "0-100,101")
            .build()
        assertEquals("v3-0-100,101", url.fragment)
        assertEquals("key=test&p=1", url.query)
    }

    @Test
    fun `canvas fingerprint matches the two integer readback positions`() {
        assertEquals("57,102,149|101,169,251", MangaTRReaderContract.canvasFingerprint("0001000200030004"))
        assertEquals("40,41,210|127,121,220", MangaTRReaderContract.canvasFingerprint("1234abcd55aa0000"))
        assertEquals("215,155,25|255,230,40", MangaTRReaderContract.canvasFingerprint("ffffffffffffffff"))
        assertEquals("20,20,20|30,30,30", MangaTRReaderContract.canvasFingerprint("0000000000000000"))
        assertEquals("", MangaTRReaderContract.canvasFingerprint(""))
    }

    @Test
    fun `pointer delta matches the public cap js checksum vectors`() {
        assertEquals("82f9678b", MangaTRReaderContract.pointerDelta("0123456789abcdef"))
        assertEquals("0cd07291", MangaTRReaderContract.pointerDelta("abcdef0123456789", "cubic", "ltr"))
        assertEquals("48f7c986", MangaTRReaderContract.pointerDelta("0123456789abcdef0123456789abcdef", "ease-out", "smooth"))
        assertEquals("", MangaTRReaderContract.pointerDelta(""))
    }

    @Test
    fun `config accepts current empty pointer and legacy gate keys`() {
        val plainConfig = """{"data":{"parts":"data-payload","pageIndex":"data-page"},"seeds":{"0":2},"grid":2}"""
        for (gateSegment in listOf("gate|test-gate||reader", "gate|test-gate|reader")) {
            val encrypted = encrypt(
                encrypt(plainConfig, "boot|$PAGE_KEY|reader"),
                gateSegment,
            )
            val config = MangaTRReaderContract.decryptConfig(encrypted, "test-gate", PAGE_KEY)
            assertEquals(2, config?.grid)
            assertEquals(mapOf("0" to 2L), config?.seeds)
        }

        val pointer = MangaTRReaderContract.pointerDelta("0123456789abcdef")
        val encrypted = encrypt(
            encrypt(plainConfig, "boot|$PAGE_KEY|reader"),
            "gate|test-gate|$pointer|reader",
        )
        val config = MangaTRReaderContract.decryptConfig(encrypted, "test-gate", PAGE_KEY, pointer)
        assertEquals(mapOf("0" to 2L), config?.seeds)
    }

    @Test
    fun `configured pages expose one corrected image per generated slice`() {
        fun payload(page: Int) = encrypt(
            """["https://image.mngtr.site/img_part.php?key=test&p=$page"]""",
            "attr|$PAGE_KEY|reader",
        )
        val document = Jsoup.parse(
            """
            <div data-page="0" data-payload="${payload(0)}" data-order="${encrypt("[300,300]", "order|$PAGE_KEY|reader")}"></div>
            <div data-page="1" data-payload="${payload(1)}" data-order="${encrypt("[300,300]", "order|$PAGE_KEY|reader")}"></div>
            """.trimIndent(),
        )
        val config = ReaderConfigDto(
            data = ReaderAttributesDto(
                parts = "data-payload",
                pageIndex = "data-page",
                order = "data-order",
            ),
            seeds = mapOf("0" to 305419896L, "1" to 2L),
            grid = 2,
        )

        val pages = requireNotNull(MangaTRReaderContract.parseConfiguredPages(document, config, PAGE_KEY))
        assertEquals(4, pages.size)
        assertEquals(listOf(0, 1, 2, 3), pages.map { it.index })
        assertEquals(
            listOf("v3-0-300,1", "v3-1-300,1", "v3-0-201,200", "v3-1-201,200"),
            pages.map { it.imageUrl?.toHttpUrl()?.fragment },
        )
        assertEquals(2, pages.map { it.imageUrl?.toHttpUrl()?.newBuilder()?.fragment(null)?.build() }.distinct().size)
        assertTrue(pages.none { it.imageUrl.orEmpty().contains("300,300") })
    }

    @Test
    fun `legacy configured pages retain their decrypted order`() {
        val document = Jsoup.parse(
            """<div data-page="0" data-payload="${
                encrypt("""["https://image.mngtr.site/img_part.php?key=test&p=0"]""", "attr|$PAGE_KEY|reader")
            }" data-order="${encrypt("[100,201]", "order|$PAGE_KEY|reader")}"></div>""",
        )
        val config = ReaderConfigDto(
            data = ReaderAttributesDto(
                parts = "data-payload",
                pageIndex = "data-page",
                order = "data-order",
            ),
        )

        val pages = requireNotNull(MangaTRReaderContract.parseConfiguredPages(document, config, PAGE_KEY))
        assertEquals("100,201", pages.single().imageUrl?.toHttpUrl()?.fragment)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid grid is rejected instead of producing an incomplete bitmap`() {
        MangaTRReaderContract.scrambleCodes(seed = 1, grid = 0)
    }

    private fun encrypt(value: String, key: String): String {
        val bytes = value.toByteArray()
        val keyBytes = key.toByteArray()
        val encrypted = ByteArray(bytes.size) { index ->
            (bytes[index].toInt() xor keyBytes[index % keyBytes.size].toInt()).toByte()
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(encrypted)
    }

    private fun assertCodes(seed: Long, grid: Int, expected: List<Int>) {
        assertEquals(expected, MangaTRReaderContract.scrambleCodes(seed, grid))
        assertEquals(expected.indices.toList(), expected.map { it % 100 }.sorted())
    }

    private companion object {
        const val PAGE_KEY = "0123456789abcdef0123456789abcdef"
        val json = Json { ignoreUnknownKeys = true }
    }
}
