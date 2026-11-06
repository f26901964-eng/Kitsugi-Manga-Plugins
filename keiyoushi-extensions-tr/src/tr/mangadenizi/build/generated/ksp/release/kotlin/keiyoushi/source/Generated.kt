package keiyoushi.source

import eu.kanade.tachiyomi.extension.tr.mangadenizi.MangaDenizi
import kotlin.Long
import kotlin.String

internal class Generated : MangaDenizi() {
  protected override val filterFetchHint: String
    get() = "Tap 'Reset' to load filters"

  override val name: String
    get() = "MangaDenizi"

  override val lang: String
    get() = "tr"

  override val id: Long
    get() = 5_186_628_758_186_958_017L

  override val baseUrl: String
    get() = "https://mangadenizi.net"
}
