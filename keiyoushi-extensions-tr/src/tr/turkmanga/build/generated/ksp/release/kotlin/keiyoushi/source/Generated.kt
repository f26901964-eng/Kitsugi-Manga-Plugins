package keiyoushi.source

import eu.kanade.tachiyomi.extension.tr.turkmanga.Turkmanga
import kotlin.Long
import kotlin.String

internal class Generated : Turkmanga() {
  protected override val filterFetchHint: String
    get() = "Tap 'Reset' to load filters"

  override val name: String
    get() = "Turkmanga"

  override val lang: String
    get() = "tr"

  override val id: Long
    get() = 4_516_321_559_375_126_021L

  override val baseUrl: String
    get() = "https://turkmanga.org"
}
