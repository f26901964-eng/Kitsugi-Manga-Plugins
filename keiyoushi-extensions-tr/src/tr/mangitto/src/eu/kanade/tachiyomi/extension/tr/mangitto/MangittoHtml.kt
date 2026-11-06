package eu.kanade.tachiyomi.extension.tr.mangitto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import org.jsoup.nodes.Document
import java.util.Locale

/** Nuxt's payload is a reference table, not an array of ordinary manga objects. */
internal object MangittoHtml {
    fun mangaObjects(document: Document): List<JsonObject> {
        val script = document.selectFirst("script#__NUXT_DATA__") ?: error("Mangitto: ana sayfa verisi bulunamadı")
        val table = Json.parseToJsonElement(script.data()) as JsonArray
        fun scalar(reference: kotlinx.serialization.json.JsonElement?) = (reference as? JsonPrimitive)?.intOrNull?.let { table.getOrNull(it) } as? JsonPrimitive
        return table.filterIsInstance<JsonObject>()
            .filter { "slug" in it && "title" in it && "coverImage" in it }
            .map { item ->
                JsonObject(item.mapNotNull { (key, reference) -> scalar(reference)?.let { key to it } }.toMap())
            }
            .filter { !it.text("slug").isNullOrBlank() && !it.text("title").isNullOrBlank() }
            .distinctBy { it.text("slug") }
            .also { check(it.isNotEmpty()) { "Mangitto: ana sayfada seri bulunamadı" } }
    }

    fun matches(item: JsonObject, query: String): Boolean {
        val needle = normalize(query)
        return listOf("title", "romaji", "english", "native", "slug").any {
            item.text(it)?.let(::normalize)?.contains(needle) == true
        }
    }

    private fun normalize(value: String) = value.replace('ı', 'i').replace('İ', 'I')
        .lowercase(Locale.ROOT).replace('-', ' ').trim()

    // Only reader images belonging to this exact chapter. Never include avatars/covers.
    fun readerImages(document: Document, slug: String, chapter: String): List<String> {
        val path = "/manga/$slug/$chapter/"
        return document.select("img[src], img[data-src]").mapNotNull { image ->
            sequenceOf(image.absUrl("data-src"), image.absUrl("src"))
                .firstOrNull { url ->
                    url.startsWith("https://") &&
                        runCatching { java.net.URI(url).path.startsWith(path) }.getOrDefault(false)
                }
        }.distinct()
    }

    fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
