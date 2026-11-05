package eu.kanade.tachiyomi.extension.tr.mangatr

import kotlinx.serialization.Serializable

@Serializable
class ChallengeRequestDto(val challenge: String)

@Serializable
class ReaderGateDto(val k: String)

@Serializable
class ReaderConfigDto(
    val data: ReaderAttributesDto,
    val seeds: Map<String, Long> = emptyMap(),
    val grid: Int = 2,
)

@Serializable
class ReaderAttributesDto(
    val parts: String,
    val pageIndex: String,
    val order: String? = null,
)
