package eu.kanade.tachiyomi.extension.pt.readmangas

import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import okhttp3.OkHttpClient
import java.text.SimpleDateFormat
import java.util.Locale

class ReadMangas : MangaThemesia(
    "Read Mangas",
    "https://app.loobyt.com",
    "pt-BR",
    dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale("pt", "BR")),
) {
    override val client: OkHttpClient = super.client.newBuilder()
        .rateLimit(3)
        .build()
}
