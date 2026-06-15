package eu.kanade.tachiyomi.extension.en.ehentai

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.util.Locale

private const val ALL_CATS = 1023
private const val CAT_MISC = 1
private const val CAT_DOUJINSHI = 2
private const val CAT_MANGA = 4
private const val CAT_ARTIST_CG = 8
private const val CAT_GAME_CG = 16
private const val CAT_IMAGE_SET = 32
private const val CAT_COSPLAY = 64
private const val CAT_ASIAN_PORN = 128
private const val CAT_NON_H = 256
private const val CAT_WESTERN = 512

class EHentai : HttpSource() {

    override val name = "E-Hentai"
    override val baseUrl = "https://e-hentai.org"
    override val lang = "en"
    override val supportsLatest = true

    private val apiUrl = "https://api.e-hentai.org/api.php"

    companion object {
        // e-hentai uses cursor pagination (?next=galleryId); store cursor between pages
        @Volatile private var cursorUrl = ""
    }

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", baseUrl)

    // ======================== Popular ========================

    override fun popularMangaRequest(page: Int): Request {
        if (page == 1) cursorUrl = ""
        val url = if (page == 1 || cursorUrl.isEmpty()) "$baseUrl/" else cursorUrl
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage =
        parseGalleryList(response)

    // ======================== Latest ========================

    override fun latestUpdatesRequest(page: Int): Request {
        if (page == 1) cursorUrl = ""
        val url = if (page == 1 || cursorUrl.isEmpty()) "$baseUrl/" else cursorUrl
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage =
        parseGalleryList(response)

    // ======================== Search ========================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val fCats = filters.filterIsInstance<CategoryFilter>().firstOrNull()?.getExcludeMask() ?: 0
        if (page == 1) cursorUrl = ""
        val url = if (page > 1 && cursorUrl.isNotEmpty()) {
            cursorUrl
        } else {
            buildString {
                append("$baseUrl/?")
                if (query.isNotEmpty()) append("f_search=${query.encodeUrl()}&")
                if (fCats > 0) append("f_cats=$fCats&")
            }.trimEnd('&', '?')
        }
        return GET(url.ifEmpty { "$baseUrl/" }, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage =
        parseGalleryList(response)

    private fun parseGalleryList(response: Response): MangasPage {
        val doc = Jsoup.parse(response.body!!.string())
        val rows = doc.select("table.itg.gltc tr:has(td.gl3c)")
        val mangas = rows.mapNotNull { row ->
            val anchor = row.selectFirst("td.gl3c a") ?: return@mapNotNull null
            val href = anchor.attr("href").removePrefix(baseUrl)
            val title = row.selectFirst("div.glink")?.text()?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            val thumbImg = row.selectFirst("td.gl2c img")
            val thumb = thumbImg?.attr("src")?.takeIf { !it.startsWith("data:") }
                ?: thumbImg?.attr("data-src")?.takeIf { it.isNotEmpty() }
            SManga.create().apply {
                url = href
                this.title = title
                thumbnail_url = thumb
            }
        }
        val nextMatch = Regex("""var nexturl="([^"]+)"""").find(doc.html())
        cursorUrl = nextMatch?.groupValues?.get(1)?.takeIf { it.isNotEmpty() } ?: ""
        return MangasPage(mangas, cursorUrl.isNotEmpty())
    }

    // ======================== Details ========================

    override fun mangaDetailsRequest(manga: SManga): Request {
        val (gid, token) = parseGalleryUrl(manga.url)
        val body = JSONObject()
            .put("method", "gdata")
            .put("gidlist", JSONArray().put(JSONArray().put(gid.toLong()).put(token)))
            .put("namespace", 1)
            .toString()
            .toRequestBody("application/json".toMediaType())
        return POST(apiUrl, headers, body)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val meta = JSONObject(response.body!!.string())
            .getJSONArray("gmetadata").getJSONObject(0)
        return SManga.create().apply {
            title = meta.optString("title").ifEmpty { meta.optString("title_jpn") }
            thumbnail_url = meta.optString("thumb")
            status = SManga.COMPLETED
            val tagsArr = meta.optJSONArray("tags")
            val tagList = if (tagsArr != null) {
                (0 until tagsArr.length()).map { tagsArr.getString(it) }
            } else emptyList()
            genre = tagList
                .filter { !it.startsWith("language:") && !it.startsWith("other:") }
                .joinToString(", ") { it.substringAfter(":").trim() }
            description = buildString {
                append("Category: ${meta.optString("category")}")
                append("\nPages: ${meta.optString("filecount")}")
                append("\nRating: ${meta.optString("rating")}")
                append("\nUploader: ${meta.optString("uploader")}")
            }
        }
    }

    // ======================== Chapters ========================

    override fun chapterListRequest(manga: SManga): Request =
        GET(baseUrl + manga.url, headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = Jsoup.parse(response.body!!.string())
        val title = doc.selectFirst("h1#gn")?.text() ?: "Gallery"
        val posted = doc.select("tr:has(td:contains(Posted)) td").getOrNull(1)?.text() ?: ""
        return listOf(
            SChapter.create().apply {
                name = title
                url = response.request.url.encodedPath
                date_upload = parseDate(posted)
            },
        )
    }

    // ======================== Pages ========================

    override fun pageListRequest(chapter: SChapter): Request =
        GET(baseUrl + chapter.url, headers)

    override fun pageListParse(response: Response): List<Page> {
        val firstDoc = Jsoup.parse(response.body!!.string())
        val galleryBase = response.request.url.toString().substringBefore("?")

        val links = mutableListOf<String>()
        firstDoc.select("div#gdt a[href]").forEach { links.add(it.attr("href")) }

        // Gallery images are split across internal pages (?p=0, ?p=1, …); collect them all
        val maxPage = firstDoc.select("table.ptt td a[href]")
            .mapNotNull { Regex("""[?&]p=(\d+)""").find(it.attr("href"))?.groupValues?.get(1)?.toInt() }
            .maxOrNull() ?: 0

        for (p in 1..maxPage) {
            val doc = Jsoup.parse(
                client.newCall(GET("$galleryBase?p=$p", headers)).execute().body!!.string(),
            )
            doc.select("div#gdt a[href]").forEach { links.add(it.attr("href")) }
        }

        return links.mapIndexed { i, url -> Page(i, url) }
    }

    override fun imageUrlParse(response: Response): String {
        val doc = Jsoup.parse(response.body!!.string())
        return doc.selectFirst("img#img")?.attr("src") ?: ""
    }

    // ======================== Filters ========================

    override fun getFilterList() = FilterList(CategoryFilter())

    class CategoryFilter : Filter.Group<CheckBoxItem>(
        "Categorías",
        listOf(
            CheckBoxItem("Doujinshi", CAT_DOUJINSHI),
            CheckBoxItem("Manga", CAT_MANGA),
            CheckBoxItem("Artist CG", CAT_ARTIST_CG),
            CheckBoxItem("Game CG", CAT_GAME_CG),
            CheckBoxItem("Western", CAT_WESTERN),
            CheckBoxItem("Non-H", CAT_NON_H),
            CheckBoxItem("Image Set", CAT_IMAGE_SET),
            CheckBoxItem("Cosplay", CAT_COSPLAY),
            CheckBoxItem("Asian Porn", CAT_ASIAN_PORN),
            CheckBoxItem("Misc", CAT_MISC),
        ),
    ) {
        // f_cats = bits to EXCLUDE; exclude everything NOT checked
        fun getExcludeMask(): Int {
            val selectedBits = state.filter { it.state }.sumOf { it.bit }
            return if (selectedBits == 0) 0 else ALL_CATS - selectedBits
        }
    }

    class CheckBoxItem(name: String, val bit: Int) : Filter.CheckBox(name, false)

    // ======================== Helpers ========================

    private fun parseGalleryUrl(url: String): Pair<String, String> {
        // url = /g/{gid}/{token}/
        val parts = url.trim('/').split("/")
        return parts[1] to parts[2]
    }

    private fun parseDate(str: String): Long = runCatching {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse(str.take(16))?.time ?: 0L
    }.getOrDefault(0L)

    private fun String.encodeUrl() = java.net.URLEncoder.encode(this, "UTF-8")
}
