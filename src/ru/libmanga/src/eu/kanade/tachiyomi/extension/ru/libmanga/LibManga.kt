package eu.kanade.tachiyomi.extension.ru.libmanga

import android.app.Application
import android.content.SharedPreferences
import android.support.v7.preference.ListPreference
import android.support.v7.preference.PreferenceScreen
import com.github.salomonbrys.kotson.array
import com.github.salomonbrys.kotson.get
import com.github.salomonbrys.kotson.int
import com.github.salomonbrys.kotson.nullArray
import com.github.salomonbrys.kotson.nullString
import com.github.salomonbrys.kotson.obj
import com.github.salomonbrys.kotson.string
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import java.text.SimpleDateFormat
import java.util.Locale
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class LibManga : ConfigurableSource, HttpSource() {

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_${id}_2", 0x0000)
    }

    override val name: String = "Mangalib"

    override val lang = "ru"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    override val baseUrl: String = "https://mangalib.me"

    override fun headersBuilder() = Headers.Builder().apply {
        add("User-Agent", "Mozilla/5.0 (Windows NT 6.3; WOW64)")
        add("Accept", "*/*")
    }

    private val jsonParser = JsonParser()

    private var server: String? = preferences.getString(SERVER_PREF, null)

    override fun setupPreferenceScreen(screen: androidx.preference.PreferenceScreen) {
        val serverPref = androidx.preference.ListPreference(screen.context).apply {
            key = SERVER_PREF
            title = SERVER_PREF_Title
            entries = arrayOf("Основной", "Второй (тестовый)", "Сжатия (эконом трафика)")
            entryValues = arrayOf("secondary", "fourth", "compress")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                server = newValue.toString()
                true
            }
        }

        screen.addPreference(serverPref)
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val serverPref = ListPreference(screen.context).apply {
            key = SERVER_PREF
            title = SERVER_PREF_Title
            entries = arrayOf("Основной", "Второй (тестовый)", "Сжатия (эконом трафика)")
            entryValues = arrayOf("secondary", "fourth", "compress")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                server = newValue.toString()
                true
            }
        }

        screen.addPreference(serverPref)
    }

    override fun latestUpdatesRequest(page: Int) = GET(baseUrl, headers)

    private val latestUpdatesSelector = "div.updates__left"

    override fun latestUpdatesParse(response: Response): MangasPage {
        val elements = response.asJsoup().select(latestUpdatesSelector)
        val latestMangas = elements?.map { latestUpdatesFromElement(it) }
        if (latestMangas != null)
            return MangasPage(latestMangas, false) // TODO: use API
        return MangasPage(emptyList(), false)
    }

    private fun latestUpdatesFromElement(element: Element): SManga {
        val link = element.select("a").first()
        val img = link.select("img").first()
        val manga = SManga.create()
        manga.thumbnail_url = baseUrl + img.attr("data-src").substringAfter(baseUrl)
            .replace("cover_thumb", "cover_250x350")
        manga.setUrlWithoutDomain(link.attr("href"))
        manga.title = img.attr("alt")
        return manga
    }

    private var csrfToken: String = ""

    private fun catalogHeaders() = Headers.Builder()
        .apply {
            add("Accept", "application/json, text/plain, */*")
            add("X-Requested-With", "XMLHttpRequest")
            add("x-csrf-token", csrfToken)
        }
        .build()

    override fun popularMangaRequest(page: Int) = GET("$baseUrl/login", headers)

    override fun fetchPopularManga(page: Int): Observable<MangasPage> {
        if (csrfToken.isEmpty()) {
            return client.newCall(popularMangaRequest(page))
                .asObservableSuccess()
                .flatMap { response ->
                    // Obtain token
                    val resBody = response.body()!!.string()
                    csrfToken = "_token\" content=\"(.*)\"".toRegex().find(resBody)!!.groups[1]!!.value
                    return@flatMap fetchPopularMangaFromApi(page)
                }
        }
        return fetchPopularMangaFromApi(page)
    }

    private fun fetchPopularMangaFromApi(page: Int): Observable<MangasPage> {
        return client.newCall(POST("$baseUrl/filterlist?dir=desc&sort=views&page=$page", catalogHeaders()))
            .asObservableSuccess()
            .map { response ->
                popularMangaParse(response)
            }
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val resBody = response.body()!!.string()
        val result = jsonParser.parse(resBody).obj
        val items = result["items"]
        val popularMangas = items["data"].nullArray?.map { popularMangaFromElement(it) }

        if (popularMangas != null) {
            val hasNextPage = items["next_page_url"].nullString != null
            return MangasPage(popularMangas, hasNextPage)
        }
        return MangasPage(emptyList(), false)
    }

    private fun popularMangaFromElement(el: JsonElement) = SManga.create().apply {
        val slug = el["slug"].string
        title = el["name"].string
        thumbnail_url = "$baseUrl/uploads/cover/$slug/cover/cover_250x350.jpg"
        url = "/$slug"
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        val manga = SManga.create()

        if (document.html().contains("Манга удалена по просьбе правообладателей")) {
            manga.status = SManga.LICENSED
            return manga
        }

        val body = document.select("div.section__body").first()
        val rawCategory = body.select(".info-list__row:has(strong:contains(Тип)) > span").text()
        val category = when {
            rawCategory == "Комикс западный" -> "комикс"
            rawCategory.isNotBlank() -> rawCategory.toLowerCase()
            else -> "манга"
        }

        val genres = body.select(".info-list__row:has(strong:contains(Жанры)) > a").map { it.text() }
        manga.title = document.select(".manga-title small").text().substringBefore("/").trim()
        manga.thumbnail_url = body.select(".manga__cover").attr("src")
        manga.author = body.select(".info-list__row:nth-child(2) > a").text()
        manga.artist = body.select(".info-list__row:nth-child(3) > a").text()
        manga.status = when (
            body.select(".info-list__row:has(strong:contains(Перевод))")
                .first()
                .select("span.m-label")
                .text()) {
            "продолжается" -> SManga.ONGOING
            "завершен" -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
        manga.genre = genres.plusElement(category).joinToString { it.trim() }
        manga.description = body.select(".info-desc__content").text()
        return manga
    }

    private val chapterListSelector = "div.chapter-item"

    override fun chapterListParse(response: Response): List<SChapter> {
        val elements = response.asJsoup().select(chapterListSelector)
        val chapters = elements?.map { chapterFromElement(it) }
        return chapters ?: emptyList()
    }

    private fun chapterFromElement(element: Element): SChapter {

        val chapter = SChapter.create()

        val chapterLink = element.select("div.chapter-item__name > a").first()
        if (chapterLink != null) {
            chapter.setUrlWithoutDomain(chapterLink.attr("href"))
        } else {
            // Found multiple translate. Get first one for now
            val volume = element.attr("data-volume")
            val number = element.attr("data-number")
            val teams = jsonParser.parse(element.attr("data-teams"))
            val team = teams[0]["slug"].nullString
            val baseUrl = "${element.baseUri()}/v$volume/c$number"
            val url = if (team != null) "$baseUrl/$team" else baseUrl

            chapter.setUrlWithoutDomain(url)
        }

        chapter.name = element.select("div.chapter-item__name").first().text()
        chapter.date_upload = SimpleDateFormat("dd.MM.yyyy", Locale.US)
            .parse(element.select("div.chapter-item__date").text()).time
        return chapter
    }

    override fun prepareNewChapter(chapter: SChapter, manga: SManga) {
        """Глава\s(\d+)""".toRegex().find(chapter.name)?.let {
            val number = it.groups[1]?.value!!
            chapter.chapter_number = number.toFloat()
        }
    }

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        val chapInfo = document
            .select("script:containsData(window.__info)")
            .first()
            .html()
            .trim()
            .removePrefix("window.__info = ")
            .removeSuffix(";")

        val chapInfoJson = jsonParser.parse(chapInfo).obj
        val servers = chapInfoJson["servers"].asJsonObject
        val defaultServer: String = chapInfoJson["img"]["server"].string
        val imgUrl: String = chapInfoJson["img"]["url"].string

        val serverToUse = if (this.server == null) defaultServer else this.server
        val imageServerUrl: String = servers[serverToUse].string

        // Get pages
        val pagesArr = document
            .select("script:containsData(window.__pg)")
            .first()
            .html()
            .trim()
            .removePrefix("window.__pg = ")
            .removeSuffix(";")

        val pagesJson = jsonParser.parse(pagesArr).array

        val pages = mutableListOf<Page>()
        pagesJson.forEach { page ->
            pages.add(Page(page["p"].int, "", imageServerUrl + imgUrl + page["u"].string))
        }

        return pages
    }

    override fun imageUrlParse(response: Response): String = ""

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (csrfToken.isEmpty()) {
            val tokenResponse = client.newCall(popularMangaRequest(page)).execute()
            val resBody = tokenResponse.body()!!.string()
            csrfToken = "_token\" content=\"(.*)\"".toRegex().find(resBody)!!.groups[1]!!.value
        }
        val url = HttpUrl.parse("$baseUrl/filterlist?page=$page")!!.newBuilder()
        if (query.isNotEmpty()) {
            url.addQueryParameter("name", query)
        }
        (if (filters.isEmpty()) getFilterList() else filters).forEach { filter ->
            when (filter) {
                is CategoryList -> filter.state.forEach { category ->
                    if (category.state != Filter.TriState.STATE_IGNORE) {
                        url.addQueryParameter("types[]", category.id)
                    }
                }
                is StatusList -> filter.state.forEach { status ->
                    if (status.state != Filter.TriState.STATE_IGNORE) {
                        url.addQueryParameter("status[]", status.id)
                    }
                }
                is GenreList -> filter.state.forEach { genre ->
                    if (genre.state != Filter.TriState.STATE_IGNORE) {
                        url.addQueryParameter(if (genre.isIncluded()) "includeGenres[]" else "excludeGenres[]", genre.id)
                    }
                }
                is OrderBy -> {
                    url.addQueryParameter("dir", if (filter.state!!.ascending) "asc" else "desc")
                    url.addQueryParameter("sort", arrayOf("rate", "name", "views", "created_at", "chap_count")[filter.state!!.index])
                }
            }
        }
        return POST(url.toString(), catalogHeaders())
    }

    // Hack search method to add some results from search popup
    override fun searchMangaParse(response: Response): MangasPage {
        val searchRequest = response.request().url().queryParameter("name")
        val mangas = mutableListOf<SManga>()

        if (!searchRequest.isNullOrEmpty()) {
            val popupSearchHeaders = headers
                .newBuilder()
                .add("Accept", "application/json, text/plain, */*")
                .add("X-Requested-With", "XMLHttpRequest")
                .build()

            // +200ms
            val popup = client.newCall(
                GET("$baseUrl/search?query=$searchRequest", popupSearchHeaders))
                .execute().body()!!.string()

            val jsonList = jsonParser.parse(popup).array
            jsonList.forEach {
                mangas.add(popularMangaFromElement(it))
            }
        }
        val searchedMangas = popularMangaParse(response)

        // Filtered out what find in popup search
        mangas.addAll(searchedMangas.mangas.filter { search ->
            mangas.find { search.title == it.title } == null
        })

        return MangasPage(mangas, searchedMangas.hasNextPage)
    }

    private class SearchFilter(name: String, val id: String) : Filter.TriState(name)

    private class CategoryList(categories: List<SearchFilter>) : Filter.Group<SearchFilter>("Категории", categories)
    private class StatusList(statuses: List<SearchFilter>) : Filter.Group<SearchFilter>("Статус", statuses)
    private class GenreList(genres: List<SearchFilter>) : Filter.Group<SearchFilter>("Жанры", genres)

    override fun getFilterList() = FilterList(
        CategoryList(getCategoryList()),
        StatusList(getStatusList()),
        GenreList(getGenreList()),
        OrderBy()
    )

    private class OrderBy : Filter.Sort("Сортировка",
        arrayOf("Рейтинг", "Имя", "Просмотры", "Дата", "Кол-во глав"),
        Selection(0, false))

    /*
    * Use console
    * Object.entries(__FILTER_ITEMS__.types).map(([k, v]) => `SearchFilter("${v.label}", "${v.id}")`).join(',\n')
    * on /manga-list
    */
    private fun getCategoryList() = listOf(
        SearchFilter("Манга", "1"),
        SearchFilter("OEL-манга", "4"),
        SearchFilter("Манхва", "5"),
        SearchFilter("Маньхуа", "6"),
        SearchFilter("Сингл", "7"),
        SearchFilter("Руманга", "8"),
        SearchFilter("Комикс западный", "9")
    )

    /*
    * Use console
    * Object.entries(__FILTER_ITEMS__.status).map(([k, v]) => `SearchFilter("${v.label}", "${v.id}")`).join(',\n')
    * on /manga-list
    */
    private fun getStatusList() = listOf(
        SearchFilter("Продолжается", "1"),
        SearchFilter("Завершен", "2"),
        SearchFilter("Заморожен", "3")
    )

    /*
    * Use console
    * __FILTER_ITEMS__.genres.map(it => `SearchFilter("${it.name}", "${it.id}")`).join(',\n')
    * on /manga-list
    */
    private fun getGenreList() = listOf(
        SearchFilter("арт", "32"),
        SearchFilter("боевик", "34"),
        SearchFilter("боевые искусства", "35"),
        SearchFilter("вампиры", "36"),
        SearchFilter("веб", "78"),
        SearchFilter("гарем", "37"),
        SearchFilter("гендерная интрига", "38"),
        SearchFilter("героическое фэнтези", "39"),
        SearchFilter("детектив", "40"),
        SearchFilter("дзёсэй", "41"),
        SearchFilter("додзинси", "42"),
        SearchFilter("драма", "43"),
        SearchFilter("ёнкома", "75"),
        SearchFilter("игра", "44"),
        SearchFilter("исекай", "79"),
        SearchFilter("история", "45"),
        SearchFilter("киберпанк", "46"),
        SearchFilter("кодомо", "76"),
        SearchFilter("комедия", "47"),
        SearchFilter("махо-сёдзё", "48"),
        SearchFilter("меха", "49"),
        SearchFilter("мистика", "50"),
        SearchFilter("научная фантастика", "51"),
        SearchFilter("омегаверс", "77"),
        SearchFilter("повседневность", "52"),
        SearchFilter("постапокалиптика", "53"),
        SearchFilter("приключения", "54"),
        SearchFilter("психология", "55"),
        SearchFilter("романтика", "56"),
        SearchFilter("самурайский боевик", "57"),
        SearchFilter("сверхъестественное", "58"),
        SearchFilter("сёдзё", "59"),
        SearchFilter("сёдзё-ай", "60"),
        SearchFilter("сёнэн", "61"),
        SearchFilter("сёнэн-ай", "62"),
        SearchFilter("спорт", "63"),
        SearchFilter("сэйнэн", "64"),
        SearchFilter("трагедия", "65"),
        SearchFilter("триллер", "66"),
        SearchFilter("ужасы", "67"),
        SearchFilter("фантастика", "68"),
        SearchFilter("фэнтези", "69"),
        SearchFilter("школа", "70"),
        SearchFilter("эротика", "71"),
        SearchFilter("этти", "72"),
        SearchFilter("юри", "73"),
        SearchFilter("яой", "74")
    )

    companion object {
        private const val SERVER_PREF_Title = "Сервер изображений"
        private const val SERVER_PREF = "MangaLibImageServer"
    }
}
