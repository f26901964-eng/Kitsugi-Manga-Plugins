package eu.kanade.tachiyomi.multisrc.initlisenext

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * GET /api/series yanıt gövdesi. Alanlar siteler arasında sabit
 * (duckscans, lunariscans, ailurascans, oblivionscans kanıtları).
 */
@Serializable
data class CatalogResponseDto(
    val success: Boolean = false,
    val data: List<SeriesDto> = emptyList(),
)

@Serializable
data class SeriesDto(
    val title: String = "",
    val slug: String = "",
    val image: String? = null,
    @SerialName("alternativeNames") val alternativeNames: List<String> = emptyList(),
)

/**
 * Seri sayfası uçuş verisindeki "initialChapters" dizisinin elemanları.
 * pages dizisi bölümün tam sayfa yollarını içerir; okuyucu kilidi bu
 * veriyi etkilemez (kullanıcı tarayıcı kanıtları: 79 ve 114 bölümlük
 * serilerde tüm sayfalar anonim oturumda gömülü geliyor).
 */
@Serializable
data class ChapterDto(
    @SerialName("chapterNumber") val chapterNumber: Float? = null,
    val title: String? = null,
    val slug: String? = null,
    val pages: List<String> = emptyList(),
    val createdAt: String? = null,
    val updatedAt: String? = null,
)
