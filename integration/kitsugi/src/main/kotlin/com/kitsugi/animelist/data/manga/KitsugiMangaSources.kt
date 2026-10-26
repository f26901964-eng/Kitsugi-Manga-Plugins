package com.kitsugi.animelist.data.manga

import android.content.Context
import com.kitsugi.animelist.data.manga.golgebahcesi.GolgeBahcesiSource
import eu.kanade.tachiyomi.network.NetworkHelper

/** Single recovery source. Archived APKs, parsers, favourites and progress are not deleted. */
object KitsugiMangaSources {
    val singleSourceMode: Boolean = true

    @Volatile private var source: MangaSource? = null

    @Synchronized
    fun initialize(context: Context) {
        if (source != null) return
        val app = context.applicationContext
        val network = uy.kohesive.injekt.Injekt.get(NetworkHelper::class.java)
        val candidate = GolgeBahcesiSource(app, network.client) { network.defaultUserAgentProvider() }
        val preferences = app.getSharedPreferences("manga_recovery_profile", Context.MODE_PRIVATE)
        if (preferences.getInt("golgebahcesi_native_version", 0) < 1) {
            // Do this once, NOT on every launch: real failures must still enter cooldown.
            MangaSourceStateStore(app).resetSource(candidate)
            preferences.edit().putInt("golgebahcesi_native_version", 1).apply()
        }
        source = candidate
    }

    fun getLoadedSources(): List<MangaSource> = listOfNotNull(source)

    fun getSource(extensionId: String): MangaSource? = source?.takeIf {
        extensionId in setOf(it.pkgName, it.pkgName.replace('.', '_'), "golgebahcesi")
    }
}
