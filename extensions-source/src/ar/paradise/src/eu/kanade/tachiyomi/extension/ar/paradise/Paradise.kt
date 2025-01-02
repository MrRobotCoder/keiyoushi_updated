package eu.kanade.tachiyomi.extension.ar.paradise

import eu.kanade.tachiyomi.multisrc.madara.Madara
import java.text.SimpleDateFormat
import java.util.Locale

class Paradise : Madara(
    "Paradise",
    "https://paradise-bl.com",
    "ar",
    dateFormat = SimpleDateFormat("MMM d", Locale("ar"))
) {
    override val useNewChapterEndpoint = true
}
