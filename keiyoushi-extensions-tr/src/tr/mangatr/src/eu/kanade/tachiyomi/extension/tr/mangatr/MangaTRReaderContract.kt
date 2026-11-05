package eu.kanade.tachiyomi.extension.tr.mangatr

import eu.kanade.tachiyomi.source.model.Page
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.nio.charset.StandardCharsets
import java.util.Base64

/** The encryption, strip order and orientation contract of Manga-TR's cap.js reader. */
internal object MangaTRReaderContract {
    const val CACHE_VERSION_PREFIX = "v4-"
    const val LEGACY_SLICE_PREFIX = "v3-"

    private val json = Json { ignoreUnknownKeys = true }
    private val GATE_PATH_REGEX = Regex("""(?:_fpx|gatePath|mtrGatePath|readerGate)\s*=\s*["']([^"']+)["']""")
    private val READER_EASE_REGEX = Regex("""["']?ease["']?\s*:\s*["']([^"']+)["']""")
    private val READER_FLOW_REGEX = Regex("""--ui-flow\s*:\s*([^\s;"']+)""")

    fun decrypt(value: String, key: String): String {
        // Script data normally contains a leading newline and indentation.
        // java.util.Base64's basic decoder rejects those bytes; the browser
        // reference explicitly strips them before decoding the same config.
        val normalized = value.trim().replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        val bytes = Base64.getDecoder().decode(padded)
        val keyBytes = key.toByteArray(StandardCharsets.UTF_8)
        val xored = ByteArray(bytes.size) { index ->
            (bytes[index].toInt() xor keyBytes[index % keyBytes.size].toInt()).toByte()
        }
        return String(xored, StandardCharsets.UTF_8)
    }

    /** Integer equivalent of the site's two canvas readbacks; no WebView is needed. */
    fun canvasFingerprint(token: String): String {
        if (token.length < 16) return ""
        val values = listOf(0, 4, 8, 12).map { offset ->
            token.substring(offset, offset + 4).toIntOrNull(16) ?: 0
        }
        val base = listOf(
            values[0] * 37 % 200 + 20,
            values[1] * 41 % 200 + 20,
            values[2] * 43 % 200 + 20,
        )
        val extra = listOf(
            values[1] * 17 % 100 + 10,
            values[2] * 19 % 100 + 10,
            values[3] * 23 % 100 + 10,
        )
        val lighter = base.mapIndexed { index, value -> minOf(255, value + extra[index]) }
        return base.joinToString(",") + "|" + lighter.joinToString(",")
    }

    fun gatePath(rawHtml: String): String? = GATE_PATH_REGEX.find(rawHtml)?.groupValues?.get(1)

    fun readerEase(rawHtml: String): String = READER_EASE_REGEX.find(rawHtml)?.groupValues?.get(1) ?: "mTr_v4"

    fun readerFlow(rawHtml: String): String = READER_FLOW_REGEX.find(rawHtml)?.groupValues?.get(1) ?: "20"

    /** FNV-1a pointer checksum calculated by the site's _calcPointerDelta function. */
    fun pointerDelta(nonce: String, ease: String = "", flow: String = ""): String {
        if (nonce.isEmpty()) return ""
        var hash = 0x811c9dc5.toInt()
        val input = "$nonce|$ease#${flow}k9!"
        for (character in input) {
            hash = (hash xor character.code) * 0x01000193
        }
        return hash.toUInt().toString(16).padStart(8, '0')
    }

    /** jQuery's live flow (currently 84) differs from the CSS fallback (20). */
    fun pointerDeltaCandidates(nonce: String, ease: String, flow: String): List<String> = listOf(
        pointerDelta(nonce, ease, flow),
        pointerDelta(nonce, ease, "84"),
        pointerDelta(nonce, ease, ""),
        pointerDelta(nonce, "", flow),
        pointerDelta(nonce, "", "84"),
        pointerDelta(nonce, "", ""),
        "",
    ).distinct()

    /** Current cap.js includes a pointer-delta segment; old readers omit it. */
    fun decryptConfig(
        encrypted: String,
        gateKey: String,
        pageKey: String,
        pointerDelta: String = "",
        additionalPointerDeltas: List<String> = emptyList(),
    ): ReaderConfigDto? = (
        (listOf(pointerDelta) + additionalPointerDeltas).map { "gate|$gateKey|$it|reader" } +
            listOf("gate|$gateKey||reader", "gate|$gateKey|reader")
        ).distinct().firstNotNullOfOrNull { key ->
        try {
            json.decodeFromString<ReaderConfigDto>(
                decrypt(decrypt(encrypted, key), "boot|$pageKey|reader"),
            )
        } catch (_: Exception) {
            null
        }
    }

