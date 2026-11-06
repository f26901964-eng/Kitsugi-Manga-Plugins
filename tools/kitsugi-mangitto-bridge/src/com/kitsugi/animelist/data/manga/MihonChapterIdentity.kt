package com.kitsugi.animelist.data.manga

import java.net.URI

/** Repair legacy URL-only chapter records using their own parent, never global state. */
internal fun restoreMangittoChapterUrl(pkgName: String, chapterUrl: String, mangaUrl: String): String {
    if (pkgName != "eu.kanade.tachiyomi.extension.tr.mangitto") return chapterUrl
    val number = chapterUrl.trim()
    if (!number.matches(Regex("[0-9]+(?:\\.[0-9]+)?"))) return chapterUrl
    val parent = runCatching { URI(mangaUrl).path.orEmpty().trim('/') }.getOrNull() ?: return chapterUrl
    val parts = parent.split('/')
    val slug = when {
        parts.size == 1 -> parts[0]
        parts.size == 2 && parts[0] == "manga" -> parts[1]
        else -> return chapterUrl
    }
    if (!slug.matches(Regex("[a-zA-Z0-9_-]+"))) return chapterUrl
    return "/manga/$slug/$number"
}
