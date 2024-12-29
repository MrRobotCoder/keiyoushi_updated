package eu.kanade.tachiyomi.extension.pt.randomscan

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.extension.pt.randomscan.dto.CapituloDto
import eu.kanade.tachiyomi.extension.pt.randomscan.dto.MainPageDto
import eu.kanade.tachiyomi.extension.pt.randomscan.dto.MangaDto
import eu.kanade.tachiyomi.extension.pt.randomscan.dto.SearchResponseDto
import eu.kanade.tachiyomi.lib.randomua.addRandomUAPreferenceToScreen
import eu.kanade.tachiyomi.lib.randomua.getPrefCustomUA
import eu.kanade.tachiyomi.lib.randomua.getPrefUAType
import eu.kanade.tachiyomi.lib.randomua.setRandomUserAgent
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservable
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.getValue

class LuraToon : HttpSource(), ConfigurableSource {

    override val baseUrl = "https://luratoons.net"

    override val name = "Lura Toon"

    override val lang = "pt-BR"

    override val supportsLatest = true

    override val versionId = 2

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private fun buildClient(withZip: Boolean = true) = network.cloudflareClient
        .newBuilder()
        .apply {
            rateLimit(25, 1, TimeUnit.MINUTES)
            addInterceptor(::loggedVerifyInterceptor)
            setRandomUserAgent(
                preferences.getPrefUAType(),
                preferences.getPrefCustomUA()
            )
            if (withZip) addInterceptor(LuraZipInterceptor()::zipImageInterceptor)
        }
        .build()

    override val client = buildClient()
    private val clientWithoutZip = buildClient(withZip = false)

    // ============================== Popular =============================

    override fun popularMangaRequest(page: Int) = GET("$baseUrl/api/main/?part=${page - 1}", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.parseAs<MainPageDto>()

        val mangas = document.top_10.map {
            SManga.create().apply {
                title = it.title
                thumbnail_url = "$baseUrl${it.capa}"
                url = "/${it.slug}/"
            }
        }

        return MangasPage(mangas, false)
    }

    // ============================== Latest =============================

    override fun latestUpdatesRequest(page: Int) = popularMangaRequest(page)

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.parseAs<MainPageDto>()

        val mangas = document.lancamentos.map {
            SManga.create().apply {
                title = it.title
                thumbnail_url = "$baseUrl${it.capa}"
                url = "/${it.slug}/"
            }
        }

        return MangasPage(mangas, document.lancamentos.isNotEmpty())
    }

    // ============================== Search ==============================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList) = GET("$baseUrl/api/autocomplete/$query", headers)

    override fun searchMangaParse(response: Response): MangasPage {
        val mangas = response.parseAs<SearchResponseDto>().obras.map {
            SManga.create().apply {
                title = it.titulo
                thumbnail_url = "$baseUrl${it.capa}"
                url = "/${it.slug}/"
            }
        }

        return MangasPage(mangas, false)
    }

    // ============================== Details =============================

    override fun getMangaUrl(manga: SManga) = "$baseUrl${manga.url}"

    override fun mangaDetailsRequest(manga: SManga): Request {
        return GET("$baseUrl/api/obra/${manga.url.trimStart('/')}", headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        return response.parseAs<MangaDto>().let { data ->
            SManga.create().apply {
                title = data.titulo
                author = data.autor
                artist = data.artista
                genre = data.generos.joinToString(", ") { it.name }
                status = when (data.status) {
                    "Em Lançamento" -> SManga.ONGOING
                    "Finalizado" -> SManga.COMPLETED
                    else -> SManga.UNKNOWN
                }
                thumbnail_url = "$baseUrl${data.capa}"

                val category = data.tipo
                val synopsis = data.sinopse
                description = "Tipo: $category\n\n$synopsis"
            }
        }
    }

    // ============================== Chapters =============================

    override fun getChapterUrl(chapter: SChapter) = "$baseUrl${chapter.url}"

    override fun chapterListRequest(manga: SManga) = mangaDetailsRequest(manga)

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        return client.newCall(chapterListRequest(manga))
            .asObservable()
            .map { response ->
                chapterListParse(manga, response)
            }
    }

    override fun chapterListParse(response: Response) = throw UnsupportedOperationException()

    fun chapterListParse(manga: SManga, response: Response): List<SChapter> {
        if (response.code == 404) {
            throw Exception("Capitulos não encontrados, tente migrar o manga, alguns nomes da LuraToon mudaram")
        }

        val comics = response.parseAs<MangaDto>()

        return comics.caps.sortedByDescending {
            it.num
        }.map { chapterFromElement(manga, it) }
    }

    private fun chapterFromElement(manga: SManga, capitulo: CapituloDto) = SChapter.create().apply {
        val capSlug = capitulo.slug.trimStart('/')
        val mangaUrl = manga.url.trimEnd('/').trimStart('/')
        url = "/api/obra/$mangaUrl/?slug=$capSlug"
        name = capitulo.num.toString().removeSuffix(".0")
        date_upload = runCatching {
            dateFormat.parse(capitulo.data)!!.time
        }.getOrDefault(0L)
    }

    // ============================== Pages ===============================

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()

    override fun pageListParse(response: Response): List<Page> {
        val manga = response.parseAs<MangaDto>()
        val pathSegments = response.request.url.pathSegments
        val slug = response.request.url.queryParameter("slug").toString()
        val cap = manga.caps.find { it.slug == slug } ?: throw Exception("Capitulo não encontrado")

        var maxCapsQuantity = -1
        val executor = Executors.newCachedThreadPool()
        val tasks: MutableList<Future<*>> = mutableListOf()

        for (i in 39 downTo 0) {
            val task: Future<*> = executor.submit {
                val url = "$baseUrl/api/c7109c0d/${manga.id}/${cap.id}/$i"
                val request = GET(url, headers)
                val response = clientWithoutZip.newCall(request).execute()
                if (response.code == 200) {
                    synchronized(this) {
                        if (i > maxCapsQuantity) {
                            maxCapsQuantity = i
                        }
                    }
                }
            }
            tasks.add(task)
        }

        tasks.forEach { it.get() }

        executor.shutdown()

        if (maxCapsQuantity == -1) {
            throw Exception("Nenhum capítulo retornou status 200")
        }

        return (0..maxCapsQuantity).map { i ->
            Page(i, baseUrl, "$baseUrl/api/c7109c0d/${manga.id}/${cap.id}/$i?obra_id=${manga.id}&cap_id=${cap.id}&slug=${pathSegments[2]}&cap_slug=${cap.slug}&salt=lura")
        }
    }

    // ============================== Utils ===============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        addRandomUAPreferenceToScreen(screen)
    }

    private inline fun <reified T> Response.parseAs(): T {
        return json.decodeFromString<T>(body.string())
    }

    private fun loggedVerifyInterceptor(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val pathSegments = response.request.url.pathSegments
        if (response.request.url.pathSegments.contains("login") || pathSegments.isEmpty()) {
            throw Exception("Faça o login na WebView para acessar o contéudo")
        }
        if (response.code == 429) {
            throw Exception("A LuraToon lhe bloqueou por acessar rápido demais, aguarde por volta de 1 minuto e tente novamente")
        }
        return response
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault()).apply {
        timeZone = TimeZone.getTimeZone("America/Sao_Paulo")
    }
}