    /** Builds pages through the same config/seed path used by the Android source. */
    fun parseConfiguredPages(document: Document, config: ReaderConfigDto, pageKey: String): List<Page>? {
        val attributes = config.data
        val elements = try {
            document.select("[${attributes.parts}]")
                .sortedBy {
                    it.attr(attributes.pageIndex).toIntOrNull()
                        ?: it.attr(attributes.pageIndex.removePrefix("data-")).toIntOrNull()
                        ?: Int.MAX_VALUE
                }
        } catch (_: Exception) {
            emptyList<Element>()
        }
        if (elements.isEmpty()) return null

        val pages = mutableListOf<Page>()
        elements.forEach { element ->
            val encryptedParts = element.attr(attributes.parts)
                .ifBlank { element.attr(attributes.parts.removePrefix("data-")) }
            if (encryptedParts.isBlank()) return@forEach
            val imageUrl = try {
                json.decodeFromString<List<String>>(
                    decrypt(encryptedParts, "attr|$pageKey|reader"),
                ).first()
            } catch (_: Exception) {
                return@forEach
            }

            val legacyOrder = if (config.seeds.isEmpty() && attributes.order != null) {
                val orderAttribute = attributes.order
                val orderRaw = element.attr(orderAttribute).ifBlank {
                    element.attr(orderAttribute.removePrefix("data-"))
                }
                try {
                    orderRaw.takeIf { it.isNotBlank() }
                        ?.let { decrypt(it, "order|$pageKey|reader") }
                        ?.let { json.decodeFromString<List<Int>>(it) }
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }
            val order = legacyOrder ?: run {
                val pageIndex = element.attr(attributes.pageIndex)
                    .ifBlank { element.attr(attributes.pageIndex.removePrefix("data-")) }
                val seed = seedForPage(config.seeds, pageIndex)
                val grid = config.grid.takeIf { it in 1..100 } ?: 2
                scrambleCodes(seed, grid)
            }

            fun pageWithFragment(fragment: String) {
                val url = try {
                    imageUrl.toHttpUrl().newBuilder().fragment(fragment).toString()
                } catch (_: Exception) {
                    imageUrl
                }
                pages += Page(pages.size, imageUrl = url)
            }

            if (legacyOrder != null) {
                // Older readers explicitly describe one reconstructed bitmap.
                if (legacyOrder.isEmpty()) {
                    pages += Page(pages.size, imageUrl = imageUrl)
                } else {
                    pageWithFragment(legacyOrder.joinToString(","))
                }
            } else if (order.isEmpty()) {
                pages += Page(pages.size, imageUrl = imageUrl)
            } else {
                // CSS shows the strips together as one original image. Emit one
                // Android page with the same full dimensions, never two halves.
                pageWithFragment("$CACHE_VERSION_PREFIX${order.joinToString(",")}")
            }
        }
        return pages.ifEmpty { null }
    }

    /**
     * cap.js uses xorshift32, Fisher-Yates shuffling and then one random flip per
     * source strip. Each result is flip * 100 + destination, as expected by the
     * bitmap interceptor. Zero is replaced by the site's fixed non-zero seed.
     */
    fun scrambleCodes(seed: Long, grid: Int): List<Int> {
        require(grid in 1..100) { "Unsupported reader grid: $grid" }

        var state = seed.toInt()
        if (state == 0) state = 123456789

        fun nextUInt(): Long {
            state = state xor (state shl 13)
            state = state xor (state ushr 17)
            state = state xor (state shl 5)
            return state.toLong() and 0xffffffffL
        }

        val destinations = IntArray(grid) { it }
        for (index in grid - 1 downTo 1) {
            val other = (nextUInt() % (index + 1).toLong()).toInt()
            val value = destinations[index]
            destinations[index] = destinations[other]
            destinations[other] = value
        }

        return List(grid) { sourceIndex ->
            val flip = (nextUInt() % 4L).toInt()
            flip * 100 + destinations[sourceIndex]
        }
    }

    fun flipHorizontally(code: Int): Boolean = code / 100 and 1 != 0

    fun flipVertically(code: Int): Boolean = code / 100 and 2 != 0

    /** This follows cap.js: page-specific seed, then seed 0, then numeric zero. */
    fun seedForPage(seeds: Map<String, Long>, pageIndex: String): Long = seeds[pageIndex] ?: seeds["0"] ?: 0L
}
