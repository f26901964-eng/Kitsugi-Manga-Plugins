package eu.kanade.tachiyomi.extension.tr.sadscans

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Serializable
class QueryResponseDto(
    val posts: List<PostDto> = emptyList(),
)

@Serializable
class PostResponseDto(
    val post: PostDto? = null,
)

@Serializable
class ChaptersResponseDto(
    val post: ChaptersPostDto? = null,
)

@Serializable
class ChaptersPostDto(
    val slug: String? = null,
    val chapters: List<ChapterDto> = emptyList(),
)

@Serializable
class ChapterResponseDto(
    val chapter: ChapterImagesDto? = null,
)

@Serializable
class ChapterImagesDto(
    val isLocked: Boolean? = null,
    val isAccessible: Boolean? = null,
    val images: List<ImageDto> = emptyList(),
)

@Serializable
class PostDto(
    val id: JsonPrimitive? = null,
    val slug: String? = null,
    val postTitle: String? = null,
    val featuredImage: String? = null,
    val author: String? = null,
    val artist: String? = null,
    val postContent: String? = null,
    val description: String? = null,
    val alternativeTitles: String? = null,
    val seriesStatus: String? = null,
    val seriesType: String? = null,
    val isNovel: Boolean? = null,
    val hot: Boolean? = null,
    val adult: Boolean? = null,
    val genres: List<GenreDto>? = null,
) {
    val idString: String?
        get() = id?.contentOrNull

    val isNovelEntry: Boolean
        get() = isNovel == true || seriesType.equals("NOVEL", ignoreCase = true)
}

@Serializable
class GenreDto(
    val id: Int? = null,
    val name: String? = null,
)

@Serializable
class ChapterDto(
    val id: JsonPrimitive? = null,
    val slug: String? = null,
    val number: Float? = null,
    val title: String? = null,
    val createdAt: String? = null,
    val isLocked: Boolean? = null,
    val isAccessible: Boolean? = null,
) {
    val idString: String?
        get() = id?.contentOrNull

    val locked: Boolean
        get() = isLocked == true || isAccessible == false
}

@Serializable
class ImageDto(
    val order: Int? = null,
    val url: String? = null,
    val src: String? = null,
    val image: String? = null,
) {
    val anyUrl: String?
        get() = url ?: src ?: image
}
