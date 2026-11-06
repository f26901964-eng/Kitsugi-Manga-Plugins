package eu.kanade.tachiyomi.extension.tr.hayalistic

import eu.kanade.tachiyomi.multisrc.madara.MadaraNoAjax
import keiyoushi.annotation.Source
import java.time.format.DateTimeFormatter
import java.util.Locale

@Source
abstract class Hayalistic : MadaraNoAjax() {
    // The site moved from hayalistic.online to hayalistic.site. On the old host the chapter
    // pages render without the page list (only the "login" placeholder), so the reader was
    // always empty. Images are served from cdn.hayalistic.site and are plain (no scramble).
    override val chapterDateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT)
}
