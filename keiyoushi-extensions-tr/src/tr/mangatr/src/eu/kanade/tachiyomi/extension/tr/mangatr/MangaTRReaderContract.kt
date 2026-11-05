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
    const val CACHE_VERSION_PREFIX = "v2-"

    private val json = Json { ignoreUnknownKeys = true }

    fun decrypt(value: String, key: String): String {
        val normalized = value.replace('-', '+').replace('_', '/')
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

    /** Current cap.js includes a pointer-delta segment; old readers omit it. */
    fun decryptConfig(encrypted: String, gateKey: String, pageKey: String, pointerDelta: String = ""): ReaderConfigDto? = listOf(
        "gate|$gateKey|$pointerDelta|reader",
        "gate|$gateKey||reader",
        "gate|$gateKey|reader",
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

        val pages = elements.mapIndexedNotNull { index, element ->
            val encryptedParts = element.attr(attributes.parts)
                .ifBlank { element.attr(attributes.parts.removePrefix("data-")) }
            if (encryptedParts.isBlank()) return@mapIndexedNotNull null
            val imageUrl = try {
                json.decodeFromString<List<String>>(
                    decrypt(encryptedParts, "attr|$pageKey|reader"),
                ).first()
            } catch (_: Exception) {
                return@mapIndexedNotNull null
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
            val cachePrefix = if (legacyOrder == null) CACHE_VERSION_PREFIX else ""
            val url = if (order.isEmpty()) {
                imageUrl
            } else {
                try {
                    imageUrl.toHttpUrl().newBuilder()
                        .fragment(cachePrefix + order.joinToString(","))
                        .toString()
                } catch (_: Exception) {
                    imageUrl
                }
            }
            Page(index, imageUrl = url)
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
